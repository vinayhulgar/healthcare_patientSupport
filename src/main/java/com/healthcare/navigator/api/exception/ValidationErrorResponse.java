package com.healthcare.navigator.api.exception;

import java.util.List;

/**
 * Response body returned for HTTP 400 validation failures.
 *
 * <p>Each entry in {@code fieldErrors} identifies the violating field and the
 * constraint message, allowing callers to correct individual request fields
 * without guessing which constraint was violated.
 *
 * @param message     top-level summary, e.g. "Validation failed"
 * @param fieldErrors one entry per failing field constraint
 */
public record ValidationErrorResponse(
        String message,
        List<FieldError> fieldErrors
) {

    /**
     * A single field-level constraint violation.
     *
     * @param field   the request field that failed validation
     * @param message the human-readable constraint violation message
     */
    public record FieldError(
            String field,
            String message
    ) {}
}
