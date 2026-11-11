package com.dataease.migration.operations.auth;

import com.dataease.migration.operations.core.OperationsApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public final class OperationsAuthentication implements WebMvcConfigurer, HandlerInterceptor {
    private final OperationsAuthService auth;
    private final Set<String> allowedOrigins;

    public OperationsAuthentication(
            OperationsAuthService auth,
            @Value("${operations.allowed-origins:}") String allowedOrigins) {
        this.auth = auth;
        this.allowedOrigins =
                Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        boolean read = Set.of("GET", "HEAD").contains(request.getMethod());
        if (!read) requireSameOrigin(request);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals("/api/ops/session") && request.getMethod().equals("GET")) return true;
        if ((path.equals("/api/ops/session/login") || path.equals("/api/ops/session/setup"))
                && request.getMethod().equals("POST")) return true;
        OperationsAuthService.Session session =
                auth.session(request)
                        .orElseThrow(
                                () ->
                                        new OperationsApiException(
                                                HttpStatus.UNAUTHORIZED, "请先登录管理员账号"));
        if (!read) {
            String token = request.getHeader("X-CSRF-Token");
            if (token == null
                    || !MessageDigest.isEqual(
                            token.getBytes(StandardCharsets.UTF_8),
                            session.csrfToken().getBytes(StandardCharsets.UTF_8)))
                throw new OperationsApiException(HttpStatus.FORBIDDEN, "会话校验失败，请刷新后重试");
        }
        request.setAttribute("operations.admin", session.username());
        return true;
    }

    private void requireSameOrigin(HttpServletRequest request) {
        if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site")))
            throw new OperationsApiException(HttpStatus.FORBIDDEN, "不允许跨站操作请求");
        String origin = request.getHeader("Origin");
        if (origin == null)
            return; // Command-line API clients authenticate with the session and CSRF token.
        if (allowedOrigins.contains(origin)) return;
        try {
            URI uri = URI.create(origin);
            int requestPort = request.getServerPort();
            int originPort =
                    uri.getPort() < 0
                            ? ("https".equals(uri.getScheme()) ? 443 : 80)
                            : uri.getPort();
            if (uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    || !request.getScheme().equalsIgnoreCase(uri.getScheme())
                    || !request.getServerName().equalsIgnoreCase(uri.getHost())
                    || requestPort != originPort) throw new IllegalArgumentException();
        } catch (RuntimeException exception) {
            throw new OperationsApiException(HttpStatus.FORBIDDEN, "请求来源与运维平台不一致");
        }
    }
}
