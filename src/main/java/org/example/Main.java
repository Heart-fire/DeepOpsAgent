package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * 默认形态（chat-history.storage-enabled=false）不依赖 MySQL/Redis：
 * 排除 DataSource/Redis 自动配置避免"无连接信息即启动失败"；
 * 开启分层存储时由 {@link org.example.config.ChatStorageConfig} 手动装配
 * HikariDataSource + JdbcTemplate + Lettuce Redis（幂等建表）。
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
})
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
