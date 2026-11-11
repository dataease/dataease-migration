package com.dataease.migration.operations.exec;

import com.dataease.migration.model.ServerInfo;
import com.dataease.migration.service.MigrationService;
import com.dataease.migration.service.SshCommandExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Component
public class HostCommands {
    private final SshCommandExecutor ssh;
    private final long timeout;

    public HostCommands(
            SshCommandExecutor ssh,
            @Value("${migration.ssh.command-timeout-millis:3600000}") long timeout) {
        this.ssh = ssh;
        this.timeout = timeout;
    }

    public ServerInfo server(Map<String, Object> instance, Map<String, String> credentials) {
        String auth =
                credentials.getOrDefault(
                        "sshAuth",
                        text(
                                instance,
                                "sshAuth",
                                credentials.getOrDefault("privateKey", "").isBlank()
                                        ? "password"
                                        : "key"));
        String mode = text(instance, "connection", "ssh");
        String portValue = credentials.getOrDefault("sshPort", text(instance, "sshPort", "22"));
        int port = "local".equals(mode) || portValue.isBlank() ? 22 : Integer.parseInt(portValue);
        return new ServerInfo(
                text(instance, "host", "127.0.0.1"),
                credentials.getOrDefault("sshUser", text(instance, "sshUser", "root")),
                auth.equals("key") ? null : credentials.get("sshPassword"),
                port,
                text(instance, "path", ""),
                auth.equals("key") ? credentials.get("privateKey") : null,
                credentials.get("passphrase"),
                credentials.get("hostKey"),
                mode);
    }

    public String capture(Map<String, Object> i, Map<String, String> c, String command)
            throws Exception {
        StringBuilder b = new StringBuilder();
        run(
                i,
                c,
                command,
                line -> {
                    if (b.length() > 16 * 1024 * 1024) throw new IllegalStateException("命令输出过大");
                    b.append(line).append('\n');
                });
        return b.toString().stripTrailing();
    }

    public void run(
            Map<String, Object> i, Map<String, String> c, String command, Consumer<String> log)
            throws Exception {
        ServerInfo server = server(i, c);
        if (!MigrationService.isLocalServer(server)) {
            ssh.run(server, command, log);
            return;
        }
        Process process =
                new ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start();
        ExecutorService reader =
                Executors.newSingleThreadExecutor(
                        r -> {
                            Thread t = new Thread(r, "ops-process-reader");
                            t.setDaemon(true);
                            return t;
                        });
        try {
            Future<?> f =
                    reader.submit(
                            () -> {
                                try (BufferedReader in =
                                        new BufferedReader(
                                                new InputStreamReader(
                                                        process.getInputStream(),
                                                        StandardCharsets.UTF_8))) {
                                    String line;
                                    while ((line = in.readLine()) != null) log.accept(line);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
            if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IllegalStateException("本机命令执行超时，已终止进程");
            }
            f.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0)
                throw new IllegalStateException("命令执行失败，退出码：" + process.exitValue());
        } finally {
            process.destroyForcibly();
            reader.shutdownNow();
        }
    }

    public void upload(Map<String, Object> i, Map<String, String> c, Path local, String remote)
            throws Exception {
        if (MigrationService.isLocalServer(server(i, c)))
            Files.copy(local, Path.of(remote), StandardCopyOption.REPLACE_EXISTING);
        else ssh.upload(server(i, c), local, remote);
    }

    public void download(Map<String, Object> i, Map<String, String> c, String remote, Path local)
            throws Exception {
        if (MigrationService.isLocalServer(server(i, c)))
            Files.copy(Path.of(remote), local, StandardCopyOption.REPLACE_EXISTING);
        else ssh.download(server(i, c), remote, local);
    }

    public static String text(Map<?, ?> map, String key, String fallback) {
        Object v = map.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    public static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    public static String path(String value) {
        if (value == null
                || !value.startsWith("/")
                || value.equals("/")
                || value.contains("\\")
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("安装目录须为非根目录的绝对路径");
        Path p = Path.of(value);
        if (!p.normalize().equals(p)) throw new IllegalArgumentException("安装目录不能含相对路径段");
        return p.toString();
    }
}
