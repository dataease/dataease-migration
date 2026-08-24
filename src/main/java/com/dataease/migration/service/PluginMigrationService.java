package com.dataease.migration.service;

import com.dataease.migration.model.DatabaseInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * 将迁移工具随包发布的 V3 插件复制信息写入目标库。
 *
 * <p>迁移时同时使用 module_name 和 name 识别同一插件，兼容 V2 旧元数据与 V3 稳定模块名。
 * 数据库中的 driverPath 会重写到本次目标安装目录，保证迁移结果可部署在非 /opt/dataease3.0 路径。</p>
 */
@Service
public class PluginMigrationService {
    // 通用数据源插件在基础升级完成后写入；driverDirectory 为插件解压 JDBC 驱动的相对目录。
    private static final Map<String, PluginId> BASE_PLUGIN_IDS = Map.of(
            "飞书多维表格插件", new PluginId(1274106050030735360L, 1783937019703L, null),
            "Apache Hive数据源插件", new PluginId(1274106098604969984L, 1783937031285L, "hiveDriver"),
            "达梦数据源插件", new PluginId(1274106127038156800L, 1783937038064L, "dmDriver")
    );
    private static final Map<String, PluginId> SYNC_PLUGIN_IDS = Map.of(
            "PostgreSQL 目标数据源插件", new PluginId(1284970020220309504L, 1786527192040L, "sync"),
            "PostgreSQL 源数据源插件", new PluginId(1284970035768594432L, 1786697211956L, "sync")
    );
    private static final Set<String> SUPPORTED_PLUGIN_NAMES = Stream.concat(
            BASE_PLUGIN_IDS.keySet().stream(), SYNC_PLUGIN_IDS.keySet().stream()
    ).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final List<String> BASE_PLUGIN_JARS = List.of(
            "lark-backend-3.0.0.jar",
            "hive-backend-3.0.0.jar",
            "dm-backend-3.0.0.jar"
    );
    private static final List<String> SYNC_PLUGIN_JARS = List.of(
            "postgresql-backend-source-3.0.0.jar",
            "postgresql-backend-sink-3.0.0.jar"
    );

    private final ObjectMapper objectMapper;

    public PluginMigrationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Path> pluginJars() {
        // 文件迁移阶段必须同时上传通用插件和同步管理插件，否则数据库元数据存在但目标端没有插件 JAR。
        return Stream.concat(basePluginJars().stream(), syncPluginJars().stream()).toList();
    }

    List<Path> basePluginJars() {
        return BASE_PLUGIN_JARS.stream().map(jar -> Path.of("plugins", jar)).toList();
    }

    List<Path> syncPluginJars() {
        return SYNC_PLUGIN_JARS.stream().map(jar -> Path.of("plugins", jar)).toList();
    }

    public void updatePlugins(DatabaseInfo target, String targetInstallPath, MigrationJob job) throws Exception {
        updatePlugins(target, targetInstallPath, job, BASE_PLUGIN_IDS, basePluginJars(), true);
        job.log("通用插件数据更新完成。");
    }

    public void updateSyncPlugins(DatabaseInfo target, String targetInstallPath, MigrationJob job) throws Exception {
        updatePlugins(target, targetInstallPath, job, SYNC_PLUGIN_IDS, syncPluginJars(), false);
        job.log("同步管理插件数据更新完成。");
    }

