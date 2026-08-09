package com.healthcare.navigator.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Incoming request DTO for a patient support query.
 *
 * @param patientId the patient's identifier (1–64 characters, must not be blank)
 * @param question  the patient's natural-language question (1–2000 characters, must not be blank)
 */
public record PatientQueryRequest(
        @NotBlank
        @Size(min = 1, max = 64)
        String patientId,

        @NotBlank
        @Size(min = 1, max = 2000)
        String question
) {}
