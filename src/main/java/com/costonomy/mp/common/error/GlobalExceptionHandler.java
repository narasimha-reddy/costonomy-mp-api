package com.costonomy.mp.common.error;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.common.web.RequestContext;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns every exception into the {@code { data, error, meta }} envelope.
 *
 * <p>The single rule this class exists to enforce: **nothing about our internals
 * reaches the client.** No stack trace, no SQL, no constraint name, no provider
 * payload (doc 04 §2, doc 09 §16). Anything not explicitly mapped below becomes
 * a generic {@code INTERNAL_ERROR} with the request ID attached, and the detail
 * goes to the log where support can find it by that ID.
 *
 * <p>Log levels are chosen deliberately. A {@link BusinessException} is the
 * system working — an expired order, an exceeded credit limit — so it logs at
 * WARN without a trace. ERROR is reserved for faults, so that an ERROR in the
 * log always means something actually broke.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ── Expected business failures ───────────────────────────────────────

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NotFoundException ex) {
        // Logged with the entity and id the client is NOT told, so a tenant
        // probing for another org's records is visible to us but opaque to them.
        log.warn("Not found or out of scope: entity={} id={}", ex.entity(), ex.id());
        return respond(ex.code(), ex.getMessage(), ex.details());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        log.warn("Business rule violated: code={} message={}", ex.code(), ex.getMessage());
        return respond(ex.code(), ex.getMessage(), ex.details());
    }

    // ── Validation ───────────────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidBody(MethodArgumentNotValidException ex) {
        // LinkedHashMap: field order follows declaration order, so the client
        // can surface the first violation and have it be the topmost field.
        Map<String, Object> fields = new LinkedHashMap<>();
        for (var error : ex.getBindingResult().getAllErrors()) {
            String field = error instanceof FieldError fe ? fe.getField() : error.getObjectName();
            fields.putIfAbsent(field, error.getDefaultMessage());
        }
        log.warn("Validation failed: {}", fields);
        return respond(ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.defaultMessage(),
                Map.of("fields", fields));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v ->
                fields.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        log.warn("Constraint violation: {}", fields);
        return respond(ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.defaultMessage(),
                Map.of("fields", fields));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class,
    })
    public ResponseEntity<ApiResponse<Void>> handleMalformed(Exception ex) {
        // The parser's message can quote the request body, which may contain an
        // OTP or a token, so it is logged but never returned.
        log.warn("Malformed request: {}", ex.getMessage());
        return respond(ErrorCode.MALFORMED_REQUEST, ErrorCode.MALFORMED_REQUEST.defaultMessage(), Map.of());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandler(NoHandlerFoundException ex) {
        log.warn("No handler for {} {}", ex.getHttpMethod(), ex.getRequestURL());
        return respond(ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND.defaultMessage(), Map.of());
    }

    // ── Security ─────────────────────────────────────────────────────────

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());
        return respond(ErrorCode.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED.defaultMessage(), Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return respond(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage(), Map.of());
    }

    // ── Persistence ──────────────────────────────────────────────────────

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        // Two actors raced on a versioned aggregate and this one lost. Expected
        // under concurrency — the client refetches and retries.
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        return respond(ErrorCode.CONCURRENT_MODIFICATION,
                ErrorCode.CONCURRENT_MODIFICATION.defaultMessage(), Map.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        // Constraint names describe our schema, so the client gets a generic
        // conflict. ERROR, not WARN: a constraint reaching the database means a
        // service-level check is missing, and that is a defect worth finding.
        log.error("Data integrity violation", ex);
        return respond(ErrorCode.CONCURRENT_MODIFICATION,
                "That conflicts with existing data. Please refresh and try again.", Map.of());
    }

    // ── Anything else ────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception [requestId={}]", RequestContext.requestId(), ex);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), Map.of());
    }

    private static ResponseEntity<ApiResponse<Void>> respond(
            ErrorCode code, String message, Map<String, Object> details) {

        HttpStatus status = code.status();
        var error = new ApiResponse.ApiError(code.name(), message, details);
        return ResponseEntity.status(status)
                .body(ApiResponse.failure(error, RequestContext.requestId()));
    }
}
