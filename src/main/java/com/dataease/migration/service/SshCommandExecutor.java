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

@Component
public class SshCommandExecutor {
    private final int connectTimeoutMillis;

    public SshCommandExecutor(@Value("${migration.ssh.connect-timeout-millis}") int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public void execute(ServerInfo server, String command, MigrationJob job) throws Exception {
        Session session = connect(server);
        try {
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command + " 2>&1");
            InputStream output = channel.getInputStream();
            channel.connect(connectTimeoutMillis);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(output, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    job.log(line);
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

    public void download(ServerInfo server, String remotePath, Path localPath) throws Exception {
        transfer(server, channel -> channel.get(remotePath, localPath.toString()));
    }

    public void upload(ServerInfo server, Path localPath, String remotePath) throws Exception {
        transfer(server, channel -> channel.put(localPath.toString(), remotePath));
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
