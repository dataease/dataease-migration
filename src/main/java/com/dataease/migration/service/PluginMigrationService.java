package com.dataease.migration.service;

import com.dataease.migration.model.DatabaseInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.zip.ZipFile;

@Service
public class PluginMigrationService {
    private static final Map<String, PluginId> PLUGIN_IDS = Map.of(
            "飞书多维表格插件", new PluginId(1274106050030735360L, 1783937019703L),
            "Apache Hive数据源插件", new PluginId(1274106098604969984L, 1783937031285L),
            "达梦数据源插件", new PluginId(1274106127038156800L, 1783937038064L)
    );
    private static final List<String> PLUGIN_JARS = List.of(
            "lark-backend-3.0.0.jar",
            "hive-backend-3.0.0.jar",
            "dm-backend-3.0.0.jar"
    );

    private final ObjectMapper objectMapper;

    public PluginMigrationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Path> pluginJars() {
        return PLUGIN_JARS.stream().map(jar -> Path.of("plugins", jar)).toList();
    }

    public void updatePlugins(DatabaseInfo target, MigrationJob job) throws Exception {
        List<PluginDefinition> plugins = loadPlugins();
        try (Connection connection = DriverManager.getConnection(target.jdbcUrl(), target.username(), target.password())) {
            List<String> unsupportedPlugins = findUnsupportedPlugins(connection);
            updateSupportedPlugins(connection, plugins);
            for (String name : unsupportedPlugins) {
                job.log("请升级插件：" + name);
            }
        }
        job.log("插件数据更新完成。");
    }

    private List<PluginDefinition> loadPlugins() throws IOException {
        List<PluginDefinition> plugins = new ArrayList<>();
        for (Path jar : pluginJars()) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                String metadata = readEntry(zip, "plugin/", ".json");
                JsonNode root = objectMapper.readTree(metadata);
                String name = requiredText(root, "name");
                PluginId pluginId = PLUGIN_IDS.get(name);
                if (pluginId == null) {
                    throw new IllegalStateException("未配置插件数据：" + name);
                }
                plugins.add(new PluginDefinition(
                        pluginId.id(),
                        name,
                        readEntry(zip, "plugin/", ".svg"),
                        requiredText(root, "version"),
                        pluginId.installTime(),
                        requiredText(root, "flag"),
                        requiredText(root, "developer"),
                        objectMapper.writeValueAsString(root.required("config")),
                        requiredText(root, "requireVersion"),
                        requiredText(root, "moduleName"),
                        jar.getFileName().toString()
                ));
            }
        }
        return plugins;
    }

    private List<String> findUnsupportedPlugins(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT name FROM xpack_plugin");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String name = result.getString(1);
                if (!PLUGIN_IDS.containsKey(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private void updateSupportedPlugins(Connection connection, List<PluginDefinition> plugins) throws SQLException {
        String sql = "UPDATE xpack_plugin SET id = ?, icon = ?, version = ?, install_time = ?, flag = ?, "
                + "developer = ?, config = ?, require_version = ?, module_name = ?, jar_name = ? WHERE name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (PluginDefinition plugin : plugins) {
                statement.setLong(1, plugin.id());
                statement.setString(2, plugin.icon());
                statement.setString(3, plugin.version());
                statement.setLong(4, plugin.installTime());
                statement.setString(5, plugin.flag());
                statement.setString(6, plugin.developer());
                statement.setString(7, plugin.config());
                statement.setString(8, plugin.requireVersion());
                statement.setString(9, plugin.moduleName());
                statement.setString(10, plugin.jarName());
                statement.setString(11, plugin.name());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("未找到待更新的插件：" + plugin.name());
                }
            }
        }
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

    private record PluginId(long id, long installTime) {
    }

    private record PluginDefinition(long id, String name, String icon, String version, long installTime, String flag,
                                    String developer, String config, String requireVersion, String moduleName,
                                    String jarName) {
    }
}
