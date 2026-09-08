package com.healthcare.navigator.api.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Centralised exception handler for the REST API layer.
 *
 * <p>Handles three categories of exceptions:
 * <ul>
 *   <li>Jakarta Bean Validation failures (HTTP 400) — returns field-level error details</li>
 *   <li>{@link ServiceUnavailableException} (HTTP 503) — Safety Validator unreachable
 *       during emergency routing; no body details exposed</li>
 *   <li>All other exceptions (HTTP 500) — generic message; full error logged with
 *       {@code correlationId} from MDC; no internal details in the response body</li>
 * </ul>
 *
 * <p>Requirements: 1.3, 1.4, 1.5, 1.9, 2.3 | Design: §2.2, §Error Handling
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ── HTTP 400: Jakarta Bean Validation failures ────────────────────────────

    /**
     * Handles {@link MethodArgumentNotValidException} thrown when a {@code @Valid}-annotated
     * request body fails one or more Jakarta Bean Validation constraints.
     *
     * <p>Returns HTTP 400 with a {@link ValidationErrorResponse} body that names each
     * violating field and its constraint message.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex) {

        List<ValidationErrorResponse.FieldError> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> new ValidationErrorResponse.FieldError(
                        fe.getField(),
                        fe.getDefaultMessage()))
                .toList();

        ValidationErrorResponse body = new ValidationErrorResponse("Validation failed", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // ── HTTP 503: Safety Validator unreachable on emergency query ─────────────

    /**
     * Handles {@link ServiceUnavailableException} thrown by the orchestrator when the
     * Safety Validator is unreachable during an {@code EMERGENCY_OR_HIGH_RISK} routing.
     *
     * <p>Returns HTTP 503 with no body details to avoid leaking internal state.
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<Void> handleServiceUnavailableException(
            ServiceUnavailableException ex) {

        String correlationId = MDC.get("correlationId");
        log.error("Service unavailable: correlationId={} message={}", correlationId, ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    // ── HTTP 500: All other unhandled exceptions ──────────────────────────────

    /**
     * Catch-all handler for any exception not matched by a more specific handler.
     *
     * <p>Logs the full stack trace together with the {@code correlationId} from MDC
     * for debugging. Returns HTTP 500 with a generic, non-revealing message.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleGenericException(Exception ex) {
        String correlationId = MDC.get("correlationId");
        log.error("Unhandled exception: correlationId={}", correlationId, ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("An internal error occurred. Please try again later.");
    }
}
