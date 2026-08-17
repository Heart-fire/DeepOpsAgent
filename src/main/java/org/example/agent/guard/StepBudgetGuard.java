package org.example.agent.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 步数预算守卫（最大步数限制的代码级实现）。
 *
 * 设计要点：
 * 1) 两层熔断，对应简历里"最大步数限制避免无效循环"：
 *    a. 总量预算：一次诊断会话所有工具调用总次数达到 {@code aiops.max-tool-calls} 后，
 *       后续工具调用直接返回结构化收敛信号，迫使模型带着已有证据 FINISH；
 *    b. 单工具熔断：同一工具连续失败达到 {@code aiops.tool-fail-threshold} 次后熔断该工具
 *       （把原来只写在提示词里的"同一工具失败 3 次停止"变成代码硬限，模型不听 prompt 也拦得住）。
 * 2) 超限语义是"收敛"不是"失败"：返回的 JSON 明确告诉模型预算耗尽、
 *    应基于已收集证据输出最终报告并说明未完成部分，与提示词里的诚实反馈要求一致。
 * 3) 生命周期对齐 {@code AgentTraceRecorder}：sessionId + volatile currentSessionId
 *    （@Tool 方法在 Reactor 异步线程调用，演示级单用户实现，多并发需换 Reactor Context）。
 * 4) 拦截位置：各 @Tool 方法入口调用 {@link #tryAcquire(String)}（预算在执行前扣减），
 *    在记录 trace 的 finally 里调用 {@link #recordResult(String, boolean)} 回报成败。
 */
@Component
public class StepBudgetGuard {

    private static final Logger logger = LoggerFactory.getLogger(StepBudgetGuard.class);

    /** 一次会话允许的最大工具调用总次数（按"告警→指标→日志→文档→佐证"典型链路 5-8 次，留足 Replan 空间） */
    @Value("${aiops.max-tool-calls:18}")
    private int maxToolCalls;

    /** 同一工具连续失败熔断阈值 */
    @Value("${aiops.tool-fail-threshold:3}")
    private int toolFailThreshold;

    /** sessionId -> 会话预算状态 */
    private final Map<String, BudgetState> states = new ConcurrentHashMap<>();

    /** 当前活跃会话（演示级 volatile，与 AgentTraceRecorder.currentTraceId 同模式） */
    private volatile String currentSessionId;

    /** 开启一次会话预算（与 startTrace 同时机调用，重置计数） */
    public void beginSession(String sessionId) {
        states.put(sessionId, new BudgetState());
        this.currentSessionId = sessionId;
        logger.info("[STEP-GUARD] 会话预算开启 sessionId={} maxToolCalls={} failThreshold={}",
                sessionId, maxToolCalls, toolFailThreshold);
    }

    /** 结束会话（清理状态，防止内存泄漏） */
    public void endSession(String sessionId) {
        BudgetState s = states.remove(sessionId);
        if (s != null) {
            logger.info("[STEP-GUARD] 会话预算结束 sessionId={} toolCallsUsed={}/{}",
                    sessionId, s.toolCallCount, maxToolCalls);
        }
        if (sessionId != null && sessionId.equals(this.currentSessionId)) {
            this.currentSessionId = null;
        }
    }

    /**
     * 工具方法入口检查：预算未耗尽 且 该工具未被熔断 时扣减一次预算并放行。
     *
     * @param toolName 工具名（用各 *Tools 类里的 TOOL_ 常量）
     * @return true=放行执行；false=拒绝，调用方应直接返回 {@link #blockedResponse(String)}
     */
    public boolean tryAcquire(String toolName) {
        String sid = this.currentSessionId;
        if (sid == null) {
            return true;   // 无活跃会话（独立调用工具等场景），不设限
        }
        BudgetState state = states.get(sid);
        if (state == null) {
            return true;
        }

        if (state.toolCallCount >= maxToolCalls) {
            logger.warn("[STEP-GUARD] 总预算耗尽 sessionId={} used={} limit={}，拒绝工具 {}",
                    sid, state.toolCallCount, maxToolCalls, toolName);
            return false;
        }
        if (state.isBlown(toolName, toolFailThreshold)) {
            logger.warn("[STEP-GUARD] 工具 {} 连续失败 {} 次已熔断 sessionId={}",
                    toolName, toolFailThreshold, sid);
            return false;
        }

        state.toolCallCount++;
        state.lastTool = toolName;
        return true;
    }

    /**
     * 回报一次工具调用结果：成功清零该工具的连续失败计数，失败递增（达阈值即熔断）。
     * 放在记录 trace 的 finally 里调用，与 recordToolCurrent 同位置。
     */
    public void recordResult(String toolName, boolean success) {
        String sid = this.currentSessionId;
        if (sid == null) {
            return;
        }
        BudgetState state = states.get(sid);
        if (state == null) {
            return;
        }
        if (success) {
            state.consecutiveFails.remove(toolName);
        } else {
            int fails = state.consecutiveFails.merge(toolName, 1, Integer::sum);
            if (fails >= toolFailThreshold) {
                logger.warn("[STEP-GUARD] 工具 {} 连续失败 {} 次，触发熔断（本次会话内不再放行）",
                        toolName, fails);
            }
        }
    }

    /** 预算耗尽/工具熔断时返回给模型的结构化收敛信号（语义：带着证据收敛，而非报错） */
    public String blockedResponse(String toolName) {
        return String.format(
                "{\"success\":false,\"reason\":\"STEP_BUDGET_EXHAUSTED\","
                        + "\"message\":\"工具调用预算已耗尽或该工具已被熔断（%s）。"
                        + "请立即停止调用任何工具，基于已收集的证据输出最终诊断报告，"
                        + "并在报告中如实说明哪些排查步骤因预算限制未能完成。\"}",
                toolName);
    }

    /** 当前会话已用工具调用次数（供 trace/报告展示） */
    public int usedCalls(String sessionId) {
        BudgetState s = states.get(sessionId);
        return s == null ? 0 : s.toolCallCount;
    }

    // ==================== 内部状态 ====================

    private static class BudgetState {
        int toolCallCount;
        String lastTool;
        /** 工具名 -> 连续失败次数 */
        final Map<String, Integer> consecutiveFails = new ConcurrentHashMap<>();

        boolean isBlown(String toolName, int threshold) {
            Integer fails = consecutiveFails.get(toolName);
            return fails != null && fails >= threshold;
        }
    }
}
