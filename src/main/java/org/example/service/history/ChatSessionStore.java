package org.example.service.history;

import java.util.List;
import java.util.Map;

/**
 * 会话历史存储抽象（存储层解耦，条件装配切换实现）。
 *
 * 两个实现：
 * - {@link InMemoryChatSessionStore}：内存版（chat-history.storage-enabled=false，默认），
 *   零外部依赖、重启即失，保留原有演示能力；仅做最近 N 轮窗口裁剪，无摘要。
 * - {@link RedisMysqlChatSessionStore}：Redis + MySQL 分层版（storage-enabled=true），
 *   MySQL 为 source of truth（全量消息持久化审计），Redis 缓存活跃窗口（低延迟读取，回源兜底）；
 *   超出窗口的历史经 LLM 压缩为摘要，注入 system prompt。
 *
 * 统一语义："历史摘要 + 最近 N 轮完整保留"对上层（ChatService.buildSystemPrompt）透明。
 */
public interface ChatSessionStore {

    /** 追加一对消息（用户问题 + AI 回复）；持久化实现内部触发超出窗口部分的摘要压缩 */
    void appendMessage(String sessionId, String userQuestion, String aiAnswer);

    /** 最近 N 轮完整历史（N 由实现按配置决定），按时间升序 */
    List<Map<String, String>> getRecentHistory(String sessionId);

    /** 历史摘要（窗口外历史压缩结果；无摘要返回空串） */
    String getSummary(String sessionId);

    /** 清空会话（持久化实现同时清 DB 与缓存） */
    void clear(String sessionId);

    /** 当前消息对数（供会话信息接口） */
    int getMessagePairCount(String sessionId);

    /** 会话创建时间戳 ms（拿不到返回 0） */
    long getCreateTime(String sessionId);
}
