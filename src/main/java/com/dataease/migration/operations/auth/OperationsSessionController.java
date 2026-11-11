package com.dataease.migration.operations.auth;

import com.dataease.migration.operations.core.OperationsApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/ops/session")
public final class OperationsSessionController {
    private final OperationsAuthService auth;

    public OperationsSessionController(OperationsAuthService auth) {
        this.auth = auth;
    }

    @GetMapping
    public Map<String, Object> current(HttpServletRequest request) {
        return auth.session(request)
                .map(this::details)
                .orElseGet(
                        () ->
                                Map.of(
                                        "authenticated",
                                        false,
                                        "setupRequired",
                                        auth.setupRequired(),
                                        "username",
                                        "admin"));
    }

    @PostMapping(value = "/setup", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setup(
            @RequestBody Map<String, String> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        OperationsAuthService.Session session = auth.setup(body.get("password"), request);
        writeCookie(response, request, session.token(), Duration.ofHours(8));
        return details(session);
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> login(
            @RequestBody Map<String, String> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        String username = body.getOrDefault("username", "admin");
        OperationsAuthService.Session session = auth.login(username, body.get("password"), request);
        auth.logout(request); // Rotate an existing browser session when signing in again.
        writeCookie(response, request, session.token(), Duration.ofHours(8));
        return details(session);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        auth.logout(request);
        writeCookie(response, request, "", Duration.ZERO);
        return Map.of("authenticated", false, "setupRequired", false);
    }

    @PutMapping(value = "/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> password(
            @RequestBody Map<String, String> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (!body.containsKey("currentPassword") || !body.containsKey("newPassword"))
            throw OperationsApiException.badRequest("请填写当前密码和新密码");
        OperationsAuthService.Session session =
                auth.changePassword(body.get("currentPassword"), body.get("newPassword"));
        writeCookie(response, request, session.token(), Duration.ofHours(8));
        return details(session);
    }

    private Map<String, Object> details(OperationsAuthService.Session session) {
        return Map.of(
                "authenticated",
                true,
                "setupRequired",
                false,
                "username",
                session.username(),
                "csrfToken",
                session.csrfToken());
    }

    private static void writeCookie(
            HttpServletResponse response, HttpServletRequest request, String token, Duration age) {
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                ResponseCookie.from(OperationsAuthService.COOKIE_NAME, token)
                        .path("/")
                        .httpOnly(true)
                        .secure(request.isSecure())
                        .sameSite("Strict")
                        .maxAge(age)
                        .build()
                        .toString());
    }
}
