package com.skillpulse.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.skillpulse.chat.ChatLimitException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        for (FieldError item : ex.getBindingResult().getFieldErrors())
            if (!fields.containsKey(item.getField())) fields.put(item.getField(), item.getDefaultMessage());
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Please check the submitted fields.", request, fields);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> badRequest(IllegalArgumentException ex, HttpServletRequest request) {
        String message = ex.getMessage() == null ? "The request could not be completed." : ex.getMessage();
        return response(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message, request, null);
    }

    @ExceptionHandler(ChatLimitException.class)
    public ResponseEntity<ApiErrorResponse> chatLimit(ChatLimitException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatus());
        if (status == null) status = HttpStatus.TOO_MANY_REQUESTS;
        ApiErrorResponse body = new ApiErrorResponse(status.value(), status == HttpStatus.TOO_MANY_REQUESTS ? "RATE_LIMITED" : "BUSY",
                ex.getMessage(), request.getRequestURI(), null);
        return ResponseEntity.status(status).header("Retry-After", String.valueOf(ex.getRetryAfterSeconds())).body(body);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> wrongMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "This request method is not supported here.", request, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> authentication(AuthenticationException ex, HttpServletRequest request) {
        return response(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", "Email or password is incorrect.", request, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> forbidden(AccessDeniedException ex, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied.", request, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> conflict(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Database constraint violation on {}", request.getRequestURI(), ex);
        return response(HttpStatus.CONFLICT, "DATA_CONFLICT", "The request conflicts with existing data.", request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled API error on {}", request.getRequestURI(), ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Please try again.", request, null);
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String message,
                                                      HttpServletRequest request, Map<String, String> fields) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                status.value(), code, message, request.getRequestURI(), fields));
    }
}
