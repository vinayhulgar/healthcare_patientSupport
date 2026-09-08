package com.healthcare.navigator.safety;

import com.healthcare.navigator.agent.AgentResult;
import com.healthcare.navigator.api.dto.PatientSupportResponse;
import com.healthcare.navigator.orchestrator.AggregatedResponse;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * Stub implementation — full implementation in task 14.4.
 * Validates the aggregated response through safety and grounding checks.
 */
@Component
public class SafetyValidator {
    public PatientSupportResponse validate(AggregatedResponse aggregated, List<AgentResult> agentResults) {
        // Stub: passes through until task 14.4 is implemented
        return new PatientSupportResponse(
            null, aggregated.answer(), aggregated.agentsUsed(),
            aggregated.sources(), aggregated.warnings(), aggregated.confidence()
        );
    }
}
