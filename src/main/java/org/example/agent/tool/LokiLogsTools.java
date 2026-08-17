package org.example.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.example.agent.guard.StepBudgetGuard;
import org.example.observability.AgentTraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Loki 日志查询工具（替代原腾讯云 CLS 的 QueryLogsTools）。
 *
 * 设计要点：
 * 1) @Tool 直连 Loki HTTP API（/loki/api/v1/query_range），不走 MCP。
 *    Agent 用 LogQL 查询，能真实查到 LokiPushRunner 启动时灌入的业务日志。
 * 2) Mock/真实切换照搬 QueryMetricsTools 范式：@Value("${loki.mock-enabled}") + if/else。
 *    mock-enabled=false（默认）连真实 Loki；true 返回本地 Mock（离线兜底）。
 * 3) 返回结构对齐原 QueryLogsTools.LogEntry（timestamp/level/service/instance/message/metrics），
 *    这样 Planner/Executor 的系统提示词与告警/文档的关联关系不用改。
 * 4) 内部接 AgentTraceRecorder：每次调用记录一个工具 span（thought-action-observation 的 action）。
 */
@Component
public class LokiLogsTools {

    private static final Logger logger = LoggerFactory.getLogger(LokiLogsTools.class);

    public static final String TOOL_QUERY_LOGS = "queryLogs";
    public static final String TOOL_GET_AVAILABLE_LOG_STREAMS = "getAvailableLogStreams";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${loki.base-url:http://localhost:3100}")
    private String lokiBaseUrl;

    @Value("${loki.timeout:10}")
    private int timeout;

    @Value("${loki.mock-enabled:false}")
    private boolean mockEnabled;

    @Autowired
    private AgentTraceRecorder agentTraceRecorder;

    /** 步数预算守卫：总量限流 + 同工具失败熔断，超限返回结构化收敛信号 */
    @Autowired
    private StepBudgetGuard stepBudgetGuard;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private OkHttpClient httpClient;

    @jakarta.annotation.PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .readTimeout(Duration.ofSeconds(timeout))
                .build();
        logger.info("✅ LokiLogsTools 初始化成功, Loki URL: {}, Mock模式: {}", lokiBaseUrl, mockEnabled);
    }

