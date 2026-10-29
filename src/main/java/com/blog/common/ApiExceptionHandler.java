package com.blog.common;

import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.authz.UnauthenticatedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Result> api(ApiException exception) {
        return failure(exception.getStatus(), exception.getMessage());
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<Result> unauthenticated() {
        return failure(HttpStatus.UNAUTHORIZED, "请先登录");
    }

    @ExceptionHandler(AuthorizationException.class)
    public ResponseEntity<Result> forbidden() {
        return failure(HttpStatus.FORBIDDEN, "权限不足");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class,
            ConstraintViolationException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result> invalidInput() {
        return failure(HttpStatus.BAD_REQUEST, "请求参数不合法");
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Result> notFound() {
        return failure(HttpStatus.NOT_FOUND, "资源不存在");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result> tooLarge() {
        return failure(HttpStatus.PAYLOAD_TOO_LARGE, "请求内容过大");
    }

    @ExceptionHandler({HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class})
    public ResponseEntity<Result> protocol(Exception exception) {
        ErrorResponse response = (ErrorResponse) exception;
        return ResponseEntity.status(response.getStatusCode()).body(Result.failed("请求方法或内容类型不支持", null));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result> unavailable() {
        return failure(HttpStatus.SERVICE_UNAVAILABLE, "数据服务暂时不可用");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> unexpected(Exception exception) {
        // Neither database details nor supplied credentials belong in API error responses.
        return failure(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");
    }

    private ResponseEntity<Result> failure(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Result.failed(message, null));
    }
}
