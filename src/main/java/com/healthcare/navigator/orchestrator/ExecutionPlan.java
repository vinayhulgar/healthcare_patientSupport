package com.healthcare.navigator.orchestrator;

import com.healthcare.navigator.domain.RequestClassification;

import java.time.Instant;
import java.util.List;

/**
 * Represents the orchestrator's execution plan for a given request,
 * capturing which agents were selected to handle the classified query.
 *
 * @param correlationId    unique ID for the request
 * @param classification   the query classification that drove agent selection
 * @param selectedAgents   names of agents selected to handle this request
 * @param createdAt        the instant at which the plan was created
 */
public record ExecutionPlan(
        String correlationId,
        RequestClassification classification,
        List<String> selectedAgents,
        Instant createdAt
) {}
