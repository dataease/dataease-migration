package com.dataease.migration.service;

import com.dataease.migration.model.DatabaseInfo;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class TargetDatabaseUpgradeService {
    private static final Set<String> PERMISSION_TABLES = Set.of(
            "per_busi_resource", "per_org", "per_role", "per_auth_busi_user", "per_auth_busi_role",
            "per_auth_menu", "per_user_role", "per_user");
    private static final Set<String> CORE_TABLES = Set.of(
            "core_menu", "core_dataset_group", "core_dataset_table", "core_dataset_table_field",
            "core_datasource", "core_de_engine", "core_rsa", "core_chart_view",
            "data_visualization_info", "snapshot_data_visualization_info",
            "core_sys_setting", "xpack_plugin", "de_standalone_version");
    private static final Set<String> V3_PERMISSION_TABLES = Set.of(
            "per_busi_resource", "per_org", "per_role", "per_user_role", "per_user", "per_permission");
    private static final String UPGRADE_SCRIPT = "upgrade.sql";
    private static final String CROSS_DATASET_QUERY = """
            SELECT 1
            FROM core_dataset_group
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
            Set<String> tables = tables(connection);
            validateCore(tables);
            boolean enterprise = hasPermissions(tables);
            String script;
            try (var input = new ClassPathResource(UPGRADE_SCRIPT).getInputStream()) {
                script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException error) {
                throw new SQLException("V3升级脚本不可读取", error);
            }
            if (!enterprise) {
                int permissionBlock = script.indexOf("CREATE TABLE IF NOT EXISTS per_permission (");
                if (permissionBlock < 0) throw new SQLException("V3升级脚本权限分段不可识别");
                script = script.substring(0, permissionBlock);
                job.log("源库为社区版：执行已安装模块的核心升级，不创建企业权限。");
            }
            var optional = Pattern.compile("(?m)^ALTER TABLE (xpack_[a-z_]+) MODIFY COLUMN oid [^;]+;").matcher(script);
            StringBuffer supported = new StringBuffer();
            while (optional.find()) {
                boolean exists = tables.contains(optional.group(1));
                optional.appendReplacement(supported, java.util.regex.Matcher.quoteReplacement(exists ? optional.group() : ""));
                if (!exists) job.log("源库未安装可选模块，跳过其字段升级：" + optional.group(1));
            }
            optional.appendTail(supported);
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ByteArrayResource(supported.toString().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
            insertCrossDatasetSetting(connection, job);
            if (!enterprise) recordCommunityCoreUpgrade(connection);
        }
        job.log("目标端数据库升级 SQL 执行完成。");
    }

    private static void recordCommunityCoreUpgrade(Connection connection) throws SQLException {
        try (var query = connection.createStatement();
                var result = query.executeQuery("SELECT COUNT(*) FROM de_standalone_version WHERE version='2.40' AND success=1")) {
            result.next(); if (result.getLong(1) > 0) return;
        }
        int rank;
        try (var query = connection.createStatement();
                var result = query.executeQuery("SELECT COALESCE(MAX(installed_rank),0)+1 FROM de_standalone_version")) {
            result.next(); rank = result.getInt(1);
        }
        try (var insert = connection.prepareStatement("INSERT INTO de_standalone_version (installed_rank,version,description,type,script,checksum,installed_by,installed_on,execution_time,success) VALUES (?,'2.40','community core upgrade','SQL','operations-upgrade-community-core.sql',0,'operations',CURRENT_TIMESTAMP,0,1)")) {
            insert.setInt(1, rank); insert.executeUpdate();
        }
    }

    public void validateSourceAndTarget(DatabaseInfo source, DatabaseInfo target) throws SQLException {
        try (Connection a = DriverManager.getConnection(source.jdbcUrl(), source.username(), source.password());
                Connection b = DriverManager.getConnection(target.jdbcUrl(), target.username(), target.password())) {
            Set<String> sourceTables = tables(a);
            validateCore(sourceTables);
            Set<String> targetTables = tables(b);
            boolean targetEnterprise = !Collections.disjoint(targetTables, Set.of("per_org", "per_user", "per_role"));
            if (targetEnterprise) hasPermissions(targetTables, V3_PERMISSION_TABLES);
            if (hasPermissions(sourceTables) != targetEnterprise)
                throw new SQLException("源目标的社区版/企业版权限能力不同，首版不自动转换版本类型，请准备相同类型的V3目标");
        }
    }

    private static Set<String> tables(Connection connection) throws SQLException {
        Set<String> names = new HashSet<>();
        try (var query = connection.createStatement();
                var rows = query.executeQuery("SELECT TABLE_NAME FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'")) {
            while (rows.next()) names.add(rows.getString(1).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static void validateCore(Set<String> names) throws SQLException {
        Set<String> missing = new TreeSet<>(CORE_TABLES);
        missing.removeAll(names);
        if (!missing.isEmpty()) throw new SQLException("V2核心元数据表不完整，缺少：" + String.join("、", missing));
    }

    private static boolean hasPermissions(Set<String> names) throws SQLException {
        return hasPermissions(names, PERMISSION_TABLES);
    }

    private static boolean hasPermissions(Set<String> names, Set<String> required) throws SQLException {
        long present = required.stream().filter(names::contains).count();
        if (present > 0 && present < required.size()) {
            Set<String> missing = new TreeSet<>(required);
            missing.removeAll(names);
            throw new SQLException("企业版权限表不完整，缺少：" + String.join("、", missing));
        }
        return present != 0;
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
