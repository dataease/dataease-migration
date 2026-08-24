package com.dataease.migration.service;

import com.dataease.migration.model.ServerInfo;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Consumer;

@Component
public class SshCommandExecutor {
    private final int connectTimeoutMillis;

    public SshCommandExecutor(@Value("${migration.ssh.connect-timeout-millis}") int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public void execute(ServerInfo server, String command, MigrationJob job) throws Exception {
        runCommand(server, command, job::log);
    }

    /**
     * 执行远程命令并返回合并后的标准输出，用于读取 docker-compose.yml 等小文件。
     * 输出中同时包含命令的标准错误，因此调用方在命令失败时无法区分错误来源，
     * 错误信息会通过退出码非零体现。
     */
    public String capture(ServerInfo server, String command) throws Exception {
        StringBuilder output = new StringBuilder();
        runCommand(server, command, line -> {
            if (!output.isEmpty()) {
                output.append('\n');
            }
            output.append(line);
        });
        return output.toString();
    }

    public String readRemoteFile(ServerInfo server, String remotePath) throws Exception {
        return capture(server, "cat " + ShellEscaper.quote(remotePath));
    }

    public void download(ServerInfo server, String remotePath, Path localPath) throws Exception {
        transfer(server, channel -> channel.get(remotePath, localPath.toString()));
    }

    public void upload(ServerInfo server, Path localPath, String remotePath) throws Exception {
        transfer(server, channel -> channel.put(localPath.toString(), remotePath));
    }

    private void runCommand(ServerInfo server, String command, Consumer<String> lineConsumer) throws Exception {
        Session session = connect(server);
        try {
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command + " 2>&1");
            InputStream output = channel.getInputStream();
            channel.connect(connectTimeoutMillis);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(output, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineConsumer.accept(line);
                }
            }
            while (!channel.isClosed()) {
                Thread.sleep(100);
            }
            if (channel.getExitStatus() != 0) {
                throw new IllegalStateException("远程命令执行失败，退出码：" + channel.getExitStatus());
            }
            channel.disconnect();
        } finally {
            session.disconnect();
        }
    }

    private Session connect(ServerInfo server) throws Exception {
        Session session = new JSch().getSession(server.username(), server.host(), server.port());
        session.setPassword(server.password());
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(connectTimeoutMillis);
        return session;
    }

    private void transfer(ServerInfo server, SftpOperation operation) throws Exception {
        Session session = connect(server);
        try {
            ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(connectTimeoutMillis);
            operation.apply(channel);
            channel.disconnect();
        } finally {
            session.disconnect();
        }
    }

    @FunctionalInterface
    private interface SftpOperation {
        void apply(ChannelSftp channel) throws Exception;
    }
}
