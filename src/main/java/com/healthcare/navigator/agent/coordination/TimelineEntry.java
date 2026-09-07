package com.healthcare.navigator.agent.coordination;

import com.healthcare.navigator.domain.SourceCitation;

/**
 * A point on the chronological care timeline, derived from retrieved KB documents.
 *
 * <p>Dates are taken verbatim from source documents; the timeline is presented in
 * ascending date order as required by Req 6.5.
 *
 * @param date     ISO 8601 date or relative timeframe exactly as stated in the source document
 * @param event    description of the event or milestone
 * @param category category of the event (e.g., "appointment", "medication", "therapy")
 * @param source   citation to the source document
 */
public record TimelineEntry(
        String date,
        String event,
        String category,
        SourceCitation source
) {}
