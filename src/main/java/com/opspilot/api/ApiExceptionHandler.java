package com.opspilot.api;

import com.opspilot.config.JsonCodec;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), null);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(ConflictException e) {
        return body(HttpStatus.CONFLICT, "CONFLICT", e.getMessage(), e.getCurrentStatus());
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .findFirst().orElse("参数错误");
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message, null);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String error,
                                                            String message, String currentStatus) {
        return ResponseEntity.status(status)
                .body(JsonCodec.fields("error", error, "message", message, "current_status", currentStatus));
    }
}
