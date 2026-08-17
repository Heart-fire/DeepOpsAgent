package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.LokiLogsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 聊天服务
 * 封装 ReactAgent 对话的公共逻辑，包括模型创建、系统提示词构建、Agent 配置等
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired
    private LokiLogsTools lokiLogsTools;   // 日志查询默认走 Loki（替代原腾讯云 CLS）

    @Autowired(required = false)  // 仅 cls.mock-enabled=true 才注册（老的 CLS Mock 工具，向后兼容）
    private QueryLogsTools queryLogsTools;

    @Autowired
    private ToolCallbackProvider tools;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    @Value("${aiops.chat-model:qwen-max}")
    private String chatModelName;   // 普通对话模型名，作为 Prometheus token 指标的 model tag

    /**
     * 创建 DashScope API 实例
     */
    public DashScopeApi createDashScopeApi() {
        return DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
    }

    /**
     * 创建 ChatModel
     * @param temperature 控制随机性 (0.0-1.0)
     * @param maxToken 最大输出长度
     * @param topP 核采样参数
     */
    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, double temperature, int maxToken, double topP) {
        // 模型路由入口：默认模型；AiOps 等场景可调下面的重载指定强/小模型
        return createChatModel(dashScopeApi, DashScopeChatModel.DEFAULT_MODEL_NAME, temperature, maxToken, topP);
    }

    /**
     * 创建指定模型的 ChatModel（模型路由用）。
     * 例如：Planner/Supervisor 传 qwen-max（强模型，低温严谨），Executor 传 qwen-turbo（小模型，省钱省延迟）。
     */
    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, String modelName, double temperature, int maxToken, double topP) {
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(modelName)
                        .withTemperature(temperature)
                        .withMaxToken(maxToken)
                        .withTopP(topP)
                        .build())
                .build();
    }

    /**
     * 创建标准对话 ChatModel（默认参数）
     */
    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
        return createChatModel(dashScopeApi, chatModelName, 0.7, 2000, 0.9);
    }

    /**
     * 构建系统提示词（历史摘要 + 最近 N 轮完整保留的混合策略）。
     *
     * 上下文结构（Token 预算口径）：
     *   基础角色说明（固定）
     *   + 历史摘要（窗口外历史经 LLM 滚动压缩，≤300 字，保"诉求/结论/关键实体"）
     *   + 最近 N 轮完整对话（chat-history.recent-pairs，保细节与指代）
     *
     * @param summary  窗口外历史摘要（无摘要传空串）
     * @param history  最近 N 轮完整历史消息
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(String summary, List<Map<String, String>> history) {
        StringBuilder systemPromptBuilder = new StringBuilder();

        // 基础系统提示
        systemPromptBuilder.append("你是一个专业的智能运维助手，可以获取当前时间、搜索内部文档知识库、查询服务器实时监控指标（CPU/内存/磁盘），以及查询 Prometheus 告警。\n");
        systemPromptBuilder.append("当用户询问时间相关问题时，使用 getCurrentDateTime 工具。\n");
        systemPromptBuilder.append("当用户需要查询公司内部文档、流程、最佳实践或技术指南时，使用 queryInternalDocs 工具（可按 service、docType 元数据过滤）。\n");
        systemPromptBuilder.append("当用户询问服务器当前的资源使用情况（CPU使用率、内存使用率、磁盘使用率、系统负载等具体数值）时，使用 queryMetric 工具，工具说明中已提供常用 PromQL 示例，按需选用。\n");
        systemPromptBuilder.append("当用户需要查询哪些 Prometheus 告警正在触发时，使用 queryPrometheusAlerts 工具。\n");
        systemPromptBuilder.append("当用户需要查询应用日志、系统日志、慢查询日志时，使用 queryLogs 工具（基于 Loki，用 LogQL 语法查询，例如 {service=\"payment-service\"} |= \"CPU\"）；不确定有哪些标签时可先调用 getAvailableLogStreams 了解可用标签。\n\n");

        // 历史摘要（窗口外历史压缩）
        if (summary != null && !summary.isBlank()) {
            systemPromptBuilder.append("--- 更早对话摘要（历史压缩，细节可能已丢失） ---\n");
            systemPromptBuilder.append(summary.trim()).append("\n");
            systemPromptBuilder.append("--- 摘要结束 ---\n\n");
        }

        // 添加最近 N 轮完整历史
        if (history != null && !history.isEmpty()) {
            systemPromptBuilder.append("--- 最近对话（完整保留） ---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    systemPromptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    systemPromptBuilder.append("助手: ").append(content).append("\n");
                }
            }
            systemPromptBuilder.append("--- 最近对话结束 ---\n\n");
        }

        systemPromptBuilder.append("请基于以上对话历史，回答用户的新问题。");

        return systemPromptBuilder.toString();
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    public Object[] buildMethodToolsArray() {
        List<Object> tools = new ArrayList<>();
        tools.add(dateTimeTools);
        tools.add(internalDocsTools);
        tools.add(queryMetricsTools);
        tools.add(lokiLogsTools);          // 日志查询默认走 Loki（@Tool 直连 Loki HTTP API）
        if (queryLogsTools != null) {      // cls.mock-enabled=true 时才追加老的 CLS Mock 工具（判断现在真正生效）
            tools.add(queryLogsTools);
        }
        return tools.toArray();
    }

    /**
     * 获取工具回调列表，mcp服务提供的工具
     */
    public ToolCallback[] getToolCallbacks() {
        return tools.getToolCallbacks();
    }

    /**
     * 记录可用工具列表：mcp服务提供的工具
     */
    public void logAvailableTools() {
        ToolCallback[] toolCallbacks = tools.getToolCallbacks();
        logger.info("可用工具列表:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }

    /**
     * 创建 ReactAgent
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray())
                .tools(getToolCallbacks())
                .build();
    }

    /**
     * 执行 ReactAgent 对话（非流式）
     * @param agent ReactAgent 实例
     * @param question 用户问题
     * @return AI 回复
     */
    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        logger.info("执行 ReactAgent.call() - 自动处理工具调用");
        var response = agent.call(question);
        String answer = response.getText();
        logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
        return answer;
    }
}
