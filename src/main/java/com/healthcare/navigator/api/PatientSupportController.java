package com.healthcare.navigator.api;

import com.healthcare.navigator.api.dto.PatientQueryRequest;
import com.healthcare.navigator.api.dto.PatientSupportResponse;
import com.healthcare.navigator.orchestrator.OrchestratorAgent;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing the patient support query endpoint.
 *
 * <p>A single {@code POST /api/v1/patient-support/query} endpoint accepts a
 * {@link PatientQueryRequest}, delegates entirely to {@link OrchestratorAgent},
 * and returns the resulting {@link PatientSupportResponse} with HTTP 200.
 *
 * <p>Responsibilities deliberately <em>not</em> handled here:
 * <ul>
 *   <li>Correlation ID generation and {@code X-Correlation-ID} response header —
 *       managed by {@code CorrelationIdFilter} (task 18.1)</li>
 *   <li>Exception mapping — handled by {@code GlobalExceptionHandler}</li>
 *   <li>Authentication / authorisation — handled by {@code SecurityConfig}</li>
 * </ul>
 *
 * <p>Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6 | Design: §2.2
 */
@RestController
@RequestMapping("/api/v1/patient-support")
public class PatientSupportController {

    private final OrchestratorAgent orchestratorAgent;

    public PatientSupportController(OrchestratorAgent orchestratorAgent) {
        this.orchestratorAgent = orchestratorAgent;
    }

    /**
     * Accepts a patient healthcare query and returns a structured, safety-validated response.
     *
     * <p>Jakarta Bean Validation is applied to the request body before any agent work
     * begins. Constraint violations result in HTTP 400 via {@code GlobalExceptionHandler}
     * without ever reaching the orchestrator.
     *
     * <p>The {@code X-Correlation-ID} response header is set by {@code CorrelationIdFilter},
     * not here. The correlation ID used for the orchestrator call is read from MDC (populated
     * by the filter when present); if the MDC value is absent (e.g. in tests without the
     * filter), a fresh UUID is generated as a fallback so the call always has a valid ID.
     *
     * @param request validated request body containing {@code patientId} and {@code question}
     * @return HTTP 200 with the fully assembled {@link PatientSupportResponse}
     */
    @PostMapping("/query")
    public ResponseEntity<PatientSupportResponse> query(
            @Valid @RequestBody PatientQueryRequest request) {

        // Prefer the correlation ID placed in MDC by CorrelationIdFilter; fall back to a
        // fresh UUID so the orchestrator always receives a non-null, unique ID.
        String correlationId = MDC.get("correlationId");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        PatientSupportResponse response = orchestratorAgent.process(
                correlationId,
                request.patientId(),
                request.question());

        return ResponseEntity.ok(response);
    }
}
