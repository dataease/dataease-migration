package com.dataease.migration.service;

import com.dataease.migration.model.ServerInfo;
import com.jcraft.jsch.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Component
public class SshCommandExecutor {
    private final int connectTimeoutMillis;
    private int commandTimeoutMillis = 3600000;

    public SshCommandExecutor(@Value("${migration.ssh.connect-timeout-millis:30000}") int timeout) {
        connectTimeoutMillis = timeout;
    }

    @Value("${migration.ssh.command-timeout-millis:3600000}")
    public void commandTimeout(int timeout) {
        commandTimeoutMillis = Math.max(1000, timeout);
    }

    public void execute(ServerInfo server, String command, MigrationJob job) throws Exception {
        run(server, command, job::log);
    }

    public String capture(ServerInfo server, String command) throws Exception {
        StringBuilder result = new StringBuilder();
        run(
                server,
                command,
                line -> {
                    if (result.length() > 8 * 1024 * 1024)
                        throw new IllegalStateException("远程输出超过读取上限");
                    result.append(line).append('\n');
                });
        return result.toString().stripTrailing();
    }

    public void run(ServerInfo server, String command, Consumer<String> log) throws Exception {
        Session session = connect(server);
        ChannelExec channel = null;
        ExecutorService reader =
                Executors.newSingleThreadExecutor(
                        r -> {
                            Thread t = new Thread(r, "ops-ssh-reader");
                            t.setDaemon(true);
                            return t;
                        });
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command + " 2>&1");
            InputStream output = channel.getInputStream();
            channel.connect(connectTimeoutMillis);
            Future<?> future =
                    reader.submit(
                            () -> {
                                try (BufferedReader in =
                                        new BufferedReader(
                                                new InputStreamReader(
                                                        output, StandardCharsets.UTF_8))) {
                                    String line;
                                    while ((line = in.readLine()) != null) log.accept(line);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(commandTimeoutMillis);
            while (!channel.isClosed()) {
                if (System.nanoTime() > deadline)
                    throw new IllegalStateException("SSH 命令执行超时，已断开执行通道");
                Thread.sleep(80);
            }
            int exitStatus = channel.getExitStatus();
            if (exitStatus < 0)
                throw new java.net.SocketException("SSH 连接中断，未取得远端命令退出状态；不能确认远端进程已结束");
            future.get(
                    Math.max(1000, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())),
                    TimeUnit.MILLISECONDS);
            if (exitStatus != 0) throw new IllegalStateException("远程命令执行失败，退出码：" + exitStatus);
        } finally {
            if (channel != null) channel.disconnect();
            session.disconnect();
            reader.shutdownNow();
        }
    }

    public String readRemoteFile(ServerInfo server, String path) throws Exception {
        return capture(server, "cat " + ShellEscaper.quote(path));
    }

    public void upload(ServerInfo server, Path local, String remote) throws Exception {
        transfer(server, c -> c.put(local.toString(), remote));
    }

    public void download(ServerInfo server, String remote, Path local) throws Exception {
        transfer(server, c -> c.get(remote, local.toString()));
    }

    private Session connect(ServerInfo server) throws Exception {
        JSch jsch = new JSch();
        if (server.privateKey() != null && !server.privateKey().isBlank())
            jsch.addIdentity(
                    "operations",
                    server.privateKey().getBytes(StandardCharsets.UTF_8),
                    null,
                    server.passphrase() == null
                            ? null
                            : server.passphrase().getBytes(StandardCharsets.UTF_8));
        if (server.hostKey() != null && !server.hostKey().isBlank()) {
            String pin = server.hostKey().trim();
            jsch.setHostKeyRepository(
                    new HostKeyRepository() {
                        public int check(String host, byte[] key) {
                            try {
                                String fingerprint =
                                        "SHA256:"
                                                + Base64.getEncoder()
                                                        .withoutPadding()
                                                        .encodeToString(
                                                                MessageDigest.getInstance("SHA-256")
                                                                        .digest(key));
                                return MessageDigest.isEqual(
                                                fingerprint.getBytes(StandardCharsets.UTF_8),
                                                pin.getBytes(StandardCharsets.UTF_8))
                                        ? OK
                                        : CHANGED;
                            } catch (Exception e) {
                                return CHANGED;
                            }
                        }

                        public void add(HostKey key, UserInfo ui) {}

                        public void remove(String h, String t) {}

                        public void remove(String h, String t, byte[] k) {}

                        public String getKnownHostsRepositoryID() {
                            return "operations-pinned-host-key";
                        }

                        public HostKey[] getHostKey() {
                            return new HostKey[0];
                        }

                        public HostKey[] getHostKey(String h, String t) {
                            return new HostKey[0];
                        }
                    });
        } else {
            Path known = Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
            if (!Files.isRegularFile(known))
                throw new IllegalArgumentException(
                        "缺少 SSH 主机密钥。请核对服务器 SHA256 指纹后填写 hostKey，或由管理员配置 known_hosts。");
            jsch.setKnownHosts(known.toString());
        }
        Session session = jsch.getSession(server.username(), server.host(), server.port());
        if (server.password() != null) session.setPassword(server.password());
        session.setConfig("StrictHostKeyChecking", "yes");
        session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive");
        session.setTimeout(commandTimeoutMillis);
        session.setServerAliveInterval(15000);
        session.setServerAliveCountMax(3);
        try {
            session.connect(connectTimeoutMillis);
            return session;
        } catch (Exception e) {
            session.disconnect();
            throw new IllegalStateException("SSH 连接失败，请核对主机密钥、地址及认证配置", e);
        }
    }

    private void transfer(ServerInfo server, SftpOperation operation) throws Exception {
        Session s = connect(server);
        ChannelSftp c = null;
        try {
            c = (ChannelSftp) s.openChannel("sftp");
            c.connect(connectTimeoutMillis);
            operation.apply(c);
        } finally {
            if (c != null) c.disconnect();
            s.disconnect();
        }
    }

    @FunctionalInterface
    private interface SftpOperation {
        void apply(ChannelSftp channel) throws Exception;
    }
}
