package com.healthcare.navigator.agent;

import com.healthcare.navigator.domain.AgentOutcome;
import com.healthcare.navigator.domain.SourceCitation;

import java.util.Collections;
import java.util.List;

/**
 * The result produced by a sub-agent after processing an {@link AgentContext}.
 *
 * <p>Use the static factory methods ({@link #success}, {@link #failure}, {@link #timeout})
 * to construct instances rather than the canonical record constructor.
 *
 * @param agentName        the registered name of the agent that produced this result
 * @param outcome          {@code SUCCESS}, {@code FAILURE}, or {@code TIMEOUT}
 * @param structuredOutput the typed payload returned by the agent (null on failure/timeout)
 * @param sources          source citations backing the agent's output
 * @param warnings         non-fatal warnings produced during execution
 * @param confidence       confidence score in the range [0.0, 1.0]
 * @param failureReason    human-readable failure description (null on success)
 */
public record AgentResult(
        String agentName,
        AgentOutcome outcome,
        Object structuredOutput,
        List<SourceCitation> sources,
        List<String> warnings,
        double confidence,
        String failureReason
) {

    /**
     * Creates a successful {@link AgentResult}.
     *
     * @param agentName        the name of the agent
     * @param structuredOutput the typed payload returned by the agent
     * @param sources          source citations backing the output
     * @param warnings         any non-fatal warnings
     * @param confidence       confidence score in [0.0, 1.0]
     * @return an {@link AgentResult} with {@code outcome = SUCCESS} and {@code failureReason = null}
     */
    public static AgentResult success(
            String agentName,
            Object structuredOutput,
            List<SourceCitation> sources,
            List<String> warnings,
            double confidence) {
        return new AgentResult(agentName, AgentOutcome.SUCCESS, structuredOutput,
                sources, warnings, confidence, null);
    }

    /**
     * Creates a failed {@link AgentResult}.
     *
     * @param agentName     the name of the agent
     * @param failureReason a human-readable description of the failure
     * @return an {@link AgentResult} with {@code outcome = FAILURE}, empty sources/warnings,
     *         {@code confidence = 0.0}, and {@code structuredOutput = null}
     */
    public static AgentResult failure(String agentName, String failureReason) {
        return new AgentResult(agentName, AgentOutcome.FAILURE, null,
                Collections.emptyList(), Collections.emptyList(), 0.0, failureReason);
    }

    /**
     * Creates a timed-out {@link AgentResult}.
     *
     * @param agentName the name of the agent that timed out
     * @return an {@link AgentResult} with {@code outcome = TIMEOUT},
     *         {@code failureReason = "Agent timed out"}, empty sources/warnings,
     *         {@code confidence = 0.0}, and {@code structuredOutput = null}
     */
    public static AgentResult timeout(String agentName) {
        return new AgentResult(agentName, AgentOutcome.TIMEOUT, null,
                Collections.emptyList(), Collections.emptyList(), 0.0, "Agent timed out");
    }
}
