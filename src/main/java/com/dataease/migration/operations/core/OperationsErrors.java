package com.dataease.migration.operations.core;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
public final class OperationsErrors {
    private static final Logger LOGGER = LoggerFactory.getLogger(OperationsErrors.class);

    @ExceptionHandler(OperationsApiException.class)
    public ResponseEntity<Map<String, String>> operations(OperationsApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException exception) {
        String message =
                exception.getStatusCode().is5xxServerError()
                        ? "服务暂时不可用，请查看运维日志"
                        : exception.getReason() == null ? "请求未完成" : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", message));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        BindException.class,
        HttpMessageNotReadableException.class
    })
    public ResponseEntity<Map<String, String>> validation(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("message", "请求参数不完整或格式不正确"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> argument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", "请求参数无效"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "接口不存在"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> method(
            HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of("message", "此接口不支持该请求方法"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> contentType(
            HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Map.of("message", "请使用支持的请求内容类型"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> internal(
            Exception exception, HttpServletRequest request) {
        // Exception messages may include a remote command or JDBC URL. Keep them out of HTTP
        // responses and this audit line.
        LOGGER.error(
                "Operations request failed: {} {} ({})",
                request.getMethod(),
                request.getRequestURI(),
                exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "操作失败，请查看服务端日志并核对连接与任务状态"));
    }
}
