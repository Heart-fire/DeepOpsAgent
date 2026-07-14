package org.example.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 调用链追踪记录器（轻量、内存版，演示级）。
 *
 * 设计要点：
 * 1) 一次对话 / AIOps 编排对应一个 Trace，按 sessionId 存内存 Map（重启即失，演示足够）。
 * 2) {@code currentTraceId} 用 volatile：@Tool 方法会被 Reactor 在异步线程调用，
 *    靠这个字段把工具 span 归属到当前 trace。
 *    【局限】这是单用户演示级实现，多并发会串；生产应改为 Reactor Context 或 ThreadLocal 传播。
 * 3) Trace 数据同时输出到日志（endTrace 打摘要）+ GET /api/trace/{sessionId} endpoint。
 * 4) 成本治理走 Prometheus：每次 LLM 调用的 token 通过 MeterRegistry 记成
 *    aiops_llm_tokens_total{model,type} 指标，Grafana 按模型聚合 + 单价变量算成本。
 */
@Component
public class AgentTraceRecorder {

    private static final Logger logger = LoggerFactory.getLogger(AgentTraceRecorder.class);

    private static final int MAX_TEXT = 2000;

    private final Map<String, TraceModel.Trace> traces = new ConcurrentHashMap<>();

    /** 当前活跃 trace id（演示级 volatile，单用户场景） */
    private volatile String currentTraceId;

    /** Prometheus 指标注册表（actuator + micrometer-registry-prometheus 已自动配置） */
    @Autowired
    private MeterRegistry meterRegistry;

    /** 开启一次 trace（在 Controller 进入 Agent 推理前调用） */
    public void startTrace(String sessionId, String type) {
        TraceModel.Trace trace = new TraceModel.Trace();
        trace.setSessionId(sessionId);
        trace.setType(type);
        trace.setStartMs(System.currentTimeMillis());
        traces.put(sessionId, trace);
        this.currentTraceId = sessionId;
        logger.info("[TRACE-START] sessionId={} type={}", sessionId, type);
    }

    /**
     * 记录一次完整的 LLM 轮次（在 AGENT_MODEL_FINISHED 事件调用）。
     * 连续的 LLM_CALL span 即 ReAct 的 thought 序列，token 从 Usage 提取。
     */
    public void recordLlm(String sessionId, String model, Usage usage, String accumulatedText) {
        TraceModel.Trace trace = traces.get(sessionId);
        if (trace == null) {
            return;
        }
        TraceModel.Span span = newSpan(null, TraceModel.SpanType.LLM_CALL, "llm");
        span.setModel(model);   // LLM span 记模型名，Prometheus 指标按 model 聚合算成本
        span.setOutput(truncate(accumulatedText, MAX_TEXT));
        span.setEndMs(System.currentTimeMillis());
        if (usage != null) {
            // DashScope 流式可能只在末尾发 usage，中途为 null 时这里自然留空
            span.setPromptTokens(usage.getPromptTokens());
            span.setCompletionTokens(usage.getCompletionTokens());
            span.setTotalTokens(usage.getTotalTokens());
            // 上报 Prometheus token 指标（Grafana 按 model 聚合 + 单价变量算成本）
            recordTokenMetrics(model, usage);
        }
        span.setStatus("OK");
        trace.getSpans().add(span);
    }

    /**
     * 把一次 LLM 调用的 token 记到 Prometheus：
     * aiops_llm_tokens_total{model,type=prompt|completion} —— 成本核心（token × 单价）
     * aiops_llm_calls_total{model} —— 调用次数，看模型路由分布（Executor 用 turbo 多、Planner 用 max 少）
     */
    private void recordTokenMetrics(String model, Usage usage) {
        if (meterRegistry == null) {
            return;
        }
        String m = (model == null || model.isEmpty()) ? "unknown" : model;
        Integer prompt = usage.getPromptTokens();
        Integer completion = usage.getCompletionTokens();
        if (prompt != null) {
            meterRegistry.counter("aiops_llm_tokens_total", "model", m, "type", "prompt")
                    .increment(prompt);
        }
        if (completion != null) {
            meterRegistry.counter("aiops_llm_tokens_total", "model", m, "type", "completion")
                    .increment(completion);
        }
        meterRegistry.counter("aiops_llm_calls_total", "model", m).increment();
    }

    /**
     * 工具方法内部调用：归属到 currentTraceId。
     * 在每个 @Tool 方法的 finally 里调用，可靠拿到工具入参/出参/耗时。
     */
    public void recordToolCurrent(String toolName, String input, String output, long durationMs, String status) {
        String sid = this.currentTraceId;
        if (sid == null) {
            return;   // 无活跃 trace，静默跳过
        }
        TraceModel.Trace trace = traces.get(sid);
        if (trace == null) {
            return;
        }
        TraceModel.Span span = newSpan(null, TraceModel.SpanType.TOOL_CALL, toolName);
        span.setInput(truncate(input, MAX_TEXT));
        span.setOutput(truncate(output, MAX_TEXT));
        span.setEndMs(span.getStartMs() + durationMs);
        span.setStatus(status);
        trace.getSpans().add(span);
    }

    /** 工具调用占位：从 AGENT_TOOL_FINISHED 事件拿 node 名（入参出参拿不到时的兜底） */
    public void recordToolNodePlaceholder(String sessionId, String node) {
        TraceModel.Trace trace = traces.get(sessionId);
        if (trace == null) {
            return;
        }
        TraceModel.Span span = newSpan(null, TraceModel.SpanType.TOOL_CALL, "tool:" + node);
        span.setEndMs(System.currentTimeMillis());
        span.setStatus("OK");
        trace.getSpans().add(span);
    }

    /** 结束一次 trace（在 Agent 推理完成/出错回调里调用） */
    public void endTrace(String sessionId, String status) {
        TraceModel.Trace trace = traces.get(sessionId);
        if (trace == null) {
            return;
        }
        trace.setEndMs(System.currentTimeMillis());

        int llmCount = 0, toolCount = 0;
        long tokens = 0;
        for (TraceModel.Span s : trace.getSpans()) {
            if (s.getType() == TraceModel.SpanType.LLM_CALL) {
                llmCount++;
                if (s.getTotalTokens() != null) {
                    tokens += s.getTotalTokens();
                }
            } else if (s.getType() == TraceModel.SpanType.TOOL_CALL) {
                toolCount++;
            }
        }
        logger.info("[TRACE-END] sessionId={} status={} durationMs={} llmSpans={} toolSpans={} totalTokens={}",
                sessionId, status, trace.getDurationMs(), llmCount, toolCount, tokens);
    }

    public TraceModel.Trace getTrace(String sessionId) {
        return traces.get(sessionId);
    }

    private TraceModel.Span newSpan(String parentId, TraceModel.SpanType type, String name) {
        TraceModel.Span span = new TraceModel.Span();
        span.setId(UUID.randomUUID().toString());
        span.setParentId(parentId);
        span.setType(type);
        span.setName(name);
        span.setStartMs(System.currentTimeMillis());
        return span;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }
}
