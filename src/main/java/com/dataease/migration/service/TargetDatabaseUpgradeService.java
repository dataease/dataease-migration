package com.dataease.migration.service;

import com.dataease.migration.model.DatabaseInfo;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@Service
public class TargetDatabaseUpgradeService {
    private static final String UPGRADE_SCRIPT = "upgrade.sql";
    private static final String CROSS_DATASET_QUERY = """
            SELECT 1
            FROM dataeasev3jinlong.core_dataset_group
            WHERE is_cross IS TRUE
            LIMIT 1
            """;
    private static final String INSERT_CROSS_DATASET_SETTING = """
            INSERT INTO core_sys_setting (id, pkey, pval, type, sort)
            VALUES (1048232869488627721, 'basic.disableCrossDs', ?, 'text', '15')
            """;

    public void execute(DatabaseInfo target, MigrationJob job) throws SQLException {
        job.log("开始在目标端数据库执行升级 SQL。");
        try (Connection connection = DriverManager.getConnection(target.jdbcUrl(), target.username(), target.password())) {
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ClassPathResource(UPGRADE_SCRIPT), StandardCharsets.UTF_8));
            insertCrossDatasetSetting(connection, job);
        }
        job.log("目标端数据库升级 SQL 执行完成。");
    }

    private void insertCrossDatasetSetting(Connection connection, MigrationJob job) throws SQLException {
        boolean hasCrossDataset;
        try (PreparedStatement query = connection.prepareStatement(CROSS_DATASET_QUERY);
             ResultSet result = query.executeQuery()) {
            hasCrossDataset = result.next();
        }

        try (PreparedStatement insert = connection.prepareStatement(INSERT_CROSS_DATASET_SETTING)) {
            insert.setString(1, Boolean.toString(!hasCrossDataset));
            insert.executeUpdate();
        }
        job.log("已根据跨数据集分组配置 basic.disableCrossDs：" + !hasCrossDataset + "。");
    }
}
