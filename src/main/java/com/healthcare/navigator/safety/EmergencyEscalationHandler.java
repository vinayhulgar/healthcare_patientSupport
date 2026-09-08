package com.healthcare.navigator.safety;

import com.healthcare.navigator.domain.RequestClassification;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Prepends a mandatory emergency directive to responses that indicate a life-threatening
 * situation — either via the request classification or via emergency keywords in the answer.
 *
 * <p>Requirements: 2.3, 9.6 | Design: §2.7
 */
@Component
public class EmergencyEscalationHandler {

    /**
     * The mandatory emergency directive that must be prepended to any response that triggers
     * the emergency escalation check.  This value is intentionally {@code public static final}
     * so that callers (e.g., property-based tests) can assert its presence without hard-coding
     * the string.
     */
    public static final String EMERGENCY_PREFIX =
            "EMERGENCY: Please contact emergency services (911) or your healthcare provider "
            + "immediately. Do not delay seeking medical attention.\n\n";

    /** Emergency keywords that trigger escalation regardless of classification. */
    private static final Pattern EMERGENCY_KEYWORD_PATTERN = Pattern.compile(
            "\\b(chest\\s+pain|difficulty\\s+breathing|severe\\s+bleeding|unconscious|stroke"
            + "|heart\\s+attack|call\\s+911|emergency|911)\\b",
            Pattern.CASE_INSENSITIVE);

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Prepends {@link #EMERGENCY_PREFIX} to {@code answer} if either:
     * <ul>
     *   <li>{@code classification} is {@link RequestClassification#EMERGENCY_OR_HIGH_RISK}, or</li>
     *   <li>{@code answer} contains one or more emergency keywords.</li>
     * </ul>
     * If the prefix is already present the answer is returned unchanged (idempotent).
     * A warning entry is appended to {@code warnings} when escalation is triggered.
     *
     * @param answer         the answer text to check and potentially modify
     * @param classification the request classification (may be {@code null})
     * @param warnings       mutable list to which a warning message is appended on escalation
     * @return the (potentially prepended) answer
     */
    public String escalateIfNeeded(
            String answer,
            RequestClassification classification,
            List<String> warnings) {

        if (isEmergencyDirectivePresent(answer)) {
            // Already escalated — idempotent; do not double-prepend
            return answer;
        }

        boolean shouldEscalate = classification == RequestClassification.EMERGENCY_OR_HIGH_RISK
                || containsEmergencyKeyword(answer);

        if (shouldEscalate) {
            warnings.add("Safety: Emergency escalation directive prepended — "
                    + "life-threatening situation detected.");
            return EMERGENCY_PREFIX + (answer != null ? answer : "");
        }

        return answer != null ? answer : "";
    }

    /**
     * Returns {@code true} if {@code answer} already starts with {@link #EMERGENCY_PREFIX}.
     *
     * @param answer the answer text to inspect (may be {@code null})
     * @return {@code true} when the emergency directive is present
     */
    public boolean isEmergencyDirectivePresent(String answer) {
        return answer != null && answer.startsWith(EMERGENCY_PREFIX);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean containsEmergencyKeyword(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return EMERGENCY_KEYWORD_PATTERN.matcher(text).find();
    }
}
