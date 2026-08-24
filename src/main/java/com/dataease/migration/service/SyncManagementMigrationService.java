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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * V2 到 V3 的同步管理专项迁移入口。
 *
 * <p>通用 upgrade.sql 只负责公共表结构。本服务单独识别同步模块是否存在，补充 JPA 实体需要的
 * datasource_role，转换任务 JSON 和运行状态，并安装 PostgreSQL 源/目标插件。专项逻辑与通用升级隔离，
 * 可避免没有同步管理数据的环境受到影响。</p>
 */
@Service
public class SyncManagementMigrationService {
    private static final String SYNC_UPGRADE_SCRIPT = "sync-upgrade.sql";
    /**
     * V3 会把 parameter 反序列化为包含 source.datasource 和 target.datasource 的任务对象。
     * 使用 CASE 保证非法 JSON 不会继续进入 JSON_EXTRACT，兼容历史 longtext/json 两种列类型。
     */
    private static final String INVALID_TASK_PARAMETER_QUERY = """
            SELECT id,
                   `_name`,
                   CASE
                       WHEN parameter IS NULL THEN 'parameter 为空'
                       WHEN JSON_VALID(parameter) = 0 THEN 'parameter 不是合法 JSON'
                       WHEN COALESCE(JSON_TYPE(JSON_EXTRACT(parameter, '$.source.datasource')), '') <> 'OBJECT'
                            AND COALESCE(JSON_TYPE(JSON_EXTRACT(parameter, '$.target.datasource')), '') <> 'OBJECT'
                           THEN '缺少 source.datasource 和 target.datasource 对象'
                       WHEN COALESCE(JSON_TYPE(JSON_EXTRACT(parameter, '$.source.datasource')), '') <> 'OBJECT'
                           THEN '缺少 source.datasource 对象'
                       WHEN COALESCE(JSON_TYPE(JSON_EXTRACT(parameter, '$.target.datasource')), '') <> 'OBJECT'
                           THEN '缺少 target.datasource 对象'
                       ELSE '未知参数结构异常'
                   END AS invalid_reason
            FROM per_sync_task_info
            WHERE CASE
                WHEN parameter IS NULL OR JSON_VALID(parameter) = 0 THEN 1
                WHEN COALESCE(JSON_TYPE(JSON_EXTRACT(parameter, '$.source.datasource')), '') <> 'OBJECT' THEN 1
                WHEN COALESCE(JSON_TYPE(JSON_EXTRACT(parameter, '$.target.datasource')), '') <> 'OBJECT' THEN 1
                ELSE 0
            END = 1
            ORDER BY id
            """;
    // 四张表共同构成可迁移的 V2 同步管理数据；只存在部分表通常表示源库不完整，不能静默继续。
    static final Set<String> REQUIRED_TABLES = Set.of(
            "per_sync_datasource",
            "per_sync_task_info",
            "per_sync_task_log",
            "per_sync_task_lock"
    );

    private final PluginMigrationService pluginMigrationService;

    public SyncManagementMigrationService(PluginMigrationService pluginMigrationService) {
        this.pluginMigrationService = pluginMigrationService;
    }

    public void execute(DatabaseInfo target, String targetInstallPath, MigrationJob job) throws Exception {
        job.log("开始同步管理专项迁移。");
        try (Connection connection = DriverManager.getConnection(target.jdbcUrl(), target.username(), target.password())) {
            Set<String> existingTables = findExistingTables(connection);
            SyncSchemaStatus schemaStatus = classifyTables(existingTables);
            if (schemaStatus == SyncSchemaStatus.ABSENT) {
                job.log("目标数据库不存在 V2 同步管理表，跳过同步数据转换。");
            } else if (schemaStatus == SyncSchemaStatus.PARTIAL) {
                // 部分表缺失时继续运行会产生无法被 JPA 正确读取的半迁移数据，必须中止并明确列出缺表。
                Set<String> missingTables = new TreeSet<>(REQUIRED_TABLES);
                missingTables.removeAll(existingTables);
                throw new SQLException("V2 同步管理表不完整，缺少：" + String.join("、", missingTables));
            } else {
                migrateSyncData(connection, job);
            }
        }
        // 即使源库没有同步业务表，也要安装同步插件，使全新的 V3 环境能够创建 PostgreSQL 源/目标数据源。
        pluginMigrationService.updateSyncPlugins(target, targetInstallPath, job);
        job.log("同步管理专项迁移完成。");
    }

    static SyncSchemaStatus classifyTables(Set<String> existingTables) {
        long matches = REQUIRED_TABLES.stream().filter(existingTables::contains).count();
        if (matches == 0) {
            return SyncSchemaStatus.ABSENT;
        }
        return matches == REQUIRED_TABLES.size() ? SyncSchemaStatus.READY : SyncSchemaStatus.PARTIAL;
    }

