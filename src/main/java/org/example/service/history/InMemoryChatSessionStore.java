package org.example.service.history;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存版会话存储（chat-history.storage-enabled=false 时的默认实现）。
 * 行为对齐原 ChatController.SessionInfo：最近 N 对消息窗口裁剪、线程安全、重启即失。
 */
@Component
@ConditionalOnProperty(prefix = "chat-history", name = "storage-enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryChatSessionStore implements ChatSessionStore {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryChatSessionStore.class);

    @Value("${chat-history.recent-pairs:6}")
    private int recentPairs;

    private final Map<String, InMemorySession> sessions = new ConcurrentHashMap<>();

    @Override
    public void appendMessage(String sessionId, String userQuestion, String aiAnswer) {
        InMemorySession s = sessions.computeIfAbsent(sessionId, InMemorySession::new);
        s.addPair(userQuestion, aiAnswer, recentPairs);
    }

    @Override
    public List<Map<String, String>> getRecentHistory(String sessionId) {
        InMemorySession s = sessions.get(sessionId);
        return s == null ? List.of() : s.getHistory();
    }

    @Override
    public String getSummary(String sessionId) {
        return "";   // 内存版不做摘要（无超窗历史保留）
    }

    @Override
    public void clear(String sessionId) {
        InMemorySession s = sessions.remove(sessionId);
        if (s != null) {
            logger.info("内存会话已清空: {}", sessionId);
        }
    }

    @Override
    public int getMessagePairCount(String sessionId) {
        InMemorySession s = sessions.get(sessionId);
        return s == null ? 0 : s.pairCount();
    }

    @Override
    public long getCreateTime(String sessionId) {
        InMemorySession s = sessions.get(sessionId);
        return s == null ? 0 : s.createTime;
    }

    private static class InMemorySession {
        final long createTime = System.currentTimeMillis();
        final List<Map<String, String>> history = new ArrayList<>();
        final ReentrantLock lock = new ReentrantLock();

        void addPair(String q, String a, int maxPairs) {
            lock.lock();
            try {
                history.add(msg("user", q));
                history.add(msg("assistant", a));
                while (history.size() > maxPairs * 2) {
                    history.remove(0);
                    if (!history.isEmpty()) {
                        history.remove(0);
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        List<Map<String, String>> getHistory() {
            lock.lock();
            try {
                return new ArrayList<>(history);
            } finally {
                lock.unlock();
            }
        }

        int pairCount() {
            lock.lock();
            try {
                return history.size() / 2;
            } finally {
                lock.unlock();
            }
        }

        private static Map<String, String> msg(String role, String content) {
            Map<String, String> m = new HashMap<>();
            m.put("role", role);
            m.put("content", content);
            return m;
        }
    }
}
