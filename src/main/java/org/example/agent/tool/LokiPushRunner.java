package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动时往 Loki 灌入演示日志，保证 Agent 首次查询就能真实查到数据（而非空结果）。
 *
 * 灌入的日志与告警/知识库相互呼应，构成演示闭环：
 *   HighCPUUsage 告警    → payment-service CPU 92% 日志
 *   HighMemoryUsage 告警 → order-service OOM 日志
 *   SlowResponse 告警    → mysql 慢查询日志
 *   ServiceUnavailable   → order-service Pod OOMKilled 事件
 *
 * 仅在真实模式（loki.mock-enabled=false）注册；Mock 模式下日志由 LokiLogsTools 本地生成，无需灌数据。
 * Loki 不可达时只打 warn 不阻断应用启动。
 */
@Component
@ConditionalOnProperty(prefix = "loki", name = "mock-enabled", havingValue = "false", matchIfMissing = true)
public class LokiPushRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(LokiPushRunner.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${loki.base-url:http://localhost:3100}")
    private String lokiBaseUrl;

    @Value("${loki.timeout:10}")
    private int timeout;

    private OkHttpClient httpClient;

    @Override
    public void run(String... args) {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .readTimeout(Duration.ofSeconds(timeout))
                .build();

        if (!waitForLoki()) {
            logger.warn("⚠️ Loki 不可达（{}），跳过灌数据。Agent 查日志将返回空，请先 docker compose -f loki.yml up -d", lokiBaseUrl);
            return;
        }
        try {
            pushLogs();
            logger.info("✅ LokiPushRunner: 演示日志灌入完成");
        } catch (Exception e) {
            logger.warn("⚠️ 灌入 Loki 日志失败: {}", e.getMessage());
        }
    }

    /** 轮询 /ready，最多等 60 秒 */
    private boolean waitForLoki() {
        String readyUrl = lokiBaseUrl + "/ready";
        for (int i = 0; i < 30; i++) {
            try {
                Request request = new Request.Builder().url(readyUrl).get().build();
                try (Response response = httpClient.newCall(request).execute()) {
                    String body = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful() && body.contains("ready")) {
                        logger.info("Loki 就绪（{} 轮询后）", i + 1);
                        return true;
                    }
                }
            } catch (Exception e) {
                // 连不上，继续重试
            }
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void pushLogs() throws Exception {
        long base = Instant.now().toEpochMilli();
        // 4 条与告警呼应的演示日志
        List<Map<String, Object>> streams = List.of(
                buildStream("application-logs", "payment-service", "WARN",
                        ns(base - 5 * 60_000L),
                        line("WARN", "payment-service", "pod-payment-service-7d8f9c6b5-x2k4m",
                                "CPU使用率过高: 92.0%, 进程: java (PID:1), 线程数: 245",
                                Map.of("cpu_usage", "92.0", "cpu_cores", "4", "load_average_1m", "3.82"))),
                buildStream("application-logs", "order-service", "FATAL",
                        ns(base - 12 * 60_000L),
                        line("FATAL", "order-service", "pod-order-service-5c7d8e9f1-m3n2p",
                                "java.lang.OutOfMemoryError: Java heap space at com.example.order.service.OrderService.processLargeOrder(OrderService.java:156)",
                                Map.of("heap_used", "3.9GB", "heap_max", "4GB", "gc_count", "128"))),
                buildStream("database-slow-query", "mysql", "WARN",
                        ns(base - 3 * 60_000L),
                        line("WARN", "mysql", "mysql-primary-01",
                                "慢查询: SELECT * FROM orders WHERE user_id = ? AND status IN (?, ?, ?) ORDER BY created_at DESC LIMIT 100, 执行时间: 3.2s, 扫描行数: 1245678",
                                Map.of("query_time_sec", "3.2", "rows_examined", "1245678", "rows_returned", "100"))),
                buildStream("system-events", "kubernetes", "WARN",
                        ns(base - 15 * 60_000L),
                        line("WARN", "kubernetes", "kube-controller-manager",
                                "Pod 重启事件: pod-order-service-5c7d8e9f1-m3n2p, 原因: OOMKilled, 容器退出码: 137, 重启次数: 3",
                                Map.of("event_type", "PodRestart", "reason", "OOMKilled", "exit_code", "137", "restart_count", "3")))
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("streams", streams);
        String body = objectMapper.writeValueAsString(payload);

        Request request = new Request.Builder()
                .url(lokiBaseUrl + "/loki/api/v1/push")
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "";
                throw new RuntimeException("Loki push HTTP " + response.code() + ": " + errBody);
            }
        }

        // 验证：查 labels 非空
        Thread.sleep(1000L);
        Request verify = new Request.Builder().url(lokiBaseUrl + "/loki/api/v1/labels").get().build();
        try (Response response = httpClient.newCall(verify).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            logger.info("Loki labels 验证: {}", resp);
        }
    }

    /** 构造一条 stream：stream labels + 单条 value[ns, lineJson] */
    private Map<String, Object> buildStream(String job, String service, String level, String nsValue, String lineJson) {
        Map<String, Object> stream = new LinkedHashMap<>();
        stream.put("stream", Map.of("job", job, "service", service, "level", level));
        stream.put("values", List.of(List.of(nsValue, lineJson)));
        return stream;
    }

    /** line 内容（JSON 对象，LokiLogsTools 解析时会 readTree） */
    private String line(String level, String service, String instance, String message, Map<String, String> metrics) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", level);
            m.put("service", service);
            m.put("instance", instance);
            m.put("message", message);
            m.put("metrics", metrics);
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"message\":\"" + message + "\"}";
        }
    }

    /** 毫秒时间戳转 Loki 需要的纳秒字符串 */
    private static String ns(long epochMilli) {
        return String.valueOf(epochMilli * 1_000_000L);
    }
}