    /**
     * 迁移同步管理数据时，把不可回滚的 DDL 与可回滚的 DML 分开：先校验并补齐字段，再在一个事务中
     * 转换任务、数据源、日志和锁，最后收紧字段约束。各阶段记录耗时，便于定位大数据量环境的瓶颈。
     */
    private void migrateSyncData(Connection connection, MigrationJob job) throws SQLException {
        long stepStartedNanos = System.nanoTime();
        validateTaskParameters(connection, job);
        logElapsed(job, "同步任务参数校验", stepStartedNanos);

        stepStartedNanos = System.nanoTime();
        SyncMigrationStats before = readStats(connection, false);
        logElapsed(job, "同步管理迁移前统计", stepStartedNanos);
        // MySQL DDL 会隐式提交，因此先以幂等方式补列，再在独立事务中执行所有数据转换。
        stepStartedNanos = System.nanoTime();
        ensureDatasourceRoleColumn(connection, job);
        logElapsed(job, "同步数据源角色字段准备", stepStartedNanos);

        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        stepStartedNanos = System.nanoTime();
        int migratedRunningLogCount;
        try {
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ClassPathResource(SYNC_UPGRADE_SCRIPT), StandardCharsets.UTF_8));
            migratedRunningLogCount = markRunningLogsConnectionLost(connection);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
        logElapsed(job, "同步管理数据转换", stepStartedNanos);

        // 转换完成后移除迁移期默认值 0，后续 V3/JPA 写入必须明确指定源端(1)或目标端(2)。
        stepStartedNanos = System.nanoTime();
        enforceDatasourceRoleColumn(connection);
        logElapsed(job, "同步数据源角色字段约束", stepStartedNanos);

