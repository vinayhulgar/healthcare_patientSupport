package com.healthcare.navigator.api.dto;

import com.healthcare.navigator.domain.SourceCitation;

import java.util.List;

/**
 * Outgoing response DTO returned to the client after processing a patient query.
 *
 * @param requestId   the correlation ID assigned to this request
 * @param answer      the assembled, safety-validated answer for the patient
 * @param agentsUsed  names of agents that returned a successful result
 * @param sources     source citations backing the answer
 * @param warnings    non-fatal warnings (e.g., missing data, grounding issues)
 * @param confidence  overall confidence score in the range [0.0, 1.0]
 */
public record PatientSupportResponse(
        String requestId,
        String answer,
        List<String> agentsUsed,
        List<SourceCitation> sources,
        List<String> warnings,
        double confidence
) {}
