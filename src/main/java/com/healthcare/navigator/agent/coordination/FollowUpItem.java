package com.healthcare.navigator.agent.coordination;

import com.healthcare.navigator.domain.SourceCitation;

/**
 * Represents a follow-up appointment or action extracted from care coordination documents.
 *
 * @param appointmentType the type of follow-up (e.g., "Orthopedic surgeon visit")
 * @param scheduledDate   date/timeframe as a string, taken directly from the source document
 * @param location        where the appointment takes place (nullable)
 * @param notes           additional notes (nullable)
 * @param source          citation to the source document
 */
public record FollowUpItem(
        String appointmentType,
        String scheduledDate,
        String location,
        String notes,
        SourceCitation source
) {}