        stepStartedNanos = System.nanoTime();
        SyncMigrationStats after = readStats(connection, true);
        logElapsed(job, "同步管理迁移后校验", stepStartedNanos);
        job.log("同步管理迁移前：数据源 " + before.datasourceCount() + "，PG 类型 " + before.postgresqlCount()
                + "，任务 " + before.taskCount() + "，运行中任务 " + before.runningTaskCount()
                + "，有效锁 " + before.activeLockCount() + "。");
        job.log("同步管理迁移后：数据源 " + after.datasourceCount() + "，PostgreSQL 类型 "
                + after.postgresqlCount() + "，源端 " + after.sourceCount() + "，目标端 " + after.targetCount()
                + "，异常角色 " + after.invalidRoleCount() + "，任务 " + after.taskCount()
                + "，运行中任务 " + after.runningTaskCount() + "，已终止运行中日志 " + migratedRunningLogCount
                + "，有效锁 " + after.activeLockCount() + "。");
    }

    /**
     * 历史日志可能达到百万级。这里让 UPDATE 自身完成唯一一次必要扫描，并直接使用受影响行数，
     * 不再为迁移前/后日志统计额外执行 COUNT 全表扫描。列举 V2 常见大小写而不对列执行 UPPER，
     * 使已有 status 索引仍有机会参与更新定位。
     */
    private int markRunningLogsConnectionLost(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE per_sync_task_log
                SET status = 'CONNECTION_LOST',
                    executor_end_time = COALESCE(
                        executor_end_time,
                        FLOOR(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000))
                WHERE status IN ('RUNNING', 'Running', 'running')
                """)) {
            return statement.executeUpdate();
        }
    }

    /**
     * 在任何同步表 DDL/DML 之前校验任务参数。异常任务不能静默跳过，否则迁移任务会显示成功，
     * 但 V3 在任务列表反序列化 parameter 时仍会失败。
     */
    private void validateTaskParameters(Connection connection, MigrationJob job) throws SQLException {
        List<InvalidSyncTask> invalidTasks = findInvalidSyncTasks(connection);
        if (!invalidTasks.isEmpty()) {
            job.log("同步任务参数校验失败，以下任务无法被 V3/JPA 正确读取：");
            for (InvalidSyncTask task : invalidTasks) {
                // 不输出 parameter 原文，避免把数据库密码等连接信息写入迁移日志。
                job.log("异常同步任务：ID=" + sanitizeLogValue(task.id())
                        + "，名称=" + sanitizeLogValue(task.name())
                        + "，原因=" + task.reason());
            }
            throw new SQLException("发现 " + invalidTasks.size()
                    + " 个异常同步任务；任务 ID、名称和具体原因已在上方日志列出。"
                    + "请先在 V2 中修复或删除这些任务，再使用全新目标库重新迁移");
        }
        job.log("同步任务参数 JSON 及源/目标数据源结构校验通过。");
    }

    private List<InvalidSyncTask> findInvalidSyncTasks(Connection connection) throws SQLException {
        List<InvalidSyncTask> invalidTasks = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(INVALID_TASK_PARAMETER_QUERY)) {
            while (result.next()) {
                invalidTasks.add(new InvalidSyncTask(
                        result.getString("id"),
                        result.getString("_name"),
                        result.getString("invalid_reason")
                ));
            }
        }
        return invalidTasks;
    }

    /**
     * 任务名称属于用户输入，去掉换行和控制字符，避免一条异常任务伪装成多条迁移日志。
     */
    private String sanitizeLogValue(String value) {
        if (value == null) {
            return "(空)";
        }
        return value.replaceAll("\\p{Cntrl}", "?");
    }

    private Set<String> findExistingTables(Connection connection) throws SQLException {
        String sql = """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN (?, ?, ?, ?)
                """;
        Set<String> tables = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (String table : REQUIRED_TABLES) {
                statement.setString(index++, table);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    tables.add(result.getString(1));
                }
            }
        }
        return tables;
    }

    private void ensureDatasourceRoleColumn(Connection connection, MigrationJob job) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'per_sync_datasource'
                  AND column_name = 'datasource_role'
                """;
        if (queryCount(connection, sql) > 0) {
            job.log("同步数据源角色字段已存在，继续执行幂等数据转换。");
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE per_sync_datasource
                    ADD COLUMN datasource_role INT NOT NULL DEFAULT 0 COMMENT '1:源数据库 2:目标数据库'
                    """);
        }
        job.log("已添加同步数据源角色字段 datasource_role。");
    }

    /**
     * 只在约束确实不满足时执行 DDL。首次迁移添加的列已经是 NOT NULL，只需删除临时默认值；
     * 直接再次 MODIFY COLUMN 可能重建整张数据源表，幂等重跑时没有必要承担这项开销。
     */
    private void enforceDatasourceRoleColumn(Connection connection) throws SQLException {
        String columnSql = """
                SELECT is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'per_sync_datasource'
                  AND column_name = 'datasource_role'
                """;
        boolean nullable;
        boolean hasDefault;
        try (Statement query = connection.createStatement();
             ResultSet result = query.executeQuery(columnSql)) {
            if (!result.next()) {
                throw new SQLException("同步数据源角色字段 datasource_role 不存在");
            }
            nullable = "YES".equalsIgnoreCase(result.getString("is_nullable"));
            hasDefault = result.getObject("column_default") != null;
        }
        if (nullable) {
            try (Statement alter = connection.createStatement()) {
                alter.execute("""
                        ALTER TABLE per_sync_datasource
                        MODIFY COLUMN datasource_role INT NOT NULL COMMENT '1:源数据库 2:目标数据库'
                        """);
            }
        } else if (hasDefault) {
            // 只移除迁移期默认值时使用元数据级 DDL，避免 MODIFY COLUMN 触发表重建。
            try (Statement alter = connection.createStatement()) {
                alter.execute("""
                        ALTER TABLE per_sync_datasource
                        ALTER COLUMN datasource_role DROP DEFAULT
                        """);
            }
        }
    }

    /**
     * 按表聚合后 CROSS JOIN 成一行，减少 JDBC 往返，并确保每张业务表每轮最多扫描一次。
     * includeRoles=false 用于补列前的 V2 统计，此时 SQL 不能引用尚不存在的 datasource_role。
     */
    private SyncMigrationStats readStats(Connection connection, boolean includeRoles) throws SQLException {
        String roleStats = includeRoles
                ? """
                  COALESCE(SUM(datasource_role = 1), 0) AS source_count,
                  COALESCE(SUM(datasource_role = 2), 0) AS target_count,
                  COALESCE(SUM(datasource_role NOT IN (1, 2)), 0) AS invalid_role_count
                  """
                : """
                  0 AS source_count,
                  0 AS target_count,
                  0 AS invalid_role_count
                  """;
        String postgresqlType = includeRoles ? "postgresql" : "pg";
        // 百万级日志表不参与统计；其余表每轮只扫描一次，并合并成一次 JDBC 往返。
        String sql = """
                SELECT datasource_stats.*, task_stats.*, lock_stats.*
                FROM (
                    SELECT COUNT(*) AS datasource_count,
                           COALESCE(SUM(LOWER(type) = '%s'), 0) AS postgresql_count,
                           %s
                    FROM per_sync_datasource
                ) datasource_stats
                CROSS JOIN (
                    SELECT COUNT(*) AS task_count,
                           COALESCE(SUM(`_status` IN ('RUNNING', 'Running', 'running')), 0)
                               AS running_task_count
                    FROM per_sync_task_info
                ) task_stats
                CROSS JOIN (
                    SELECT COALESCE(SUM(expiration_time > CURRENT_TIMESTAMP), 0) AS active_lock_count
                    FROM per_sync_task_lock
                ) lock_stats
                """.formatted(postgresqlType, roleStats);
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return new SyncMigrationStats(
                    result.getInt("datasource_count"),
                    result.getInt("postgresql_count"),
                    result.getInt("source_count"),
                    result.getInt("target_count"),
                    result.getInt("invalid_role_count"),
                    result.getInt("task_count"),
                    result.getInt("running_task_count"),
                    result.getInt("active_lock_count")
            );
        }
    }

    private int queryCount(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private void logElapsed(MigrationJob job, String operation, long startedNanos) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        job.log(operation + "耗时：" + elapsedMillis + " 毫秒。");
    }

    enum SyncSchemaStatus {
        ABSENT,
        READY,
        PARTIAL
    }

    private record SyncMigrationStats(int datasourceCount, int postgresqlCount, int sourceCount, int targetCount,
                                      int invalidRoleCount, int taskCount, int runningTaskCount,
                                      int activeLockCount) {
    }

    private record InvalidSyncTask(String id, String name, String reason) {
    }
}
