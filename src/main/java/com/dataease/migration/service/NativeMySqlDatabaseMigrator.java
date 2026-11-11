package com.dataease.migration.service;

import com.dataease.migration.model.DatabaseConnection;
import com.dataease.migration.model.DatabaseInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLNonTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Component
public class NativeMySqlDatabaseMigrator implements DatabaseMigrator {
    private final MySqlToolResolver toolResolver;
    private long commandTimeoutMillis = 3600000;
    private static final Pattern CLIENT_ERROR = Pattern.compile("(?i)\\bERROR\\s+(\\d+)\\b");
    private static final Set<Integer> CONNECTION_ERRORS = Set.of(2002, 2003, 2005, 2006, 2013, 2026, 2055);

    public NativeMySqlDatabaseMigrator(MySqlToolResolver toolResolver) {
        this.toolResolver = toolResolver;
    }

    @Value("${migration.mysql.command-timeout-millis:${migration.ssh.command-timeout-millis:3600000}}")
    public void commandTimeout(long timeout) {
        commandTimeoutMillis = Math.max(1000, timeout);
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
        run(command, database.password(), null, job);
    }

    private void recreateTargetDatabase(MySqlToolResolver.MySqlTools tools, DatabaseInfo database,
                                        DatabaseConnection connection, MigrationJob job) throws Exception {
        job.log("正在删除并重建 DataEase 3.0 MySQL 数据库：" + connection.database() + "。");
        String identifier = ShellEscaper.sqlIdentifier(connection.database());
        String sql = "DROP DATABASE IF EXISTS " + identifier + "; CREATE DATABASE " + identifier
                + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;";
        List<String> command = mysqlCommand(tools.mysql(), database, connection);
        command.add("--execute=" + sql);
        run(command, database.password(), null, job);
    }

    private void importDump(MySqlToolResolver.MySqlTools tools, DatabaseInfo database, DatabaseConnection connection,
                            Path dump, MigrationJob job) throws Exception {
        job.log("正在导入 DataEase 3.0 MySQL 数据库。");
        List<String> command = mysqlCommand(tools.mysql(), database, connection);
        command.add(connection.database());
        run(command, database.password(), dump, job);
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

    private void run(List<String> command, String password, Path input, MigrationJob job) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        processBuilder.environment().put("MYSQL_PWD", password);
        // Let the client read the dump directly. A Java writer blocked on a full stdin pipe
        // would otherwise bypass the command deadline when the server stops reading.
        if (input != null) processBuilder.redirectInput(input.toFile());
        Process process = processBuilder.start();
        ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "mysql-client-output");
            thread.setDaemon(true);
            return thread;
        });
        AtomicInteger errorCode = new AtomicInteger();
        AtomicBoolean disconnected = new AtomicBoolean();
        try {
            if (input == null) process.getOutputStream().close();
            Future<?> output = reader.submit(() -> {
                try (BufferedReader stream = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = stream.readLine()) != null) {
                        var error = CLIENT_ERROR.matcher(line);
                        if (error.find()) {
                            int code = Integer.parseInt(error.group(1));
                            errorCode.set(code);
                            if (CONNECTION_ERRORS.contains(code)) disconnected.set(true);
                        }
                        if (line.toLowerCase(java.util.Locale.ROOT).matches(
                                ".*(?:lost connection|server has gone away|connection reset|broken pipe).*"))
                            disconnected.set(true);
                        job.log(line);
                    }
                    return null;
                }
            });
            if (!process.waitFor(commandTimeoutMillis, TimeUnit.MILLISECONDS))
                throw new TimeoutException("本地MySQL客户端执行超时，SQL结束状态需要现场核查");
            try {
                output.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                throw new SQLNonTransientConnectionException("无法确认MySQL客户端输出与SQL结束状态", "08S01", failure.getCause());
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                // Do not discard ERROR 2013/2006 as an ordinary SQL failure. Closing the client
                // connection is not proof that a remote SQL statement finished or rolled back.
                if (disconnected.get() || exitCode >= 128)
                    throw new SQLNonTransientConnectionException(
                            "MySQL客户端连接中断或进程异常退出，SQL结束状态需要现场核查（退出码：" + exitCode + "）", "08S01");
                throw new IllegalStateException("本地MySQL命令执行失败，退出码：" + exitCode
                        + (errorCode.get() == 0 ? "" : "，MySQL错误：" + errorCode.get()));
            }
        } finally {
            process.descendants().forEach(child -> child.destroyForcibly());
            if (process.isAlive()) process.destroyForcibly();
            try { process.getInputStream().close(); } catch (IOException ignored) {}
            try { process.getOutputStream().close(); } catch (IOException ignored) {}
            reader.shutdownNow();
            if (!process.waitFor(5, TimeUnit.SECONDS))
                throw new TimeoutException("MySQL客户端进程结束状态无法确认，请先完成现场核查");
        }
    }
}
