package com.blog.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public ApiException(int status, String message) {
        this(HttpStatus.valueOf(status), message);
    }

    public HttpStatus getStatus() {
        return status;
    }
}
