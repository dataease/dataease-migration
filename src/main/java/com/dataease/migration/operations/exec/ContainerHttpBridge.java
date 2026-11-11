package com.dataease.migration.operations.exec;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Standalone JRE-only helper. Executed inside the managed application container. */
public final class ContainerHttpBridge {
    private ContainerHttpBridge() {}

    public static void main(String[] args) {
        ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor();
        try {
            Properties p = new Properties();
            byte[] sealed = Files.readAllBytes(Path.of(args[0])),
                    key = Base64.getDecoder().decode(args[1]);
            if (sealed.length < 28 || sealed.length > 128 * 1024 || key.length != 32)
                throw new IOException();
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    javax.crypto.Cipher.DECRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(key, "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, Arrays.copyOf(sealed, 12)));
            byte[] plaintext = cipher.doFinal(sealed, 12, sealed.length - 12);
            Arrays.fill(key, (byte) 0);
            try (Reader input = new StringReader(new String(plaintext, StandardCharsets.UTF_8))) {
                p.load(input);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
            int port = Integer.parseInt(p.getProperty("port"));
            String path = p.getProperty("path"), token = p.getProperty("token", "");
            if (port < 1
                    || port > 65535
                    || !path.matches("/[A-Za-z0-9_/-]*")
                    || token.chars().anyMatch(Character::isISOControl)) throw new IOException();
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                            .timeout(Duration.ofMinutes(3))
                            .header("Accept", "application/json");
            if (!token.isBlank()) request.header("X-DE-TOKEN", token);
            if (p.getProperty("control") != null) {
                if (!"/__deops_guard".equals(path)) throw new IOException();
                String capability = Files.readString(Path.of("/tmp/.deops-guard-token")).trim();
                if (!capability.matches("[A-Za-z0-9_-]{43}")) throw new IOException();
                request.header("X-DEOPS-Guard", capability)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(p.getProperty("control"), StandardCharsets.UTF_8));
            } else if (p.getProperty("upload") != null) {
                String boundary = "deops-" + UUID.randomUUID(),
                        filename = p.getProperty("filename");
                if (!filename.matches("[A-Za-z0-9._-]+\\.jar")) throw new IOException();
                byte[] prefix =
                        ("--"
                                        + boundary
                                        + "\r\n"
                                        + "Content-Disposition: form-data; name=\"file\";"
                                        + " filename=\""
                                        + filename
                                        + "\"\r\nContent-Type: application/java-archive\r\n\r\n")
                                .getBytes(StandardCharsets.UTF_8);
                request.header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(
                                HttpRequest.BodyPublishers.concat(
                                        HttpRequest.BodyPublishers.ofByteArray(prefix),
                                        HttpRequest.BodyPublishers.ofFile(
                                                Path.of(p.getProperty("upload"))),
                                        HttpRequest.BodyPublishers.ofByteArray(
                                                ("\r\n--" + boundary + "--\r\n")
                                                        .getBytes(StandardCharsets.UTF_8))));
            } else request.GET();
            HttpResponse<InputStream> response =
                    HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .connectTimeout(Duration.ofSeconds(10))
                            .build()
                            .send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream input = response.body()) {
                deadline.schedule(
                        () -> {
                            try {
                                input.close();
                            } catch (IOException ignored) {
                            }
                        },
                        3,
                        TimeUnit.MINUTES);
                bytes = input.readNBytes(2 * 1024 * 1024 + 1);
            }
            if (bytes.length > 2 * 1024 * 1024) throw new IOException();
            System.out.println(response.statusCode());
            System.out.println(Base64.getEncoder().encodeToString(bytes));
        } catch (Exception unavailable) {
            // Never echo request properties, tokens, server bodies or a credential-bearing cause.
            System.out.println("0");
            System.out.println("unavailable");
        } finally {
            deadline.shutdownNow();
        }
    }
}
