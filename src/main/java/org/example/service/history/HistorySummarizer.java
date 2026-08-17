package org.example.service.history;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 历史摘要生成器：把窗口外的旧对话压缩成一段摘要，供 system prompt 注入。
 *
 * 设计要点：
 * 1) 用 qwen-turbo（低成本）+ 低 maxToken（500）——摘要本身是"有损压缩"，
 *    只保留"用户问过什么、已确认了什么结论"，细节由最近 N 轮完整保留兜底。
 * 2) 摘要失败不阻塞主流程：返回旧摘要 + 拼接裁剪，会话照常进行（降级而非报错）。
 * 3) 增量压缩：新一批超窗消息与已有摘要一起再压缩（滚动摘要），
 *    避免每次都从全量历史重算，控制摘要调用成本随轮次线性而非平方增长。
 */
@Component
public class HistorySummarizer {

    private static final Logger logger = LoggerFactory.getLogger(HistorySummarizer.class);

    private static final String SUMMARIZE_PROMPT = """
            你是多轮对话历史压缩器。请把下面的【已有摘要】和【新增对话】合并压缩为一段新的摘要。
            要求：
            1. 保留：用户的核心诉求、已确认的结论、关键实体（服务名/告警名/指标名）。
            2. 丢弃：寒暄、重复表述、已失效的中间猜测。
            3. 输出不超过 300 字的中文段落，不要任何格式前缀。

            【已有摘要】
            %s

            【新增对话】
            %s
            """;

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    @Value("${chat-history.summarizer-model:qwen-turbo}")
    private String modelName;

    private DashScopeChatModel chatModel;

    private DashScopeChatModel model() {
        if (chatModel == null) {
            chatModel = DashScopeChatModel.builder()
                    .dashScopeApi(DashScopeApi.builder().apiKey(apiKey).build())
                    .defaultOptions(DashScopeChatOptions.builder()
                            .withModel(modelName)
                            .withTemperature(0.2)
                            .withMaxToken(500)
                            .build())
                    .build();
        }
        return chatModel;
    }

    /**
     * 滚动摘要：旧摘要 + 新超窗消息 -> 新摘要。
     *
     * @param previousSummary 上一次的摘要（首次为空串）
     * @param overflowMessages 本轮被裁出窗口的旧消息（user/assistant 交替）
     * @return 新摘要；失败时返回 previousSummary（降级）
     */
    public String summarize(String previousSummary, List<java.util.Map<String, String>> overflowMessages) {
        if (overflowMessages == null || overflowMessages.isEmpty()) {
            return previousSummary == null ? "" : previousSummary;
        }
        try {
            StringBuilder dialog = new StringBuilder();
            for (java.util.Map<String, String> m : overflowMessages) {
                String role = "assistant".equals(m.get("role")) ? "助手" : "用户";
                dialog.append(role).append(": ").append(truncate(m.get("content"), 500)).append("\n");
            }
            String promptText = String.format(SUMMARIZE_PROMPT,
                    (previousSummary == null || previousSummary.isBlank()) ? "（无）" : previousSummary,
                    dialog);

            ChatResponse response = model().call(new Prompt(List.of(new UserMessage(promptText))));
            String summary = response.getResult().getOutput().getText();
            logger.info("历史摘要生成成功，长度: {}", summary == null ? 0 : summary.length());
            return summary == null || summary.isBlank() ? previousSummary : summary.trim();
        } catch (Exception e) {
            logger.warn("历史摘要生成失败，降级沿用旧摘要: {}", e.getMessage());
            return previousSummary == null ? "" : previousSummary;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
