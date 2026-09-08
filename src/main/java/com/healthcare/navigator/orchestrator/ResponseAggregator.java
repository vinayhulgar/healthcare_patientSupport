package com.healthcare.navigator.orchestrator;

import com.healthcare.navigator.agent.AgentResult;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * Stub implementation — full implementation in task 13.1.
 * Combines sub-agent results into a single AggregatedResponse.
 */
@Component
public class ResponseAggregator {
    public AggregatedResponse aggregate(List<AgentResult> results) {
        // Stub: returns empty aggregation until task 13.1 is implemented
        return new AggregatedResponse("", List.of(), List.of(), List.of(), 0.0);
    }
}
