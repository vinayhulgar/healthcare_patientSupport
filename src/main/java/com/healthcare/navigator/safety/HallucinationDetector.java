package com.healthcare.navigator.safety;

import com.healthcare.navigator.agent.AgentResult;
import com.healthcare.navigator.agent.medication.MedicationAgent;
import com.healthcare.navigator.domain.AgentOutcome;
import com.healthcare.navigator.domain.SourceCitation;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects medication names in the answer that are not grounded in the sources
 * returned by the Medication Agent.
 *
 * <p>Requirements: 9.2, 9.4 | Design: §2.7
 */
@Component
public class HallucinationDetector {

    /** Agent name used to identify the Medication Agent result. */
    private static final String MEDICATION_AGENT_NAME = "MedicationAgent";

    /**
     * Cross-references medication-like words in {@code answer} against the grounded set of
     * medication names extracted from the Medication Agent's source citations.  Any word that
     * looks like a medication name (title-cased or all-caps token ≥ 3 chars) but does not
     * appear in the grounded set is removed from the answer and a warning is added.
     *
     * <p>Grounded medication names are extracted from the {@code documentName} of each
     * {@link SourceCitation} on the Medication Agent's {@link AgentResult}.  Individual words
     * within each document name are also added to the grounded set so that multi-word drug
     * names contribute their components.
     *
     * @param answer       the answer text to inspect
     * @param agentResults all agent results from the current request
     * @param warnings     mutable list to which warning messages are appended
     * @return the answer with any ungrounded medication-like tokens removed
     */
    public String checkMedicationGrounding(
            String answer,
            List<AgentResult> agentResults,
            List<String> warnings) {

        if (answer == null || answer.isBlank() || agentResults == null || agentResults.isEmpty()) {
            return answer == null ? "" : answer;
        }

        // Only run the check when the Medication Agent actually produced results
        AgentResult medicationResult = findMedicationAgentResult(agentResults);
        if (medicationResult == null) {
            // No medication agent result — no grounding to check against
            return answer;
        }

        // Build the grounded set from source citation document names
        Set<String> groundedTerms = buildGroundedSet(medicationResult);
        if (groundedTerms.isEmpty()) {
            // No sources at all — cannot verify; leave answer unchanged
            return answer;
        }

        // Scan the answer word by word and remove ungrounded medication-like tokens
        String[] tokens = answer.split("(?<=\\s)|(?=\\s)");
        StringBuilder cleaned = new StringBuilder();

        for (String token : tokens) {
            String bare = token.strip();
            if (looksLikeMedicationName(bare) && !isGrounded(bare, groundedTerms)) {
                warnings.add("Safety: Removed potentially ungrounded medication reference: \""
                        + bare + "\" — not found in Medication Agent sources.");
                // Replace the token with whitespace to preserve surrounding spacing
                cleaned.append(token.replace(bare, ""));
            } else {
                cleaned.append(token);
            }
        }

        // Collapse multiple spaces that may result from removal
        return cleaned.toString().replaceAll("[ ]{2,}", " ").strip();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Finds the first {@link AgentResult} produced by the Medication Agent with outcome
     * {@link com.healthcare.navigator.domain.AgentOutcome#SUCCESS}.
     */
    private AgentResult findMedicationAgentResult(List<AgentResult> agentResults) {
        for (AgentResult result : agentResults) {
            if (MEDICATION_AGENT_NAME.equals(result.agentName())
                    && result.outcome() == AgentOutcome.SUCCESS) {
                return result;
            }
        }
        return null;
    }

    /**
     * Builds a set of lower-cased grounded terms from the Medication Agent's source citations.
     * Both full document names and individual words within each document name are included.
     */
    private Set<String> buildGroundedSet(AgentResult medicationResult) {
        Set<String> grounded = new HashSet<>();
        List<SourceCitation> sources = medicationResult.sources();
        if (sources == null) {
            return grounded;
        }

        for (SourceCitation citation : sources) {
            if (citation == null || citation.documentName() == null) {
                continue;
            }
            String docName = citation.documentName();
            grounded.add(docName.toLowerCase());

            // Also add individual words (handles "aspirin-instructions" → "aspirin", "instructions")
            String[] parts = docName.split("[\\s\\-_/]+");
            for (String part : parts) {
                String lc = part.toLowerCase().replaceAll("[^a-z0-9]", "");
                if (!lc.isEmpty()) {
                    grounded.add(lc);
                }
            }

            // If the medication result has structured output with medication list, add those too
            Object output = medicationResult.structuredOutput();
            if (output instanceof MedicationAgent.MedicationAgentOutput medOutput) {
                if (medOutput.medications() != null) {
                    for (String med : medOutput.medications()) {
                        if (med != null) {
                            grounded.add(med.toLowerCase().strip());
                            // Add individual words of multi-word medication names
                            for (String word : med.split("\\s+")) {
                                grounded.add(word.toLowerCase().replaceAll("[^a-z0-9]", ""));
                            }
                        }
                    }
                }
            }
        }
        return grounded;
    }

    /**
     * Heuristic: a token looks like a medication name if it is ≥ 3 characters long and is
     * either title-cased (starts with uppercase, remainder lowercase) or all-uppercase.
     * Pure numbers, common English words, and sentence starters are excluded by this check.
     */
    private boolean looksLikeMedicationName(String token) {
        if (token == null || token.length() < 3) {
            return false;
        }
        // Strip trailing punctuation for the check
        String stripped = token.replaceAll("[^A-Za-z0-9]", "");
        if (stripped.length() < 3) {
            return false;
        }
        // Title-cased: first char upper, at least one lower char elsewhere
        boolean titleCased = Character.isUpperCase(stripped.charAt(0))
                && stripped.chars().skip(1).anyMatch(Character::isLowerCase);
        // All-uppercase acronym-style (like "NSAIDs")
        boolean allUpper = stripped.chars().allMatch(Character::isUpperCase);
        // Must contain at least one letter (exclude pure numbers)
        boolean hasLetter = stripped.chars().anyMatch(Character::isLetter);

        return hasLetter && (titleCased || allUpper);
    }

    /** Returns {@code true} if {@code token} (case-insensitive) is in the grounded set. */
    private boolean isGrounded(String token, Set<String> groundedTerms) {
        String lc = token.toLowerCase().replaceAll("[^a-z0-9]", "");
        return groundedTerms.contains(lc) || groundedTerms.contains(token.toLowerCase());
    }
}
