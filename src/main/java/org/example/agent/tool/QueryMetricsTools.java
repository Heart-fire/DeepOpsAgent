package org.example.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.example.agent.guard.StepBudgetGuard;
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
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Prometheus 告警查询工具
 * 用于查询 Prometheus 的活动告警信息
 */
@Component
public class QueryMetricsTools {

    private static final Logger logger = LoggerFactory.getLogger(QueryMetricsTools.class);
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_PROMETHEUS_ALERTS = "queryPrometheusAlerts";
    public static final String TOOL_QUERY_METRIC = "queryMetric";

    /** 单次指标查询返回样本数上限（超出截断为 TopN + 总数说明，防止大结果集挤占上下文） */
    private static final int MAX_SAMPLES = 20;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${prometheus.base-url}")
    private String prometheusBaseUrl;

    @Value("${prometheus.timeout:10}")
    private int timeout;

    @Value("${prometheus.mock-enabled:false}")
    private boolean mockEnabled;

    /** 步数预算守卫：总量限流 + 同工具失败熔断，超限返回结构化收敛信号 */
    @Autowired
    private StepBudgetGuard stepBudgetGuard;

    private OkHttpClient httpClient;
    
    @jakarta.annotation.PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .readTimeout(Duration.ofSeconds(timeout))
                .build();
        logger.info("✅ QueryMetricsTools 初始化成功, Prometheus URL: {}, Mock模式: {}", prometheusBaseUrl, mockEnabled);
    }
    
    /**
     * 查询 Prometheus 活动告警
     * 该工具从 Prometheus 告警系统检索所有当前活动/触发的告警，包括标签、注释、状态和值
     */
    @Tool(description = "Query active alerts from Prometheus alerting system. " +
            "This tool retrieves all currently active/firing alerts including their labels, annotations, state, and values. " +
            "Use this tool when you need to check what alerts are currently firing, investigate alert conditions, or monitor alert status.")
    public String queryPrometheusAlerts() {
        if (!stepBudgetGuard.tryAcquire(TOOL_QUERY_PROMETHEUS_ALERTS)) {
            return stepBudgetGuard.blockedResponse(TOOL_QUERY_PROMETHEUS_ALERTS);
        }
        long t0 = System.currentTimeMillis();
        boolean ok = false;
        try {
        logger.info("开始查询 Prometheus 活动告警, Mock模式: {}", mockEnabled);

        try {
            List<SimplifiedAlert> simplifiedAlerts;

            if (mockEnabled) {
                // Mock 模式：返回与文档关联的模拟告警数据
                simplifiedAlerts = buildMockAlerts();
                logger.info("使用 Mock 数据，返回 {} 个模拟告警", simplifiedAlerts.size());
                ok = true;
            } else {
                // 真实模式：调用 Prometheus Alerts API
                PrometheusAlertsResult result = fetchPrometheusAlerts();

                if (!"success".equals(result.getStatus())) {
                    return buildErrorResponse("Prometheus API 返回非成功状态: " + result.getStatus(), result.getError());
                }

                // 转换为简化格式，对于相同的 alertname，只保留第一个
                Set<String> seenAlertNames = new HashSet<>();
                simplifiedAlerts = new ArrayList<>();

                for (PrometheusAlert alert : result.getData().getAlerts()) {
                    String alertName = alert.getLabels().get("alertname");

                    // 如果这个 alertname 已经存在，跳过
                    if (seenAlertNames.contains(alertName)) {
                        continue;
                    }

                    // 标记为已见过
                    seenAlertNames.add(alertName);

                    SimplifiedAlert simplified = new SimplifiedAlert();
                    simplified.setAlertName(alertName);
                    simplified.setDescription(alert.getAnnotations().getOrDefault("description", ""));
                    simplified.setState(alert.getState());
                    simplified.setActiveAt(alert.getActiveAt());
                    simplified.setDuration(calculateDuration(alert.getActiveAt()));

                    simplifiedAlerts.add(simplified);
                }
                ok = true;
            }

            // 构建成功响应
            PrometheusAlertsOutput output = new PrometheusAlertsOutput();
            output.setSuccess(true);
            output.setAlerts(simplifiedAlerts);
            output.setMessage(String.format("成功检索到 %d 个活动告警", simplifiedAlerts.size()));

            String jsonResult = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            logger.info("Prometheus 告警查询完成: 找到 {} 个告警", simplifiedAlerts.size());

            return jsonResult;

        } catch (Exception e) {
            logger.error("查询 Prometheus 告警失败", e);
            return buildErrorResponse("查询失败", e.getMessage());
        }
        } finally {
            stepBudgetGuard.recordResult(TOOL_QUERY_PROMETHEUS_ALERTS, ok);
        }
    }

    /**
     * 即时查询 Prometheus 指标（查"当前值"，如 CPU%、内存%、磁盘%）
     * <p>
     * 与 {@link #queryPrometheusAlerts()} 的区别：
     * - 告警查询看"是否触发阈值"（依赖 alerting rule，无规则则查不到）
     * - 即时查询看"当前具体数值"（直接读 PromQL，无需任何规则）
     * <p>
     * Agent 问"服务器 CPU 多少 / 内存多少 / 磁盘多少"时用这个工具。
     */
    @Tool(description = "Query current/instant metric value from Prometheus using PromQL. " +
            "Use this to check CURRENT server resource usage like CPU%, memory%, disk%, load. " +
            "Common PromQL examples: " +
            "1) CPU使用率(百分比): 100 - (avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100); " +
            "2) 内存使用率(百分比): (1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100; " +
            "3) 根分区磁盘使用率(百分比): (1 - (node_filesystem_avail_bytes{mountpoint=\"/\"} / node_filesystem_size_bytes{mountpoint=\"/\"})) * 100; " +
            "4) 系统1分钟负载: node_load1; " +
            "5) 可用内存(字节): node_memory_MemAvailable_bytes. " +
            "promql (required): the PromQL expression to evaluate.")
    public String queryMetric(
            @ToolParam(description = "PromQL 查询表达式，例如查CPU使用率: 100 - (avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100)") String promql) {
        if (!stepBudgetGuard.tryAcquire(TOOL_QUERY_METRIC)) {
            return stepBudgetGuard.blockedResponse(TOOL_QUERY_METRIC);
        }
        boolean ok = false;
        try {
            logger.info("查询 Prometheus 即时指标, PromQL: {}, Mock模式: {}", promql, mockEnabled);

            List<MetricSample> samples;
            if (mockEnabled) {
                samples = buildMockMetric(promql);
                logger.info("使用 Mock 指标数据，返回 {} 个样本", samples.size());
            } else {
                samples = fetchInstantQuery(promql);
                logger.info("Prometheus 即时查询完成: PromQL={} 返回 {} 个样本", promql, samples.size());
            }
            ok = true;

            // 样本数上限截断（字段裁剪/结果裁剪）：大结果集只保留前 MAX_SAMPLES 个样本 + 总数说明，
            // 避免按 instance/mountpoint 分组的大 PromQL 结果挤占模型上下文
            int totalSamples = samples.size();
            boolean truncated = totalSamples > MAX_SAMPLES;
            if (truncated) {
                samples = new ArrayList<>(samples.subList(0, MAX_SAMPLES));
            }

            MetricQueryOutput output = new MetricQueryOutput();
            output.setSuccess(true);
            output.setPromql(promql);
            output.setSamples(samples);
            output.setCount(samples.size());
            output.setTotalSamples(totalSamples);
            output.setMessage(samples.isEmpty()
                    ? "查询成功但无数据（请检查 PromQL 表达式或指标名是否正确）"
                    : truncated
                        ? String.format("查询到 %d 个样本，超出单次返回上限仅保留前 %d 个（如需其余实例请缩小 PromQL 范围）",
                                totalSamples, MAX_SAMPLES)
                        : String.format("成功查询到 %d 个指标样本", samples.size()));

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);

        } catch (Exception e) {
            logger.error("查询 Prometheus 即时指标失败", e);
            try {
                MetricQueryOutput errOutput = new MetricQueryOutput();
                errOutput.setSuccess(false);
                errOutput.setPromql(promql);
                errOutput.setMessage("即时指标查询失败: " + e.getMessage());
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(errOutput);
            } catch (Exception ex) {
                return String.format("{\"success\":false,\"promql\":\"%s\",\"message\":\"%s\"}",
                        promql, e.getMessage());
            }
        } finally {
            stepBudgetGuard.recordResult(TOOL_QUERY_METRIC, ok);
        }
    }

    /**
     * 调用 Prometheus /api/v1/query 即时查询接口
     */
    private List<MetricSample> fetchInstantQuery(String promql) throws Exception {
        String apiUrl = prometheusBaseUrl + "/api/v1/query?query=" +
                URLEncoder.encode(promql, StandardCharsets.UTF_8);
        logger.debug("请求 Prometheus 即时查询: {}", apiUrl);

        Request request = new Request.Builder().url(apiUrl).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP 请求失败: " + response.code());
            }
            String body = response.body().string();
            return parseInstantQuery(body);
        }
    }

    /**
     * 解析 Prometheus /api/v1/query 返回结果
     * 结构: {status, data:{resultType, result:[{metric:{labels}, value:[timestamp, "数值"]}]}}
     */
    private List<MetricSample> parseInstantQuery(String body) throws Exception {
        List<MetricSample> samples = new ArrayList<>();
        JsonNode root = objectMapper.readTree(body);

        if (!"success".equals(root.path("status").asText())) {
            String err = root.path("error").asText("");
            String errType = root.path("errorType").asText("");
            throw new RuntimeException("Prometheus 返回失败: " + err + " (" + errType + ")");
        }

        JsonNode resultArr = root.path("data").path("result");
        for (JsonNode item : resultArr) {
            MetricSample sample = new MetricSample();

            // 解析 labels（如 instance / job / mountpoint 等）
            Map<String, String> labels = new LinkedHashMap<>();
            JsonNode metricNode = item.path("metric");
            metricNode.fields().forEachRemaining(f -> labels.put(f.getKey(), f.getValue().asText()));
            sample.setLabels(labels);

            // 解析 value: [timestamp, "数值字符串"]
            JsonNode valueNode = item.path("value");
            if (valueNode.isArray() && valueNode.size() >= 2) {
                sample.setTimestamp(valueNode.get(0).asDouble());
                sample.setValue(valueNode.get(1).asText());
            }

            samples.add(sample);
        }
        return samples;
    }

    /**
     * Mock 即时指标数据（离线兜底，mock-enabled=true 时启用）
     */
    private List<MetricSample> buildMockMetric(String promql) {
        List<MetricSample> samples = new ArrayList<>();
        MetricSample s = new MetricSample();
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("instance", "mock-server");
        labels.put("job", "node");
        s.setLabels(labels);
        s.setTimestamp(Instant.now().getEpochSecond());
        s.setValue("42.0");
        samples.add(s);
        return samples;
    }
    
    /**
     * 构建 Mock 告警数据
     * 与 aiops-docs 文档中的告警类型对应：
     * - HighCPUUsage: CPU使用率过高
     * - HighMemoryUsage: 内存使用率过高
     * - HighDiskUsage: 磁盘使用率过高
     * - ServiceUnavailable: 服务不可用
     * - SlowResponse: 响应时间过长
     */
    private List<SimplifiedAlert> buildMockAlerts() {
        List<SimplifiedAlert> alerts = new ArrayList<>();
        Instant now = Instant.now();
        
        // 告警1: CPU使用率过高 - 持续约25分钟
        SimplifiedAlert cpuAlert = new SimplifiedAlert();
        cpuAlert.setAlertName("HighCPUUsage");
        cpuAlert.setDescription("服务 payment-service 的 CPU 使用率持续超过 80%，当前值为 92%。" +
                "实例: pod-payment-service-7d8f9c6b5-x2k4m，命名空间: production");
        cpuAlert.setState("firing");
        Instant cpuActiveAt = now.minus(25, ChronoUnit.MINUTES);
        cpuAlert.setActiveAt(cpuActiveAt.toString());
        cpuAlert.setDuration(calculateDuration(cpuActiveAt.toString()));
        alerts.add(cpuAlert);
        
        // 告警2: 内存使用率过高 - 持续约15分钟
        SimplifiedAlert memoryAlert = new SimplifiedAlert();
        memoryAlert.setAlertName("HighMemoryUsage");
        memoryAlert.setDescription("服务 order-service 的内存使用率持续超过 85%，当前值为 91%。" +
                "JVM堆内存使用: 3.8GB/4GB，可能存在内存泄漏风险。" +
                "实例: pod-order-service-5c7d8e9f1-m3n2p，命名空间: production");
        memoryAlert.setState("firing");
        Instant memoryActiveAt = now.minus(15, ChronoUnit.MINUTES);
        memoryAlert.setActiveAt(memoryActiveAt.toString());
        memoryAlert.setDuration(calculateDuration(memoryActiveAt.toString()));
        alerts.add(memoryAlert);
        
        // 告警3: 响应时间过长 - 持续约10分钟
        SimplifiedAlert slowAlert = new SimplifiedAlert();
        slowAlert.setAlertName("SlowResponse");
        slowAlert.setDescription("服务 user-service 的 P99 响应时间持续超过 3 秒，当前值为 4.2 秒。" +
                "受影响接口: /api/v1/users/profile, /api/v1/users/orders。" +
                "可能原因：数据库慢查询或下游服务延迟");
        slowAlert.setState("firing");
        Instant slowActiveAt = now.minus(10, ChronoUnit.MINUTES);
        slowAlert.setActiveAt(slowActiveAt.toString());
        slowAlert.setDuration(calculateDuration(slowActiveAt.toString()));
        alerts.add(slowAlert);
        
        return alerts;
    }
    
    /**
     * 从 Prometheus API 获取告警数据
     */
    private PrometheusAlertsResult fetchPrometheusAlerts() throws Exception {
        String apiUrl = prometheusBaseUrl + "/api/v1/alerts";
        logger.debug("请求 Prometheus API: {}", apiUrl);
        
        Request request = new Request.Builder()
                .url(apiUrl)
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP 请求失败: " + response.code());
            }
            
            String responseBody = response.body().string();
            return objectMapper.readValue(responseBody, PrometheusAlertsResult.class);
        }
    }
    
    /**
     * 计算从 activeAt 到现在的持续时间
     */
    private String calculateDuration(String activeAtStr) {
        try {
            Instant activeAt = Instant.parse(activeAtStr);
            Duration duration = Duration.between(activeAt, Instant.now());
            
            long hours = duration.toHours();
            long minutes = duration.toMinutes() % 60;
            long seconds = duration.getSeconds() % 60;
            
            if (hours > 0) {
                return String.format("%dh%dm%ds", hours, minutes, seconds);
            } else if (minutes > 0) {
                return String.format("%dm%ds", minutes, seconds);
            } else {
                return String.format("%ds", seconds);
            }
        } catch (Exception e) {
            logger.warn("解析时间失败: {}", activeAtStr, e);
            return "unknown";
        }
    }
    
    /**
     * 构建错误响应
     */
    private String buildErrorResponse(String message, String error) {
        try {
            PrometheusAlertsOutput output = new PrometheusAlertsOutput();
            output.setSuccess(false);
            output.setMessage(message);
            output.setError(error);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            return String.format("{\"success\":false,\"message\":\"%s\",\"error\":\"%s\"}", message, error);
        }
    }
    
    // ==================== 数据模型 ====================
    
    /**
     * Prometheus 告警信息结构
     */
    @Data
    public static class PrometheusAlert {
        private Map<String, String> labels;
        private Map<String, String> annotations;
        private String state;
        private String activeAt;
        private String value;
    }
    
    /**
     * Prometheus 告警查询结果
     */
    @Data
    public static class PrometheusAlertsResult {
        private String status;
        private AlertsData data;
        private String error;
        private String errorType;
    }
    
    @Data
    public static class AlertsData {
        private List<PrometheusAlert> alerts = new ArrayList<>();
    }
    
    /**
     * 简化的告警信息
     */
    @Data
    public static class SimplifiedAlert {
        @JsonProperty("alert_name")
        private String alertName;
        
        @JsonProperty("description")
        private String description;
        
        @JsonProperty("state")
        private String state;
        
        @JsonProperty("active_at")
        private String activeAt;
        
        @JsonProperty("duration")
        private String duration;
    }
    
    /**
     * 告警查询输出
     */
    @Data
    public static class PrometheusAlertsOutput {
        @JsonProperty("success")
        private boolean success;
        
        @JsonProperty("alerts")
        private List<SimplifiedAlert> alerts;
        
        @JsonProperty("message")
        private String message;
        
        @JsonProperty("error")
        private String error;
    }

    /**
     * 即时指标样本（一个 PromQL 查询可能返回多个，例如按 mountpoint 分组的磁盘）
     */
    @Data
    public static class MetricSample {
        @JsonProperty("labels")
        private Map<String, String> labels;

        @JsonProperty("timestamp")
        private double timestamp;

        @JsonProperty("value")
        private String value;
    }

    /**
     * 即时指标查询输出
     */
    @Data
    public static class MetricQueryOutput {
        @JsonProperty("success")
        private boolean success;

        @JsonProperty("promql")
        private String promql;

        @JsonProperty("samples")
        private List<MetricSample> samples;

        @JsonProperty("count")
        private int count;

        /** 原始样本总数（截断前），与 count 差值即被裁剪部分 */
        @JsonProperty("total_samples")
        private Integer totalSamples;

        @JsonProperty("message")
        private String message;
    }
}
