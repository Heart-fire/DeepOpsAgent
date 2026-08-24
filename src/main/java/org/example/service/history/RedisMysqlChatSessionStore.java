package org.example.service.history;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis + MySQL 分层会话存储（chat-history.storage-enabled=true 时启用）。
 *
 * 分层语义：
 * - MySQL（source of truth）：chat_session（含滚动摘要）+ chat_message（全量消息，审计不删）。
 * - Redis（活跃会话缓存）：仅缓存"最近 N 对"窗口（JSON），TTL 24h；过期/丢失自动回源 MySQL，最终一致。
 *
 * 写路径（Cache-Aside 变体，先持久化后缓存）：
 *   INSERT 消息 → UPSERT 会话 → 超窗消息滚动摘要（滞后 2 对触发，避免每轮 LLM 调用）→ 刷新 Redis 窗口。
 * 读路径：Redis hit 直接返回；miss 查 MySQL 回填。
 *
 * 一致性口径：
 *   MySQL 写成功才算成功；Redis 刷新失败只告警不回滚（缓存可重建，容忍短暂陈旧）。
 *   Redis 全丢 = 多几次 MySQL 回源，语义无损。
 */
@Component
@ConditionalOnProperty(prefix = "chat-history", name = "storage-enabled", havingValue = "true")
public class RedisMysqlChatSessionStore implements ChatSessionStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisMysqlChatSessionStore.class);

    private static final String KEY_WINDOW = "chat:win:%s";   // 最近 N 对消息窗口
    private static final Duration WINDOW_TTL = Duration.ofHours(24);

    /** 摘要触发滞后：超窗消息数达到 recentPairs + hysteresis 才压缩一批（摊薄 LLM 调用成本） */
    private static final int SUMMARIZE_HYSTERESIS = 2;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private HistorySummarizer summarizer;

    @Value("${chat-history.recent-pairs:6}")
    private int recentPairs;

    // ==================== 写路径 ====================

    @Override
    public void appendMessage(String sessionId, String userQuestion, String aiAnswer) {
        // 1. MySQL 持久化（source of truth，成功才继续）
        jdbcTemplate.update(
                "INSERT INTO chat_message(session_id, role, content) VALUES (?, ?, ?)",
                sessionId, "user", userQuestion);
        jdbcTemplate.update(
                "INSERT INTO chat_message(session_id, role, content) VALUES (?, ?, ?)",
                sessionId, "assistant", aiAnswer);
        jdbcTemplate.update(
                "INSERT INTO chat_session(session_id, summary, summarized_pairs, total_pairs) " +
                        "VALUES (?, '', 0, 1) " +
                        "ON DUPLICATE KEY UPDATE total_pairs = total_pairs + 1, update_time = CURRENT_TIMESTAMP",
                sessionId);

        int totalPairs = totalPairs(sessionId);
        int summarizedPairs = summarizedPairs(sessionId);

        // 2. 滚动摘要：超窗消息攒够一批（滞后）才压缩，控制摘要调用频率
        if (totalPairs - summarizedPairs >= recentPairs + SUMMARIZE_HYSTERESIS && summarizer != null) {
            int overflowPairs = totalPairs - recentPairs - summarizedPairs;
            List<Map<String, String>> overflow = queryMessages(sessionId, summarizedPairs * 2, overflowPairs * 2);
            String newSummary = summarizer.summarize(summary(sessionId), overflow);
            jdbcTemplate.update(
                    "UPDATE chat_session SET summary = ?, summarized_pairs = ? WHERE session_id = ?",
                    newSummary, totalPairs - recentPairs, sessionId);
            logger.info("会话 {} 滚动摘要完成，累计压缩 {} 对", sessionId, totalPairs - recentPairs);
        }

        // 3. 刷新 Redis 活跃窗口（失败不回滚：缓存可回源重建）
        refreshWindowCache(sessionId);
    }

    // ==================== 读路径 ====================

    @Override
    public List<Map<String, String>> getRecentHistory(String sessionId) {
        String key = String.format(KEY_WINDOW, sessionId);
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<Map<String, String>>>() {});
            }
        } catch (Exception e) {
            logger.warn("Redis 读取失败，回源 MySQL: {}", e.getMessage());
        }
        // miss / Redis 故障 → MySQL 回源并回填
        List<Map<String, String>> fromDb = queryRecentWindow(sessionId);
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(fromDb), WINDOW_TTL);
        } catch (Exception e) {
            logger.warn("Redis 回填失败（仅告警，不影响读）: {}", e.getMessage());
        }
        return fromDb;
    }

    @Override
    public String getSummary(String sessionId) {
        return summary(sessionId);
    }

    @Override
    public void clear(String sessionId) {
        jdbcTemplate.update("DELETE FROM chat_message WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM chat_session WHERE session_id = ?", sessionId);
        try {
            redisTemplate.delete(String.format(KEY_WINDOW, sessionId));
        } catch (Exception e) {
            logger.warn("Redis 删除失败: {}", e.getMessage());
        }
    }

    @Override
    public int getMessagePairCount(String sessionId) {
        Integer total = jdbcTemplate.queryForObject(
                "SELECT total_pairs FROM chat_session WHERE session_id = ?", Integer.class, sessionId);
        return total == null ? 0 : total;
    }

    @Override
    public long getCreateTime(String sessionId) {
        java.sql.Timestamp ts = jdbcTemplate.queryForObject(
                "SELECT create_time FROM chat_session WHERE session_id = ?", java.sql.Timestamp.class, sessionId);
        return ts == null ? 0 : ts.getTime();
    }

    // ==================== 内部方法 ====================

    /** 按全局插入顺序取 [offset, offset+limit) 条消息（id 自增即顺序，无需 pair_index） */
    private List<Map<String, String>> queryMessages(String sessionId, int offset, int limit) {
        return jdbcTemplate.query(
                "SELECT role, content FROM chat_message WHERE session_id = ? ORDER BY id ASC LIMIT ? OFFSET ?",
                (rs, i) -> {
                    Map<String, String> m = new HashMap<>();
                    m.put("role", rs.getString("role"));
                    m.put("content", rs.getString("content"));
                    return m;
                }, sessionId, limit, offset);
    }

    private List<Map<String, String>> queryRecentWindow(String sessionId) {
        int totalPairs = totalPairs(sessionId);
        if (totalPairs == 0) {
            return new ArrayList<>();
        }
        // 最近 N 对 = 全量里最后 2N 条
        int skip = Math.max(0, (totalPairs - recentPairs)) * 2;
        int limit = Math.min(totalPairs, recentPairs) * 2;
        return queryMessages(sessionId, skip, limit);
    }

    private void refreshWindowCache(String sessionId) {
        try {
            List<Map<String, String>> window = queryRecentWindow(sessionId);
            redisTemplate.opsForValue().set(
                    String.format(KEY_WINDOW, sessionId),
                    objectMapper.writeValueAsString(window),
                    WINDOW_TTL);
        } catch (Exception e) {
            logger.warn("Redis 窗口刷新失败（缓存可回源重建，不回滚 MySQL）: {}", e.getMessage());
        }
    }

    private int totalPairs(String sessionId) {
        Integer total = jdbcTemplate.queryForObject(
                "SELECT total_pairs FROM chat_session WHERE session_id = ?", Integer.class, sessionId);
        return total == null ? 0 : total;
    }

    private int summarizedPairs(String sessionId) {
        Integer summarized = jdbcTemplate.queryForObject(
                "SELECT summarized_pairs FROM chat_session WHERE session_id = ?", Integer.class, sessionId);
        return summarized == null ? 0 : summarized;
    }

    private String summary(String sessionId) {
        List<String> s = jdbcTemplate.query(
                "SELECT summary FROM chat_session WHERE session_id = ?",
                (rs, i) -> rs.getString("summary"), sessionId);
        return s.isEmpty() || s.get(0) == null ? "" : s.get(0);
    }
}