    /**
     * 查询日志（LogQL）。
     *
     * @param logql        Loki LogQL 表达式，如 {service="payment-service"} |= "CPU"
     * @param rangeMinutes 时间范围（分钟），默认 60
     * @param limit        返回条数，默认 20，最大 100
     */
    @Tool(description = "Query logs from Loki using LogQL syntax. " +
            "Use this tool to search application logs, system metrics, slow queries, and system events. " +
            "LogQL examples: " +
            "1) {service=\"payment-service\"} |= \"CPU\" — 查 payment-service 含 CPU 的日志; " +
            "2) {job=\"application-logs\"} |= \"ERROR\" — 查应用错误日志; " +
            "3) {service=\"order-service\"} |= \"OutOfMemoryError\" — 查 OOM 日志; " +
            "4) {job=\"database-slow-query\"} — 查慢查询日志; " +
            "5) {job=\"application-logs\"} — 查所有应用日志。 " +
            "logql (required, LogQL 表达式); rangeMinutes (optional, 默认60); limit (optional, 默认20, 最大100).")
    public String queryLogs(
            @ToolParam(description = "Loki LogQL 查询表达式，如 {service=\"payment-service\"} |= \"CPU\"") String logql,
            @ToolParam(description = "时间范围（分钟），默认 60") Integer rangeMinutes,
            @ToolParam(description = "返回日志条数，默认 20，最大 100") Integer limit) {

        if (!stepBudgetGuard.tryAcquire(TOOL_QUERY_LOGS)) {
            return stepBudgetGuard.blockedResponse(TOOL_QUERY_LOGS);
        }
        long t0 = System.currentTimeMillis();
        String safeLogql = (logql == null || logql.isBlank()) ? "{job=\"application-logs\"}" : logql;
        int actualRange = (rangeMinutes == null || rangeMinutes <= 0) ? 60 : rangeMinutes;
        int actualLimit = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        String inputDesc = "logql=" + safeLogql + ", range=" + actualRange + "min, limit=" + actualLimit;

        String result = null;
        String status = "OK";
        try {
            List<LogEntry> logs;
            if (mockEnabled) {
                logs = buildMockLogs(safeLogql, actualLimit);
                logger.info("Loki Mock 模式，返回 {} 条日志", logs.size());
            } else {
                logs = queryRange(safeLogql, actualRange, actualLimit);
                logger.info("Loki 查询完成: logql={} 返回 {} 条日志", safeLogql, logs.size());
            }
            stepBudgetGuard.recordResult(TOOL_QUERY_LOGS, true);

            QueryLogsOutput output = new QueryLogsOutput();
            output.setSuccess(true);
            output.setLogql(safeLogql);
            output.setRangeMinutes(actualRange);
            output.setLimit(actualLimit);
            output.setLogs(logs);
            output.setTotal(logs.size());
            output.setMessage(logs.isEmpty()
                    ? "未查到匹配日志（Loki 中可能没有对应数据，请检查 LogQL 或扩大时间范围）"
                    : String.format("成功查询到 %d 条日志", logs.size()));
            result = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            return result;
        } catch (Exception e) {
            logger.error("查询 Loki 日志失败", e);
            status = "ERROR";
            stepBudgetGuard.recordResult(TOOL_QUERY_LOGS, false);
            result = buildErrorResponse("查询 Loki 失败: " + e.getMessage());
            return result;
        } finally {
            agentTraceRecorder.recordToolCurrent(TOOL_QUERY_LOGS, inputDesc, result,
                    System.currentTimeMillis() - t0, status);
        }
    }

    /**
     * 列出 Loki 中可用的日志标签（label），查询前可先调用了解有哪些 service/job/level。
     */
    @Tool(description = "List available log labels in Loki (service, job, level). " +
            "Call this first if you are unsure which labels exist before writing a LogQL query.")
    public String getAvailableLogStreams() {
        if (!stepBudgetGuard.tryAcquire(TOOL_GET_AVAILABLE_LOG_STREAMS)) {
            return stepBudgetGuard.blockedResponse(TOOL_GET_AVAILABLE_LOG_STREAMS);
        }
        long t0 = System.currentTimeMillis();
        String result = null;
        String status = "OK";
        try {
            List<LogStreamInfo> streams = mockEnabled ? buildMockStreams() : fetchLabels();
            LogStreamsOutput output = new LogStreamsOutput();
            output.setSuccess(true);
            output.setStreams(streams);
            output.setMessage(String.format("共 %d 个可用标签", streams.size()));
            result = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            stepBudgetGuard.recordResult(TOOL_GET_AVAILABLE_LOG_STREAMS, true);
            return result;
        } catch (Exception e) {
            logger.error("获取 Loki 标签失败", e);
            status = "ERROR";
            stepBudgetGuard.recordResult(TOOL_GET_AVAILABLE_LOG_STREAMS, false);
            result = buildErrorResponse("获取标签失败: " + e.getMessage());
            return result;
        } finally {
            agentTraceRecorder.recordToolCurrent(TOOL_GET_AVAILABLE_LOG_STREAMS, "list labels", result,
                    System.currentTimeMillis() - t0, status);
        }
    }

    // ==================== 真实 Loki 调用 ====================

