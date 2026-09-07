package com.healthcare.navigator.agent.coordination;

import com.healthcare.navigator.domain.SourceCitation;

/**
 * Represents a care task or patient reminder extracted from care coordination documents.
 *
 * @param taskName    name of the care task
 * @param description description of what to do
 * @param frequency   how often the task should be performed (nullable, e.g., "twice daily")
 * @param dueDate     when the task should be completed (nullable)
 * @param source      citation to the source document
 */
public record CareTask(
        String taskName,
        String description,
        String frequency,
        String dueDate,
        SourceCitation source
) {}
