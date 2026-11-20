package com.windfarm.faultref.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex, HttpServletRequest req) {
        return ResponseEntity.status(ex.getStatus())
                .body(body(ex.getStatus(), ex.getCode(), ex.getMessage(), req, null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex,
                                                                HttpServletRequest req) {
        List<Map<String, String>> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::fieldError)
                .toList();
        return ResponseEntity.badRequest()
                .body(body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", req, details));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleBadInput(Exception ex, HttpServletRequest req) {
        return ResponseEntity.badRequest()
                .body(body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), req, null));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleDenied(AccessDeniedException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(body(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient role for this operation", req, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                        "Unexpected server error", req, null));
    }

    private Map<String, String> fieldError(FieldError fe) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("field", fe.getField());
        m.put("message", fe.getDefaultMessage());
        return m;
    }

    private Map<String, Object> body(HttpStatus status, String code, String message,
                                     HttpServletRequest req, Object details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("code", code);
        body.put("message", message);
        body.put("path", req.getRequestURI());
        if (details != null) {
            body.put("details", details);
        }
        return body;
    }
}
