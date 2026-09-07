package com.healthcare.navigator.agent.coordination;

import com.healthcare.navigator.domain.SourceCitation;

/**
 * A condition that requires escalation or emergency contact.
 *
 * <p>Both {@code condition} and {@code action} MUST be verbatim text copied from the
 * source document — no rephrasing, summarizing, or omission is permitted (Req 6.6).
 * Every entry must carry a {@link SourceCitation} identifying the exact document and
 * section from which the verbatim text was taken.
 *
 * @param condition verbatim text from the source document describing the escalation condition
 * @param action    verbatim action instruction from the source document
 * @param source    citation with document name, type, and section
 */
public record EscalationCondition(
        String condition,
        String action,
        SourceCitation source
) {}
