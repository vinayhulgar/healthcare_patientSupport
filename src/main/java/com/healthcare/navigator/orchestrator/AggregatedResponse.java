package com.healthcare.navigator.orchestrator;

import com.healthcare.navigator.domain.SourceCitation;
import java.util.List;

public record AggregatedResponse(
    String answer,
    List<String> agentsUsed,
    List<SourceCitation> sources,
    List<String> warnings,
    double confidence
) {}
