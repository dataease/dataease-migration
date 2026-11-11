package com.dataease.migration.operations.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM encrypted connection material; only opaque references enter instance records. */
@Component
public final class CredentialVault {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path directory;
    private final ObjectMapper mapper;
    private final SecretKey key;

    public CredentialVault(OperationsProperties properties, ObjectMapper mapper) {
        this.directory = properties.dataDirectory().resolve("credentials");
        this.mapper = mapper;
        OperationsFiles.createPrivateDirectory(directory);
        Path keyFile = properties.dataDirectory().resolve("vault.key");
        try {
            if (Files.isSymbolicLink(keyFile))
                throw new IOException("Vault key may not be a symbolic link");
            byte[] encoded;
            if (Files.exists(keyFile)) {
                encoded = Files.readAllBytes(keyFile);
                if (encoded.length != 32) throw new IOException("Invalid vault key");
                OperationsFiles.restrictFile(keyFile);
            } else {
                KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(256);
                encoded = generator.generateKey().getEncoded();
                OperationsFiles.writePrivate(keyFile, encoded);
            }
            this.key = new SecretKeySpec(encoded, "AES");
            Arrays.fill(encoded, (byte) 0);
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Cannot open the operations credential vault", exception);
        }
    }

    public synchronized String put(Map<String, String> credentials) {
        if (credentials == null) throw OperationsApiException.badRequest("连接认证信息不能为空");
        String reference = UUID.randomUUID().toString();
        byte[] plaintext = null;
        try {
            plaintext = mapper.writeValueAsBytes(credentials);
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(reference.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext);
            OperationsFiles.writePrivate(
                    file(reference),
                    mapper.writeValueAsBytes(
                            Map.of(
                                    "iv",
                                    Base64.getEncoder().encodeToString(iv),
                                    "data",
                                    Base64.getEncoder().encodeToString(ciphertext))));
            return reference;
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException(
                    "Cannot store encrypted connection credentials", exception);
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    public synchronized Map<String, String> get(String reference) {
        Path file = file(reference);
        if (!Files.exists(file)) throw OperationsApiException.notFound("连接凭据不存在，请更新实例连接");
        byte[] plaintext = null;
        try {
            if (Files.isSymbolicLink(file))
                throw new IOException("Vault entry may not be a symbolic link");
            Map<String, String> envelope =
                    mapper.readValue(Files.readAllBytes(file), new TypeReference<>() {});
            byte[] iv = Base64.getDecoder().decode(envelope.get("iv"));
            if (iv.length != 12) throw new IOException("Invalid encrypted credential envelope");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(reference.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            plaintext = cipher.doFinal(Base64.getDecoder().decode(envelope.get("data")));
            return mapper.readValue(plaintext, new TypeReference<>() {});
        } catch (GeneralSecurityException | IOException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Cannot decrypt saved connection credentials", exception);
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    public synchronized void delete(String reference) {
        try {
            Files.deleteIfExists(file(reference));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot remove saved connection credentials", exception);
        }
    }

    private Path file(String reference) {
        if (reference == null
                || !reference.matches(
                        "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw OperationsApiException.badRequest("连接凭据引用无效");
        return directory.resolve(reference + ".json");
    }
}
