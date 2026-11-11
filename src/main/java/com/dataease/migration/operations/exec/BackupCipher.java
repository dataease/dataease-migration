package com.dataease.migration.operations.exec;

import com.dataease.migration.operations.core.OperationsFiles;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.modes.GCMModeCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Authenticated backup streams. Plaintext stays private and is not consumed before tag validation.
 */
final class BackupCipher {
    private static final byte[] MANAGED = "DEOPSBK1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PORTABLE = "DEOPSPB1".getBytes(StandardCharsets.US_ASCII);
    private static final int ITERATIONS = 600_000;
    private static final long MAX_BYTES = 20L * 1024 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCipher() {}

    static boolean portable(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return Arrays.equals(input.readNBytes(8), PORTABLE);
        }
    }

    static void validatePassword(char[] password) {
        if (password == null || password.length < 8 || password.length > 256)
            throw new IllegalArgumentException("备份密码须为 8–256 个字符，请单独妥善保管；遗失后无法恢复。");
    }

    static void encrypt(Path source, Path target, byte[] key) throws Exception {
        byte[] nonce = random(12);
        boolean created = false;
        try (OutputStream output = create(target)) {
            created = true;
            output.write(MANAGED);
            output.write(nonce);
            try (InputStream input = Files.newInputStream(source)) {
                transform(input, output, key, nonce, MANAGED, true);
            }
        } catch (Exception error) {
            if (created) Files.deleteIfExists(target);
            throw error;
        }
    }

    static void decrypt(Path source, Path target, byte[] key) throws Exception {
        boolean created = false;
        try (InputStream input = Files.newInputStream(source)) {
            if (!Arrays.equals(input.readNBytes(8), MANAGED))
                throw new IllegalArgumentException("不是当前平台的受管备份；请通过导入功能上传密码保护的导出包。");
            byte[] nonce = exact(input, 12);
            try (OutputStream output = create(target)) {
                created = true;
                transform(input, output, key, nonce, MANAGED, false);
            }
        } catch (Exception error) {
            if (created) Files.deleteIfExists(target);
            if (error instanceof IllegalArgumentException) throw error;
            throw new IllegalArgumentException("备份解密或完整性验证失败。其他平台的旧备份须在原平台导出为密码保护包后再导入。", error);
        }
    }

    static void export(Path source, Path target, char[] password) throws Exception {
        validatePassword(password);
        byte[] salt = random(16), nonce = random(12), derived = derive(password, salt);
        byte[] header = header(salt, nonce);
        boolean created = false;
        try (OutputStream output = create(target)) {
            created = true;
            output.write(header);
            try (InputStream input = Files.newInputStream(source)) {
                transform(input, output, derived, nonce, header, true);
            }
        } catch (Exception error) {
            if (created) Files.deleteIfExists(target);
            throw error;
        } finally {
            Arrays.fill(derived, (byte) 0);
        }
    }

    static void importFile(Path source, Path target, char[] password) throws Exception {
        validatePassword(password);
        byte[] derived = null;
        boolean created = false;
        try (InputStream input = Files.newInputStream(source)) {
            if (!Arrays.equals(exact(input, 8), PORTABLE)) throw new IOException("header");
            byte[] salt = exact(input, 16), nonce = exact(input, 12);
            derived = derive(password, salt);
            try (OutputStream output = create(target)) {
                created = true;
                transform(input, output, derived, nonce, header(salt, nonce), false);
            }
        } catch (Exception error) {
            if (created) Files.deleteIfExists(target);
            throw new IllegalArgumentException("备份密码不正确，或文件不完整、已被修改；未导入任何备份。", error);
        } finally {
            if (derived != null) Arrays.fill(derived, (byte) 0);
        }
    }

    private static void transform(
            InputStream input,
            OutputStream output,
            byte[] key,
            byte[] nonce,
            byte[] aad,
            boolean encrypt)
            throws IOException, InvalidCipherTextException {
        GCMModeCipher cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(encrypt, new AEADParameters(new KeyParameter(key), 128, nonce, aad));
        byte[] buffer = new byte[64 * 1024], result = new byte[buffer.length + 32];
        long total = 0;
        try {
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_BYTES + 128) throw new IOException("备份超过 20GB 支持上限");
                int written = cipher.processBytes(buffer, 0, count, result, 0);
                output.write(result, 0, written);
            }
            output.write(result, 0, cipher.doFinal(result, 0));
        } finally {
            Arrays.fill(buffer, (byte) 0);
            Arrays.fill(result, (byte) 0);
        }
    }

    private static OutputStream create(Path path) throws IOException {
        OutputStream output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW);
        try {
            OperationsFiles.restrictFile(path);
            return output;
        } catch (IOException error) {
            output.close();
            Files.deleteIfExists(path);
            throw error;
        }
    }

    private static byte[] exact(InputStream input, int length) throws IOException {
        byte[] value = input.readNBytes(length);
        if (value.length != length) throw new EOFException();
        return value;
    }

    private static byte[] derive(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] random(int length) {
        byte[] value = new byte[length];
        RANDOM.nextBytes(value);
        return value;
    }

    private static byte[] header(byte[] salt, byte[] nonce) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(PORTABLE);
        output.write(salt);
        output.write(nonce);
        return output.toByteArray();
    }
}