    private void updatePlugins(DatabaseInfo target, String targetInstallPath, MigrationJob job,
                               Map<String, PluginId> pluginIds, List<Path> pluginJars,
                               boolean reportUnsupportedPlugins) throws Exception {
        List<PluginDefinition> plugins = loadPlugins(pluginIds, pluginJars, targetInstallPath);
        List<String> unsupportedPlugins;
        try (Connection connection = DriverManager.getConnection(target.jdbcUrl(), target.username(), target.password())) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                updateSupportedPlugins(connection, plugins);
                // 更新后再检查，避免仅展示名称发生变化的已支持插件被误报为“不支持”。
                unsupportedPlugins = reportUnsupportedPlugins
                        ? findUnsupportedPlugins(connection) : List.of();
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    e.addSuppressed(rollbackException);
                }
                throw e;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
        for (String name : unsupportedPlugins) {
            job.log("请升级插件：" + name);
        }
    }

    private List<PluginDefinition> loadPlugins(Map<String, PluginId> pluginIds, List<Path> pluginJars,
                                               String targetInstallPath) throws IOException {
        List<PluginDefinition> plugins = new ArrayList<>();
        for (Path jar : pluginJars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                String metadata = readEntry(zip, "plugin/", ".json");
                JsonNode root = objectMapper.readTree(metadata);
                String name = requiredText(root, "name");
                PluginId pluginId = pluginIds.get(name);
                if (pluginId == null) {
                    throw new IllegalStateException("未配置插件数据：" + name);
                }
                JsonNode config = root.required("config").deepCopy();
                if (!config.isObject()) {
                    throw new IllegalStateException("插件配置格式无效：" + name);
                }
                if (pluginId.driverDirectory() != null) {
                    // 插件包内的默认路径通常指向 /opt/dataease3.0；迁移目标允许自定义安装目录，不能沿用默认值。
                    ((ObjectNode) config).put("driverPath",
                            resolveDriverPath(targetInstallPath, pluginId.driverDirectory()));
                }
                plugins.add(new PluginDefinition(
                        pluginId.id(),
                        name,
                        readEntry(zip, "plugin/", ".svg"),
                        requiredText(root, "version"),
                        pluginId.installTime(),
                        requiredText(root, "flag"),
                        requiredText(root, "developer"),
                        objectMapper.writeValueAsString(config),
                        requiredText(root, "requireVersion"),
                        requiredText(root, "moduleName"),
                        jar.getFileName().toString()
                ));
            }
        }
        return plugins;
    }

    /**
     * 生成写入 xpack_plugin.config 的绝对驱动目录，与 V3 插件运行时的 drivers/plugin 目录约定保持一致。
     */
    static String resolveDriverPath(String targetInstallPath, String driverDirectory) {
        // targetInstallPath 是目标 Linux 服务器路径，不能使用迁移工具所在操作系统的 Path 规则拼接；
        // 否则 Windows 上运行迁移工具时可能把反斜杠写入 Linux 目标库。
        String normalizedInstallPath = targetInstallPath.replaceFirst("/+$", "");
        return normalizedInstallPath + "/drivers/plugin/" + driverDirectory;
    }

    private List<String> findUnsupportedPlugins(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT name FROM xpack_plugin");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String name = result.getString(1);
                if (!SUPPORTED_PLUGIN_NAMES.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private void updateSupportedPlugins(Connection connection, List<PluginDefinition> plugins) throws SQLException {
        // module_name 是 V2/V3 运行时使用的稳定业务键；name 则兼容旧迁移逻辑和历史展示名称。
        // 两者若命中不同记录，说明目标库已有冲突数据。此时中止比静默插入重复插件更安全。
        String findSql = "SELECT id FROM xpack_plugin WHERE module_name = ? OR name = ?";
        String updateSql = "UPDATE xpack_plugin SET name = ?, icon = ?, version = ?, install_time = ?, flag = ?, "
                + "developer = ?, config = ?, require_version = ?, module_name = ?, jar_name = ? WHERE id = ?";
        String insertSql = "INSERT INTO xpack_plugin (id, name, icon, version, install_time, flag, developer, config, "
                + "require_version, module_name, jar_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement find = connection.prepareStatement(findSql);
             PreparedStatement update = connection.prepareStatement(updateSql);
             PreparedStatement insert = connection.prepareStatement(insertSql)) {
            for (PluginDefinition plugin : plugins) {
                Long existingId = findExistingPluginId(find, plugin);
                if (existingId != null) {
                    // 保留 V2 记录主键，只更新插件元数据，避免无必要地改变已有记录身份。
                    bindUpdate(update, plugin, existingId);
                    update.executeUpdate();
                } else {
                    bindInsert(insert, plugin);
                    insert.executeUpdate();
                }
            }
        }
    }

    private Long findExistingPluginId(PreparedStatement statement, PluginDefinition plugin) throws SQLException {
        statement.setString(1, plugin.moduleName());
        statement.setString(2, plugin.name());
        try (ResultSet result = statement.executeQuery()) {
            Long id = null;
            while (result.next()) {
                if (id != null) {
                    throw new SQLException("插件名称或模块存在冲突数据：" + plugin.name()
                            + "（" + plugin.moduleName() + "）");
                }
                id = result.getLong(1);
            }
            return id;
        }
    }

    private void bindUpdate(PreparedStatement statement, PluginDefinition plugin, long existingId) throws SQLException {
        statement.setString(1, plugin.name());
        statement.setString(2, plugin.icon());
        statement.setString(3, plugin.version());
        statement.setLong(4, plugin.installTime());
        statement.setString(5, plugin.flag());
        statement.setString(6, plugin.developer());
        statement.setString(7, plugin.config());
        statement.setString(8, plugin.requireVersion());
        statement.setString(9, plugin.moduleName());
        statement.setString(10, plugin.jarName());
        statement.setLong(11, existingId);
    }

    private void bindInsert(PreparedStatement statement, PluginDefinition plugin) throws SQLException {
        statement.setLong(1, plugin.id());
        statement.setString(2, plugin.name());
        statement.setString(3, plugin.icon());
        statement.setString(4, plugin.version());
        statement.setLong(5, plugin.installTime());
        statement.setString(6, plugin.flag());
        statement.setString(7, plugin.developer());
        statement.setString(8, plugin.config());
        statement.setString(9, plugin.requireVersion());
        statement.setString(10, plugin.moduleName());
        statement.setString(11, plugin.jarName());
    }

    private String readEntry(ZipFile zip, String directory, String suffix) throws IOException {
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            if (entry.getName().startsWith(directory) && entry.getName().endsWith(suffix)) {
                try (var input = zip.getInputStream(entry)) {
                    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IOException("插件包缺少 " + suffix + " 元数据");
    }

    private String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException("插件元数据缺少字段：" + field);
        }
        return value.textValue();
    }

    private record PluginId(long id, long installTime, String driverDirectory) {
    }

    private record PluginDefinition(long id, String name, String icon, String version, long installTime, String flag,
                                    String developer, String config, String requireVersion, String moduleName,
                                    String jarName) {
    }
}
