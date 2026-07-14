package org.example.observability;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent 调用链追踪数据模型。
 *
 * 一次对话或一次 AIOps 编排 = 1 个 Trace，内含多个 Span（LLM 调用 / 工具调用）。
 * 数据模型：trace 是一次 Agent 推理，span 是其中的每次 LLM 调用或工具调用。
 * thought-action-observation 的"思考"由 LLM_CALL span 的 output 承载（ReAct 模型每轮先输出 Thought），
 * "行动"由 TOOL_CALL span 承载，"观察"由工具返回结果承载。
 */
public class TraceModel {

    /** Span 类型 */
    public enum SpanType { LLM_CALL, TOOL_CALL, THINK }

    @Data
    public static class Trace {
        @JsonProperty("sessionId")
        private String sessionId;

        @JsonProperty("type")
        private String type;   // CHAT / AI_OPS

        @JsonProperty("startMs")
        private long startMs;

        @JsonProperty("endMs")
        private Long endMs;

        @JsonProperty("spans")
        private List<Span> spans = new CopyOnWriteArrayList<>();

        /** 派生字段：总耗时，供前端展示 */
        public long getDurationMs() {
            return endMs == null ? System.currentTimeMillis() - startMs : endMs - startMs;
        }
    }

    @Data
    public static class Span {
        @JsonProperty("id")
        private String id;

        @JsonProperty("parentId")
        private String parentId;   // 可为 null（扁平结构足够演示）

        @JsonProperty("type")
        private SpanType type;

        @JsonProperty("name")
        private String name;       // 工具名 / "llm"

        @JsonProperty("input")
        private String input;

        @JsonProperty("output")
        private String output;

        @JsonProperty("startMs")
        private long startMs;

        @JsonProperty("endMs")
        private Long endMs;

        @JsonProperty("promptTokens")
        private Integer promptTokens;          // 可空：DashScope 流式可能只在末尾发 usage

        @JsonProperty("completionTokens")
        private Integer completionTokens;

        @JsonProperty("totalTokens")
        private Integer totalTokens;

        @JsonProperty("status")
        private String status;      // OK / ERROR

        @JsonProperty("model")
        private String model;       // LLM span 才填（qwen-max/qwen-turbo），Prometheus 指标按 model 聚合算成本

        public long getDurationMs() {
            return endMs == null ? System.currentTimeMillis() - startMs : endMs - startMs;
        }
    }
}
