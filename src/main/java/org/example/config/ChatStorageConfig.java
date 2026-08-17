package org.example.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 会话分层存储装配（chat-history.storage-enabled=true 时生效）。
 *
 * 为什么手动装配而不是依赖 Spring Boot 自动配置：
 * 项目默认形态是"零外部存储依赖"的演示（storage-enabled=false 时不得因缺 DataSource/Redis 起不来），
 * 因此主类排除了 DataSource/Redis 自动配置，这里按开关手动装配 HikariDataSource + JdbcTemplate +
 * Lettuce Redis。启动时幂等建表（CREATE TABLE IF NOT EXISTS），MySQL 表结构由代码保证，
 * 与 Milvus collection 由 MilvusClientFactory 自动创建的口径一致。
 */
@Configuration
@EnableTransactionManagement
@ConditionalOnProperty(prefix = "chat-history", name = "storage-enabled", havingValue = "true")
public class ChatStorageConfig {

    // ==================== MySQL ====================

    @Bean
    @ConfigurationProperties("chat-history.datasource")
    public HikariDataSource chatDataSource() {
        return new HikariDataSource();
    }

    @Bean
    public JdbcTemplate chatJdbcTemplate(HikariDataSource chatDataSource) {
        return new JdbcTemplate(chatDataSource);
    }

    @Bean
    public TransactionManager chatTransactionManager(HikariDataSource chatDataSource) {
        return new DataSourceTransactionManager(chatDataSource);
    }

    /** 启动时幂等建表（见 docs/sql/schema.sql，两处结构保持一致） */
    @Bean
    public String chatSchemaInitializer(JdbcTemplate chatJdbcTemplate) {
        chatJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS chat_session (
                    session_id       VARCHAR(64)  NOT NULL COMMENT '会话ID',
                    summary          TEXT         NULL     COMMENT '窗口外历史滚动摘要（LLM压缩）',
                    summarized_pairs INT          NOT NULL DEFAULT 0 COMMENT '已压缩进摘要的消息对数',
                    total_pairs      INT          NOT NULL DEFAULT 0 COMMENT '累计消息对数（审计口径）',
                    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (session_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话元信息（摘要+计数）'
                """);
        chatJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS chat_message (
                    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键，天然插入顺序',
                    session_id  VARCHAR(64)  NOT NULL COMMENT '会话ID',
                    role        VARCHAR(16)  NOT NULL COMMENT 'user / assistant',
                    content     TEXT         NOT NULL COMMENT '消息内容',
                    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    KEY idx_session (session_id, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话消息全量持久化（审计）'
                """);
        return "chat-schema-initialized";
    }

    // ==================== Redis ====================

    @Bean
    @ConfigurationProperties("chat-history.redis")
    public RedisStandaloneConfiguration chatRedisConfiguration() {
        return new RedisStandaloneConfiguration();
    }

    @Bean
    public LettuceConnectionFactory chatRedisConnectionFactory(RedisStandaloneConfiguration chatRedisConfiguration) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(chatRedisConfiguration);
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory chatRedisConnectionFactory) {
        return new StringRedisTemplate(chatRedisConnectionFactory);
    }
}
