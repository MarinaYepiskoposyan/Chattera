package com.chattera.chat.error;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.chattera.chat.service.exception.ChatDomainException;
import com.chattera.domain.error.ApiError;

/**
 * Maps validation failures and chat-service's domain exceptions to the
 * shared {@link ApiError} response shape (common-domain), matching
 * profile-service's error contract.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.badRequest().body(ApiError.of("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(ChatDomainException.class)
    public ResponseEntity<ApiError> handleDomain(ChatDomainException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ApiError.of(ex.getCode(), ex.getMessage()));
    }
}
