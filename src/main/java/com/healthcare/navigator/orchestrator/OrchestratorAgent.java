package com.healthcare.navigator.orchestrator;

import com.healthcare.navigator.agent.Agent;
import com.healthcare.navigator.agent.AgentContext;
import com.healthcare.navigator.agent.AgentResult;
import com.healthcare.navigator.api.dto.PatientSupportResponse;
import com.healthcare.navigator.api.exception.ServiceUnavailableException;
import com.healthcare.navigator.domain.AgentOutcome;
import com.healthcare.navigator.domain.RequestClassification;
import com.healthcare.navigator.observability.MdcPropagator;
import com.healthcare.navigator.safety.SafetyValidator;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Central orchestrator that coordinates the full 8-step request lifecycle:
 * <ol>
 *   <li>Classify the request</li>
 *   <li>Emergency short-circuit for high-risk classifications</li>
 *   <li>Build an ExecutionPlan selecting capable agents</li>
 *   <li>Execute selected agents in parallel with a 10-second batch timeout</li>
 *   <li>Circuit-breaker check before each agent submission</li>
 *   <li>Aggregate all agent results</li>
 *   <li>Validate the aggregated response through safety checks</li>
 *   <li>Emit a structured execution log</li>
 * </ol>
 *
 * <p>Requirements: 3.1–3.5 | Design: §3
 */
