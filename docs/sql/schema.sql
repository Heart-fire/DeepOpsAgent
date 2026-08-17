-- ============================================================
-- DeepOps Agent 会话分层存储表结构（chat-history.storage-enabled=true 时使用）
-- 代码侧由 ChatStorageConfig 启动时幂等建表（CREATE TABLE IF NOT EXISTS），
-- 本文件用于审阅/手工建库；两处结构保持一致。
-- ============================================================

-- 会话元信息：滚动摘要 + 计数（摘要即"窗口外历史"的压缩形态）
CREATE TABLE IF NOT EXISTS chat_session (
    session_id       VARCHAR(64)  NOT NULL COMMENT '会话ID',
    summary          TEXT         NULL     COMMENT '窗口外历史滚动摘要（LLM压缩）',
    summarized_pairs INT          NOT NULL DEFAULT 0 COMMENT '已压缩进摘要的消息对数',
    total_pairs      INT          NOT NULL DEFAULT 0 COMMENT '累计消息对数（审计口径）',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话元信息（摘要+计数）';

-- 会话消息全量持久化（审计）：MySQL 是 source of truth，只增不删（clear 除外）
CREATE TABLE IF NOT EXISTS chat_message (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键，天然插入顺序',
    session_id  VARCHAR(64)  NOT NULL COMMENT '会话ID',
    role        VARCHAR(16)  NOT NULL COMMENT 'user / assistant',
    content     TEXT         NOT NULL COMMENT '消息内容',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session (session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话消息全量持久化（审计）';

-- Redis 侧无表结构，key 设计：
--   chat:win:{sessionId} -> JSON 数组（最近 N 对消息窗口），TTL 24h
--   语义：活跃会话缓存；过期/丢失回源 MySQL 重建，最终一致
