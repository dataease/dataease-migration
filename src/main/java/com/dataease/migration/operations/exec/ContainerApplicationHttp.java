package com.dataease.migration.operations.exec;

import static com.dataease.migration.operations.exec.HostCommands.*;

import com.dataease.migration.operations.core.OperationsFiles;

import java.io.*;
import java.net.SocketException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** An ephemeral, private HTTP channel over the saved local/SSH Docker management connection. */
final class ContainerApplicationHttp implements AutoCloseable {
    record Response(int status, byte[] body) {}

    private final HostCommands host;
    private final Map<String, Object> instance;
    private final Map<String, String> credentials;
    private final String container, remote;
    private final int port;
    private final Path local;

    @SuppressWarnings("unchecked")
    ContainerApplicationHttp(
            HostCommands host, ComposeManager compose, Map<String, Object> i, Map<String, String> c)
            throws Exception {
        this.host = host;
        instance = i;
        credentials = c;
        container = compose.containerId(i, c, text(i, "applicationService", ""));
        if (container.isBlank()) throw new IllegalArgumentException("目标应用容器尚未启动");
        Map<String, Object> cfg = compose.config(i, c);
        Map<String, Object> services = (Map<String, Object>) cfg.get("services");
        Map<String, Object> app = (Map<String, Object>) services.get(i.get("applicationService"));
        List<?> ports = app.get("ports") instanceof List<?> list ? list : List.of();
        if (ports.isEmpty() || !(ports.get(0) instanceof Map<?, ?> p))
            throw new IllegalArgumentException("未识别应用容器端口");
        port = Integer.parseInt(String.valueOf(p.get("target")));
        remote = "/tmp/deops-http-" + UUID.randomUUID();
        local = Files.createTempDirectory("deops-http-");
        OperationsFiles.createPrivateDirectory(local);
        try {
            Path helper = local.resolve("bridge.jar");
            String name = ContainerHttpBridge.class.getName().replace('.', '/') + ".class";
            try (InputStream input = ContainerHttpBridge.class.getResourceAsStream("/" + name);
                    JarOutputStream output = new JarOutputStream(Files.newOutputStream(helper))) {
                if (input == null) throw new IllegalStateException("容器接口访问组件缺失");
                output.putNextEntry(new JarEntry(name));
                input.transferTo(output);
                output.closeEntry();
            }
            host.run(i, c, "umask 077; mkdir " + quote(remote), line -> {});
            host.upload(i, c, helper, remote + "/bridge.jar");
            host.run(
                    i,
                    c,
                    "docker exec --user 0 "
                            + quote(container)
                            + " sh -c "
                            + quote("umask 077; mkdir " + quote(remote))
                            + " && docker cp "
                            + quote(remote + "/bridge.jar")
                            + " "
                            + quote(container + ":" + remote + "/bridge.jar"),
                    line -> {});
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    Response request(String path, String token, Path upload, String filename) throws Exception {
        return request(path, token, upload, filename, null);
    }

    Response control(String body) throws Exception {
        return request(ApplicationIsolationGuard.ENDPOINT, "", null, null, body);
    }

    private Response request(String path, String token, Path upload, String filename, String body) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("port", String.valueOf(port));
        properties.setProperty("path", path);
        properties.setProperty("token", token);
        if (body != null) properties.setProperty("control", body);
        if (upload != null) {
            host.upload(instance, credentials, upload, remote + "/upload.jar");
            host.run(
                    instance,
                    credentials,
                    "docker cp "
                            + quote(remote + "/upload.jar")
                            + " "
                            + quote(container + ":" + remote + "/upload.jar"),
                    line -> {});
            properties.setProperty("upload", remote + "/upload.jar");
            properties.setProperty("filename", filename);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Writer writer =
                new OutputStreamWriter(bytes, java.nio.charset.StandardCharsets.UTF_8)) {
            properties.store(writer, null);
        }
        byte[] key = new byte[32], iv = new byte[12];
        new java.security.SecureRandom().nextBytes(key);
        new java.security.SecureRandom().nextBytes(iv);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(bytes.toByteArray());
        ByteArrayOutputStream sealed = new ByteArrayOutputStream();
        sealed.write(iv);
        sealed.write(encrypted);
        Path request = local.resolve("request.sealed");
        OperationsFiles.writePrivate(request, sealed.toByteArray());
        String oneTimeKey = Base64.getEncoder().encodeToString(key);
        Arrays.fill(key, (byte) 0);
        try {
            host.upload(instance, credentials, request, remote + "/request.sealed");
            host.run(
                    instance,
                    credentials,
                    "chmod 600 "
                            + quote(remote + "/request.sealed")
                            + " && docker cp "
                            + quote(remote + "/request.sealed")
                            + " "
                            + quote(container + ":" + remote + "/request.sealed"),
                    line -> {});
            String result =
                    host.capture(
                            instance,
                            credentials,
                            "docker exec --user 0 "
                                    + quote(container)
                                    + " java -cp "
                                    + quote(remote + "/bridge.jar")
                                    + " "
                                    + quote(ContainerHttpBridge.class.getName())
                                    + " "
                                    + quote(remote + "/request.sealed")
                                    + " "
                                    + quote(oneTimeKey));
            String[] lines = result.split("\\r?\\n", 2);
            if (lines.length < 1 || !lines[0].matches("[1-5][0-9]{2}"))
                throw new SocketException("容器内应用接口连接未确认，请检查应用启动状态和接口路径");
            try {
                return new Response(
                        Integer.parseInt(lines[0]),
                        lines.length > 1 ? Base64.getDecoder().decode(lines[1]) : new byte[0]);
            } catch (IllegalArgumentException invalid) {
                throw new SocketException("容器内应用接口响应不完整");
            }
        } finally {
            Files.deleteIfExists(request);
        }
    }

    @Override
    public void close() {
        try {
            host.run(
                    instance,
                    credentials,
                    "docker exec --user 0 " + quote(container) + " rm -rf -- " + quote(remote),
                    line -> {});
        } catch (Exception ignored) {
        }
        try {
            host.run(instance, credentials, "rm -rf -- " + quote(remote), line -> {});
        } catch (Exception ignored) {
        }
        try {
            com.dataease.migration.operations.media.ArchiveSupport.deleteTree(local);
        } catch (Exception ignored) {
        }
    }
}
