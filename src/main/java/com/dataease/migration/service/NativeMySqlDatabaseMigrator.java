package com.dataease.migration.service;

import com.dataease.migration.model.DatabaseConnection;
import com.dataease.migration.model.DatabaseInfo;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class NativeMySqlDatabaseMigrator implements DatabaseMigrator {
    private final MySqlToolResolver toolResolver;

    public NativeMySqlDatabaseMigrator(MySqlToolResolver toolResolver) {
        this.toolResolver = toolResolver;
    }

    public boolean isAvailable() {
        return toolResolver.resolve().isPresent();
    }

    @Override
    public void migrate(DatabaseInfo source, DatabaseInfo target, MigrationJob job) throws Exception {
        MySqlToolResolver.MySqlTools tools = toolResolver.resolve()
                .orElseThrow(() -> new IllegalStateException("当前环境没有可用的内置 MySQL 客户端工具"));
        DatabaseConnection sourceConnection = DatabaseConnection.fromJdbcUrl(source.jdbcUrl());
        DatabaseConnection targetConnection = DatabaseConnection.fromJdbcUrl(target.jdbcUrl());
        Path dump = Files.createTempFile("dataease-database-", ".sql");
        try {
            job.log("使用本地 MySQL 客户端工具（" + tools.platform() + "）迁移数据库。");
            runDump(tools, source, sourceConnection, dump, job);
            recreateTargetDatabase(tools, target, targetConnection, job);
            importDump(tools, target, targetConnection, dump, job);
        } finally {
            Files.deleteIfExists(dump);
        }
    }

    private void runDump(MySqlToolResolver.MySqlTools tools, DatabaseInfo database, DatabaseConnection connection,
                         Path dump, MigrationJob job) throws Exception {
        job.log("正在导出 DataEase 2.0 MySQL 数据库：" + connection.database() + "。");
        List<String> command = mysqlCommand(tools.mysqldump(), database, connection);
        command.add("--single-transaction");
        command.add("--routines");
        command.add("--triggers");
        command.add("--events");
        command.add("--set-gtid-purged=OFF");
        command.add("--result-file=" + dump);
        command.add(connection.database());
        run(command, database.password(), job);
    }

    private void recreateTargetDatabase(MySqlToolResolver.MySqlTools tools, DatabaseInfo database,
                                        DatabaseConnection connection, MigrationJob job) throws Exception {
        job.log("正在删除并重建 DataEase 3.0 MySQL 数据库：" + connection.database() + "。");
        String identifier = ShellEscaper.sqlIdentifier(connection.database());
        String sql = "DROP DATABASE IF EXISTS " + identifier + "; CREATE DATABASE " + identifier
                + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;";
        List<String> command = mysqlCommand(tools.mysql(), database, connection);
        command.add("--execute=" + sql);
        run(command, database.password(), job);
    }

    private void importDump(MySqlToolResolver.MySqlTools tools, DatabaseInfo database, DatabaseConnection connection,
                            Path dump, MigrationJob job) throws Exception {
        job.log("正在导入 DataEase 3.0 MySQL 数据库。");
        List<String> command = mysqlCommand(tools.mysql(), database, connection);
        command.add(connection.database());
        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        processBuilder.environment().put("MYSQL_PWD", database.password());
        Process process = processBuilder.start();
        try (var dumpInput = Files.newInputStream(dump); var mysqlInput = process.getOutputStream()) {
            dumpInput.transferTo(mysqlInput);
        }
        logOutput(process, job);
        ensureSuccess(process);
    }

    private List<String> mysqlCommand(Path executable, DatabaseInfo database, DatabaseConnection connection) {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("--protocol=tcp");
        command.add("--host=" + connection.host());
        command.add("--port=" + connection.port());
        command.add("--user=" + database.username());
        return command;
    }

    private void run(List<String> command, String password, MigrationJob job) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        processBuilder.environment().put("MYSQL_PWD", password);
        Process process = processBuilder.start();
        logOutput(process, job);
        ensureSuccess(process);
    }

    private void logOutput(Process process, MigrationJob job) throws IOException {
        try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null) {
                job.log(line);
            }
        }
    }

    private void ensureSuccess(Process process) throws InterruptedException {
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("本地 MySQL 命令执行失败，退出码：" + exitCode);
        }
    }
}