    private List<LogEntry> queryRange(String logql, int rangeMinutes, int limit) throws Exception {
        long endNs = Instant.now().toEpochMilli() * 1_000_000L;
        long startNs = Instant.now().minus(rangeMinutes, ChronoUnit.MINUTES).toEpochMilli() * 1_000_000L;
        String url = String.format(
                "%s/loki/api/v1/query_range?query=%s&start=%d&end=%d&limit=%d&direction=backward",
                lokiBaseUrl, URLEncoder.encode(logql, StandardCharsets.UTF_8), startNs, endNs, limit);
        logger.debug("请求 Loki: {}", url);

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Loki HTTP " + response.code());
            }
            String body = response.body().string();
            return parseQueryRange(body);
        }
    }

    private List<LogEntry> parseQueryRange(String body) throws Exception {
        List<LogEntry> logs = new ArrayList<>();
        JsonNode root = objectMapper.readTree(body);
        if (!"success".equals(root.path("status").asText())) {
            throw new RuntimeException("Loki 返回非 success: " + root.path("error").asText(""));
        }
        JsonNode results = root.path("data").path("result");
        for (JsonNode r : results) {
            JsonNode stream = r.path("stream");
            String streamService = stream.path("service").asText("");
            String streamLevel = stream.path("level").asText("");
            String streamJob = stream.path("job").asText("");
            JsonNode values = r.path("values");
            for (JsonNode v : values) {
                if (v.size() < 2) {
                    continue;
                }
                String ns = v.get(0).asText();
                String line = v.get(1).asText();
                logs.add(parseLine(ns, line, streamService, streamLevel, streamJob));
            }
        }
        return logs;
    }

    private LogEntry parseLine(String ns, String line, String streamService, String streamLevel, String streamJob) {
        LogEntry entry = new LogEntry();
        entry.setTimestamp(formatNs(ns));
        try {
            JsonNode node = objectMapper.readTree(line);
            entry.setLevel(firstNonEmpty(node.path("level").asText(""), streamLevel, "INFO"));
            entry.setService(firstNonEmpty(node.path("service").asText(""), streamService, streamJob));
            entry.setInstance(node.path("instance").asText(""));
            entry.setMessage(node.path("message").asText(line));
            JsonNode metrics = node.path("metrics");
            if (metrics.isObject()) {
                Map<String, String> m = new LinkedHashMap<>();
                metrics.fields().forEachRemaining(f -> m.put(f.getKey(), f.getValue().asText("")));
                entry.setMetrics(m);
            } else {
                entry.setMetrics(new HashMap<>());
            }
        } catch (Exception e) {
            // 非 JSON 行：整行当 message，level/service 用 stream 标签兜底
            entry.setLevel(streamLevel.isEmpty() ? "INFO" : streamLevel);
            entry.setService(streamService.isEmpty() ? streamJob : streamService);
            entry.setInstance("");
            entry.setMessage(line);
            entry.setMetrics(new HashMap<>());
        }
        return entry;
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private String formatNs(String ns) {
        try {
            long ms = Long.parseLong(ns) / 1_000_000L;
            return FORMATTER.format(Instant.ofEpochMilli(ms));
        } catch (Exception e) {
            return ns;
        }
    }

    private List<LogStreamInfo> fetchLabels() throws Exception {
        List<LogStreamInfo> list = new ArrayList<>();
        Request request = new Request.Builder().url(lokiBaseUrl + "/loki/api/v1/labels").get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return list;
            }
            assert response.body() != null;
            JsonNode root = objectMapper.readTree(response.body().string());
            JsonNode data = root.path("data");
            for (JsonNode label : data) {
                LogStreamInfo info = new LogStreamInfo();
                info.setLabel(label.asText());
                info.setValues(Collections.emptyList());
                list.add(info);
            }
        }
        return list;
    }

    // ==================== Mock 兜底（离线/演示，默认不启用） ====================

    private List<LogEntry> buildMockLogs(String logql, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        Instant now = Instant.now();
        String q = logql.toLowerCase();

        if (q.contains("payment") || q.contains("cpu")) {
            for (int i = 0; i < 3; i++) {
                LogEntry e = new LogEntry();
                e.setTimestamp(FORMATTER.format(now.minus(i * 2L, ChronoUnit.MINUTES)));
                e.setLevel("WARN");
                e.setService("payment-service");
                e.setInstance("pod-payment-service-7d8f9c6b5-x2k4m");
                e.setMessage(String.format("CPU使用率过高: %.1f%%, 进程: java (PID:1), 线程数:245", 92.0 - i * 1.5));
                e.setMetrics(Map.of("cpu_usage", String.format("%.1f", 92.0 - i * 1.5), "load_average_1m", "3.82"));
                logs.add(e);
            }
        }
        if (q.contains("order") || q.contains("oom") || q.contains("outofmemory")) {
            LogEntry e = new LogEntry();
            e.setTimestamp(FORMATTER.format(now.minus(12L, ChronoUnit.MINUTES)));
            e.setLevel("FATAL");
            e.setService("order-service");
            e.setInstance("pod-order-service-5c7d8e9f1-m3n2p");
            e.setMessage("java.lang.OutOfMemoryError: Java heap space at OrderService.processLargeOrder:156");
            e.setMetrics(Map.of("heap_used", "3.9GB", "heap_max", "4GB"));
            logs.add(e);
        }
        if (q.contains("slow") || q.contains("database-slow-query") || q.contains("query")) {
            LogEntry e = new LogEntry();
            e.setTimestamp(FORMATTER.format(now.minus(3L, ChronoUnit.MINUTES)));
            e.setLevel("WARN");
            e.setService("mysql");
            e.setInstance("mysql-primary-01");
            e.setMessage("慢查询: SELECT * FROM orders WHERE user_id=? ... 执行时间:3.2s 扫描行数:1245678");
            e.setMetrics(Map.of("query_time_sec", "3.2", "rows_examined", "1245678"));
            logs.add(e);
        }
        if (logs.isEmpty()) {
            LogEntry e = new LogEntry();
            e.setTimestamp(FORMATTER.format(now.minus(1L, ChronoUnit.MINUTES)));
            e.setLevel("INFO");
            e.setService("generic-service");
            e.setInstance("instance-0");
            e.setMessage("(Mock) 未精确匹配到关键词，返回通用日志: " + logql);
            e.setMetrics(new HashMap<>());
            logs.add(e);
        }
        return logs.size() > limit ? new ArrayList<>(logs.subList(0, limit)) : logs;
    }

    private List<LogStreamInfo> buildMockStreams() {
        List<LogStreamInfo> list = new ArrayList<>();
        for (String s : List.of("service", "job", "level")) {
            LogStreamInfo info = new LogStreamInfo();
            info.setLabel(s);
            info.setValues(List.of());
            list.add(info);
        }
        return list;
    }

    private String buildErrorResponse(String message) {
        try {
            QueryLogsOutput output = new QueryLogsOutput();
            output.setSuccess(false);
            output.setMessage(message);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            return String.format("{\"success\":false,\"message\":\"%s\"}", message);
        }
    }

    // ==================== 数据模型 ====================

    @Data
    public static class LogEntry {
        @JsonProperty("timestamp")
        private String timestamp;
        @JsonProperty("level")
        private String level;
        @JsonProperty("service")
        private String service;
        @JsonProperty("instance")
        private String instance;
        @JsonProperty("message")
        private String message;
        @JsonProperty("metrics")
        private Map<String, String> metrics;
    }

    @Data
    public static class QueryLogsOutput {
        @JsonProperty("success")
        private boolean success;
        @JsonProperty("logql")
        private String logql;
        @JsonProperty("range_minutes")
        private int rangeMinutes;
        @JsonProperty("limit")
        private int limit;
        @JsonProperty("logs")
        private List<LogEntry> logs;
        @JsonProperty("total")
        private int total;
        @JsonProperty("message")
        private String message;
    }

    @Data
    public static class LogStreamInfo {
        @JsonProperty("label")
        private String label;
        @JsonProperty("values")
        private List<String> values;
    }

    @Data
    public static class LogStreamsOutput {
        @JsonProperty("success")
        private boolean success;
        @JsonProperty("streams")
        private List<LogStreamInfo> streams;
        @JsonProperty("message")
        private String message;
    }
}
