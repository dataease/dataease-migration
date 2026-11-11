package com.dataease.migration.operations.auth;

import com.dataease.migration.operations.core.OperationsApiException;
import com.dataease.migration.operations.core.OperationsFiles;
import com.dataease.migration.operations.core.OperationsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

@Service
public final class OperationsAuthService {
    public static final String COOKIE_NAME = "OPS_SESSION";
    private static final int ITERATIONS = 600_000;
    private static final long HARD_TIMEOUT = Duration.ofHours(8).toMillis();
    private static final long IDLE_TIMEOUT = Duration.ofMinutes(30).toMillis();
    private static final long ATTEMPT_WINDOW = Duration.ofMinutes(15).toMillis();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path accountFile;
    private final ObjectMapper mapper;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private volatile Account account;

    public OperationsAuthService(
            OperationsProperties properties,
            ObjectMapper mapper,
            @Value("${operations.admin-password:${OPERATIONS_ADMIN_PASSWORD:}}")
                    String initialPassword) {
        this.accountFile = properties.dataDirectory().resolve("administrator.json");
        this.mapper = mapper;
        if (Files.exists(accountFile)) {
            try {
                if (Files.isSymbolicLink(accountFile))
                    throw new IOException("Administrator file may not be a symbolic link");
                account = mapper.readValue(Files.readAllBytes(accountFile), Account.class);
                if (account == null
                        || !"admin".equals(account.username())
                        || account.iterations() < 100_000
                        || account.iterations() > 2_000_000)
                    throw new IOException("Invalid administrator record");
                OperationsFiles.restrictFile(accountFile);
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot load administrator account", exception);
            }
        } else if (initialPassword != null && !initialPassword.isEmpty()) {
            savePassword(initialPassword);
        }
    }

    public boolean setupRequired() {
        return account == null;
    }

    public synchronized Session setup(String password, HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())
                || !isLoopback(request.getServerName())
                || hasRemoteForwarding(request))
            throw new OperationsApiException(
                    HttpStatus.FORBIDDEN, "首次初始化仅允许从运维服务本机的 localhost 地址访问");
        if (account != null) throw OperationsApiException.conflict("管理员已初始化，请直接登录");
        savePassword(password);
        return newSession();
    }

    public synchronized Session login(
            String username, String password, HttpServletRequest request) {
        if (account == null) throw OperationsApiException.conflict("请先在运维服务本机初始化管理员密码");
        String address = request.getRemoteAddr();
        long now = System.currentTimeMillis();
        Attempts recent = attempts.get(address);
        if (recent != null && now - recent.startedAt < ATTEMPT_WINDOW && recent.count >= 5)
            throw new OperationsApiException(HttpStatus.TOO_MANY_REQUESTS, "登录失败次数过多，请稍后重试");
        boolean valid = "admin".equals(username) && matches(password, account);
        if (!valid) {
            attempts.compute(
                    address,
                    (key, old) ->
                            old == null || now - old.startedAt >= ATTEMPT_WINDOW
                                    ? new Attempts(now, 1)
                                    : new Attempts(old.startedAt, old.count + 1));
            if (attempts.size() > 1_000)
                attempts.entrySet()
                        .removeIf(entry -> now - entry.getValue().startedAt >= ATTEMPT_WINDOW);
            throw new OperationsApiException(HttpStatus.UNAUTHORIZED, "管理员账号或密码错误");
        }
        attempts.remove(address);
        return newSession();
    }

    public Optional<Session> session(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        for (Cookie cookie : cookies) {
            if (!COOKIE_NAME.equals(cookie.getName())) continue;
            Session session = sessions.get(cookie.getValue());
            if (session == null) return Optional.empty();
            long now = System.currentTimeMillis();
            if (now - session.createdAt > HARD_TIMEOUT || now - session.lastSeen > IDLE_TIMEOUT) {
                sessions.remove(session.token);
                return Optional.empty();
            }
            session.lastSeen = now;
            return Optional.of(session);
        }
        return Optional.empty();
    }

    public void logout(HttpServletRequest request) {
        session(request).ifPresent(session -> sessions.remove(session.token));
    }

    public synchronized Session changePassword(String currentPassword, String newPassword) {
        if (!matches(currentPassword, account))
            throw new OperationsApiException(HttpStatus.UNAUTHORIZED, "当前密码不正确");
        validatePassword(newPassword);
        if (matches(newPassword, account)) throw OperationsApiException.badRequest("新密码不能与当前密码相同");
        savePassword(newPassword);
        sessions.clear();
        return newSession();
    }

    private Session newSession() {
        long now = System.currentTimeMillis();
        sessions.entrySet()
                .removeIf(
                        entry ->
                                now - entry.getValue().createdAt > HARD_TIMEOUT
                                        || now - entry.getValue().lastSeen > IDLE_TIMEOUT);
        if (sessions.size() >= 100)
            sessions.values().stream()
                    .min(Comparator.comparingLong(session -> session.createdAt))
                    .ifPresent(old -> sessions.remove(old.token));
        Session result = new Session(randomToken(), randomToken(), now);
        sessions.put(result.token, result);
        return result;
    }

    private void savePassword(String password) {
        validatePassword(password);
        byte[] salt = new byte[32];
        RANDOM.nextBytes(salt);
        Account replacement =
                new Account(
                        "admin",
                        Base64.getEncoder().encodeToString(salt),
                        Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS)),
                        ITERATIONS);
        try {
            OperationsFiles.writePrivate(accountFile, mapper.writeValueAsBytes(replacement));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot persist administrator account", exception);
        }
        account = replacement;
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 256)
            throw OperationsApiException.badRequest("管理员密码需为 8–256 个字符");
    }

    private static boolean matches(String password, Account stored) {
        if (stored == null || password == null || password.length() > 256) return false;
        try {
            byte[] derived =
                    derive(
                            password,
                            Base64.getDecoder().decode(stored.salt()),
                            stored.iterations());
            try {
                return MessageDigest.isEqual(derived, Base64.getDecoder().decode(stored.hash()));
            } finally {
                Arrays.fill(derived, (byte) 0);
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid administrator hash", exception);
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec specification = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification)
                    .getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Password hashing is unavailable", exception);
        } finally {
            specification.clearPassword();
        }
    }

    private static String randomToken() {
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    private static boolean isLoopback(String address) {
        return address != null
                && (address.equalsIgnoreCase("localhost")
                        || address.startsWith("127.")
                        || address.equals("::1")
                        || address.equals("0:0:0:0:0:0:0:1"));
    }

    private static boolean hasRemoteForwarding(HttpServletRequest request) {
        // Public reverse-proxy access must not become a local first-run bootstrap.
        String forwarded = request.getHeader("Forwarded");
        if (forwarded != null && !forwarded.isBlank()) return true;
        for (String header : new String[] {"X-Forwarded-For", "X-Real-IP"}) {
            String value = request.getHeader(header);
            if (value != null
                    && Arrays.stream(value.split(","))
                            .anyMatch(address -> !isLoopback(address.trim()))) return true;
        }
        return false;
    }

    public record Account(String username, String salt, String hash, int iterations) {}

    private record Attempts(long startedAt, int count) {}

    public static final class Session {
        private final String token;
        private final String csrfToken;
        private final long createdAt;
        private volatile long lastSeen;

        private Session(String token, String csrfToken, long time) {
            this.token = token;
            this.csrfToken = csrfToken;
            this.createdAt = time;
            this.lastSeen = time;
        }

        public String token() {
            return token;
        }

        public String csrfToken() {
            return csrfToken;
        }

        public String username() {
            return "admin";
        }
    }
}
