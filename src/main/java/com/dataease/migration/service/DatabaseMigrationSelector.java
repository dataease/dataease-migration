package com.dataease.migration.service;

import com.dataease.migration.model.DatabaseInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class DatabaseMigrationSelector implements DatabaseMigrator {
    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationSelector.class);

    private final NativeMySqlDatabaseMigrator nativeMigrator;
    private final JdbcDatabaseMigrator jdbcMigrator;

    public DatabaseMigrationSelector(NativeMySqlDatabaseMigrator nativeMigrator, JdbcDatabaseMigrator jdbcMigrator) {
        this.nativeMigrator = nativeMigrator;
        this.jdbcMigrator = jdbcMigrator;
    }

    @Override
    public void migrate(DatabaseInfo source, DatabaseInfo target, MigrationJob job) throws Exception {
        if (nativeMigrator.isAvailable()) {
            nativeMigrator.migrate(source, target, job);
            return;
        }
        job.log("未发现当前环境对应的 MySQL 客户端工具，使用内置 JDBC 迁移。");
        jdbcMigrator.migrate(source, target, job);
    }

    @Bean
    ApplicationRunner logSelectedStrategy(MySqlToolResolver toolResolver) {
        return arguments -> {
            if (nativeMigrator.isAvailable()) {
                log.info("已选择本地 MySQL 客户端工具：{}", toolResolver.platform());
            } else {
                log.info("未发现 {} 的 MySQL 客户端工具，将使用内置 JDBC 迁移", toolResolver.platform());
            }
        };
    }
}
