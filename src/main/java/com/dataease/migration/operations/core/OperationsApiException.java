package com.dataease.migration.operations.core;

import org.springframework.http.HttpStatus;

public final class OperationsApiException extends RuntimeException {
    private final HttpStatus status;

    public OperationsApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static OperationsApiException badRequest(String message) {
        return new OperationsApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static OperationsApiException notFound(String message) {
        return new OperationsApiException(HttpStatus.NOT_FOUND, message);
    }

    public static OperationsApiException conflict(String message) {
        return new OperationsApiException(HttpStatus.CONFLICT, message);
    }
}