@Component
public class OrchestratorAgent {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAgent.class);

    /** Overall timeout (seconds) for waiting on the full batch of agent futures. */
    private static final long BATCH_TIMEOUT_SECONDS = 10L;

    private final List<Agent> agents;
    private final RequestClassifier classifier;
    private final ResponseAggregator aggregator;
    private final SafetyValidator safetyValidator;
    private final Executor virtualThreadExecutor;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    public OrchestratorAgent(
            List<Agent> agents,
            RequestClassifier classifier,
            ResponseAggregator aggregator,
            SafetyValidator safetyValidator,
            @Qualifier("virtualThreadExecutor") Executor virtualThreadExecutor,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.agents = agents;
        this.classifier = classifier;
        this.aggregator = aggregator;
        this.safetyValidator = safetyValidator;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /**
     * Processes a patient support request through the full 8-step orchestration lifecycle.
     *
     * <p>This method never throws except for {@link ServiceUnavailableException} when an
     * emergency classification cannot be safety-validated. All other errors are caught and
     * encoded into the response.
     *
     * @param correlationId unique identifier for this request (used in MDC and logs)
     * @param patientId     the patient's identifier
     * @param question      the patient's natural-language question
     * @return a {@link PatientSupportResponse} — never {@code null}
     * @throws ServiceUnavailableException (HTTP 503) if classification is EMERGENCY_OR_HIGH_RISK
     *                                     and the SafetyValidator is unavailable or throws
     */
    public PatientSupportResponse process(String correlationId, String patientId, String question) {
        MDC.put("correlationId", correlationId);
        try {
            return doProcess(correlationId, patientId, question);
        } finally {
            MDC.remove("correlationId");
        }
    }

    // ── internal lifecycle ────────────────────────────────────────────────────

    private PatientSupportResponse doProcess(String correlationId, String patientId, String question) {

        // ── Step 1: Classify ─────────────────────────────────────────────────
        RequestClassifier.ClassificationResult classificationResult = classifier.classify(question);
        RequestClassification classification = classificationResult.classification();
        log.debug("OrchestratorAgent: correlationId={} classification={} confidence={} reasoning=\"{}\"",
                correlationId, classification, classificationResult.confidence(),
                classificationResult.reasoning());

        // ── Structured log: request received (Req. 11.1) ─────────────────────
        // NEVER log raw patientId — only the SHA-256 hash (Req. 11.5, 12.5)
        logRequestReceived(correlationId, patientId, classification);

        // ── Step 2: Emergency short-circuit ──────────────────────────────────
        if (classification == RequestClassification.EMERGENCY_OR_HIGH_RISK) {
            log.warn("OrchestratorAgent: EMERGENCY_OR_HIGH_RISK detected for correlationId={}", correlationId);
            AggregatedResponse emergencyAggregated = new AggregatedResponse(
                    "Emergency or high-risk situation detected. Please call emergency services immediately.",
                    List.of(),
                    List.of(),
                    List.of("EMERGENCY: This question indicates a potentially life-threatening situation. "
                            + "Please call 911 or your local emergency number immediately."),
                    1.0
            );
            try {
                return safetyValidator.validate(emergencyAggregated, List.of());
            } catch (Exception e) {
                log.error("OrchestratorAgent: SafetyValidator unavailable during emergency routing "
                        + "for correlationId={}: {}", correlationId, e.getMessage(), e);
                throw new ServiceUnavailableException(
                        "Safety validation service unavailable during emergency routing", e);
            }
        }

        // ── Step 3: Build ExecutionPlan ───────────────────────────────────────
        // Temporary context without executionPlan to evaluate canHandle
        AgentContext preplanContext = new AgentContext(correlationId, patientId, question, classification, null);

        List<Agent> selectedAgents = agents.stream()
                .filter(agent -> {
                    try {
                        return agent.canHandle(preplanContext);
                    } catch (Exception e) {
                        log.warn("OrchestratorAgent: canHandle threw for agent={} correlationId={}: {}",
                                agent.getName(), correlationId, e.getMessage());
                        return false;
                    }
                })
                .collect(Collectors.toList());

        List<String> selectedAgentNames = selectedAgents.stream()
                .map(Agent::getName)
                .collect(Collectors.toList());

        ExecutionPlan executionPlan = new ExecutionPlan(
                correlationId, classification, Collections.unmodifiableList(selectedAgentNames), Instant.now());

        log.debug("OrchestratorAgent: correlationId={} selectedAgents={}", correlationId, selectedAgentNames);

        // Full context with execution plan
        AgentContext context = new AgentContext(correlationId, patientId, question, classification, executionPlan);

        // ── Steps 4 & 5: Circuit-breaker check + parallel execution ──────────
        // Track start times and futures keyed by agent name
        Map<String, Long> startTimes = new HashMap<>();
        Map<String, CompletableFuture<AgentResult>> futures = new HashMap<>();
        List<AgentResult> skippedResults = new ArrayList<>();

        for (Agent agent : selectedAgents) {
            String agentName = agent.getName();

            // Step 5 — check circuit breaker BEFORE submitting
            CircuitBreaker cb = circuitBreakerRegistry.find(agentName).orElse(null);
            if (cb != null && cb.getState() == CircuitBreaker.State.OPEN) {
                log.warn("OrchestratorAgent: circuit breaker OPEN for agent={} correlationId={}; skipping",
                        agentName, correlationId);
                skippedResults.add(AgentResult.failure(
                        agentName,
                        "Circuit breaker is OPEN; agent execution skipped"));
                continue;
            }

            // Step 4 — emit agent start log (Req. 11.2), then submit to virtual thread executor
            long agentStartTime = System.currentTimeMillis();
            startTimes.put(agentName, agentStartTime);
            logAgentStart(correlationId, agentName, agentStartTime);

            // Wrap with MdcPropagator so child virtual threads inherit correlationId (Req. 11.2)
            CompletableFuture<AgentResult> future = CompletableFuture.supplyAsync(
                    MdcPropagator.wrap(() -> agent.execute(context)), virtualThreadExecutor);
            futures.put(agentName, future);
        }

        // Wait for all submitted futures with overall batch timeout
        List<AgentResult> agentResults = new ArrayList<>(skippedResults);

        if (!futures.isEmpty()) {
            CompletableFuture<?>[] futureArray = futures.values().toArray(new CompletableFuture[0]);
            try {
                CompletableFuture.allOf(futureArray).get(BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("OrchestratorAgent: batch timeout after {}s for correlationId={}",
                        BATCH_TIMEOUT_SECONDS, correlationId);
                // individual futures checked below
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("OrchestratorAgent: interrupted while waiting for agents correlationId={}",
                        correlationId, e);
            } catch (Exception e) {
                log.error("OrchestratorAgent: unexpected error waiting for agent batch correlationId={}: {}",
                        correlationId, e.getMessage(), e);
            }

            // Collect results — cancel and substitute timeout for any incomplete futures
            for (Map.Entry<String, CompletableFuture<AgentResult>> entry : futures.entrySet()) {
                String agentName = entry.getKey();
                CompletableFuture<AgentResult> future = entry.getValue();
                long durationMs = System.currentTimeMillis() - startTimes.getOrDefault(agentName, 0L);

                if (!future.isDone()) {
                    // Cancel the outstanding future
                    future.cancel(true);
                    log.warn("OrchestratorAgent: agent={} timed out after {}ms correlationId={}",
                            agentName, durationMs, correlationId);
                    AgentResult timeoutResult = AgentResult.timeout(agentName);
                    logAgentComplete(correlationId, agentName, durationMs, timeoutResult.outcome());
                    agentResults.add(timeoutResult);
                } else if (future.isCompletedExceptionally()) {
                    log.warn("OrchestratorAgent: agent={} completed exceptionally after {}ms correlationId={}",
                            agentName, durationMs, correlationId);
                    AgentResult failResult = AgentResult.failure(agentName, "Agent completed exceptionally");
                    logAgentComplete(correlationId, agentName, durationMs, failResult.outcome());
                    agentResults.add(failResult);
                } else {
                    try {
                        AgentResult result = future.getNow(AgentResult.timeout(agentName));
                        logAgentComplete(correlationId, agentName, durationMs, result.outcome());
                        agentResults.add(result);
                    } catch (Exception e) {
                        log.warn("OrchestratorAgent: failed to retrieve result for agent={} correlationId={}: {}",
                                agentName, correlationId, e.getMessage());
                        AgentResult failResult = AgentResult.failure(agentName, "Failed to retrieve agent result: " + e.getMessage());
                        logAgentComplete(correlationId, agentName, durationMs, failResult.outcome());
                        agentResults.add(failResult);
                    }
                }
            }
        }

        // ── Step 6: Aggregate ─────────────────────────────────────────────────
        AggregatedResponse aggregatedResponse = aggregator.aggregate(agentResults);

        // ── Step 7: Validate ──────────────────────────────────────────────────
        PatientSupportResponse response;
        try {
            response = safetyValidator.validate(aggregatedResponse, agentResults);
        } catch (Exception e) {
            log.error("OrchestratorAgent: SafetyValidator threw for correlationId={}: {}",
                    correlationId, e.getMessage(), e);
            // Encode the error gracefully rather than propagating (non-emergency path)
            response = new PatientSupportResponse(
                    correlationId,
                    "We were unable to process your request at this time. Please try again or contact support.",
                    List.of(),
                    List.of(),
                    List.of("Safety validation failed: " + e.getMessage()),
                    0.0
            );
        }

        // ── Step 8: Structured execution log ─────────────────────────────────
        logExecutionRecord(correlationId, classification, selectedAgentNames, agentResults, startTimes);

        return response;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Emits a structured INFO-level log capturing the full execution record for this request.
     *
     * @param correlationId      the request correlation ID
     * @param classification     the determined classification
     * @param selectedAgentNames names of agents included in the execution plan
     * @param agentResults       the results collected from all agents
     * @param startTimes         per-agent start timestamps (epoch millis)
     */
    private void logExecutionRecord(
            String correlationId,
            RequestClassification classification,
            List<String> selectedAgentNames,
            List<AgentResult> agentResults,
            Map<String, Long> startTimes) {

        // Build per-agent summaries for the log
        List<Map<String, Object>> agentEntries = new ArrayList<>();
        for (AgentResult result : agentResults) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("agentName", result.agentName());
            entry.put("outcome", result.outcome());
            long start = startTimes.getOrDefault(result.agentName(), 0L);
            long durationMs = start > 0 ? System.currentTimeMillis() - start : -1L;
            entry.put("durationMs", durationMs);
            if (result.failureReason() != null) {
                entry.put("failureReason", result.failureReason());
            }
            agentEntries.add(entry);
        }

        log.info("OrchestratorAgent execution complete: correlationId={} classification={} "
                        + "selectedAgents={} agentResults={}",
                correlationId,
                classification,
                selectedAgentNames,
                agentEntries);
    }

    // ── structured observability log helpers (Req. 11.1 – 11.4) ─────────────

    /**
     * Emits the "request received" structured log event.
     * <ul>
     *   <li>correlationId — from MDC</li>
     *   <li>timestamp     — current UTC instant</li>
     *   <li>patientIdHash — SHA-256 hex of patientId (NEVER the raw value)</li>
     *   <li>classification — the determined classification</li>
     * </ul>
     * Requirements: 11.1, 11.5
     */
    private void logRequestReceived(String correlationId, String patientId, RequestClassification classification) {
        String patientIdHash = sha256Hex(patientId);
        log.info("event=REQUEST_RECEIVED correlationId={} timestamp={} patientIdHash={} classification={}",
                correlationId, Instant.now(), patientIdHash, classification);
    }

    /**
     * Emits the "agent start" structured log event.
     * Requirements: 11.2
     */
    private void logAgentStart(String correlationId, String agentName, long startTimeEpochMs) {
        log.info("event=AGENT_START correlationId={} agentName={} startTime={}",
                correlationId, agentName, Instant.ofEpochMilli(startTimeEpochMs));
    }

    /**
     * Emits the "agent complete" structured log event.
     * Requirements: 11.2
     */
    private void logAgentComplete(String correlationId, String agentName, long durationMs, AgentOutcome outcome) {
        log.info("event=AGENT_COMPLETE correlationId={} agentName={} durationMs={} outcome={}",
                correlationId, agentName, durationMs, outcome);
    }

    /**
     * Computes the SHA-256 hex digest of the input string.
     * Returns {@code "UNKNOWN"} on any failure so that logging never throws.
     * The hash is one-way — the original patientId cannot be recovered from the log.
     */
    private static String sha256Hex(String input) {
        if (input == null || input.isBlank()) {
            return "EMPTY";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available on any standard JRE; this branch is defensive only
            return "HASH_ERROR";
        }
    }
}
