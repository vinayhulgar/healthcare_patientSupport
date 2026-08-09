package com.healthcare.navigator.agent;

import com.healthcare.navigator.domain.RequestClassification;
import com.healthcare.navigator.orchestrator.ExecutionPlan;

/**
 * Carries all context needed by a sub-agent to process a patient request.
 *
 * @param correlationId  unique ID for the request (used in logs)
 * @param patientId      the patient's identifier
 * @param question       the patient's natural-language question
 * @param classification the query classification determined by the orchestrator
 * @param executionPlan  the orchestrator's execution plan for this request
 */
public record AgentContext(
        String correlationId,
        String patientId,
        String question,
        RequestClassification classification,
        ExecutionPlan executionPlan
) {}
