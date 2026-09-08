package com.healthcare.navigator.safety;

import com.healthcare.navigator.domain.SourceCitation;
import com.healthcare.navigator.knowledge.KnowledgeDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks answers for forbidden clinical-judgment language (diagnosis / prognosis)
 * and verifies that source citations exist in the knowledge-base catalog.
 *
 * <p>Requirements: 9.1, 9.3, 9.4 | Design: §2.7
 */
@Component
public class GroundingChecker {

    // ── Forbidden clinical-judgment patterns ──────────────────────────────────

    /**
     * Patterns that indicate a forbidden clinical judgment (diagnosis or prognosis).
     * All comparisons are case-insensitive via the CASE_INSENSITIVE flag.
     */
    private static final Pattern[] DIAGNOSIS_PATTERNS = {
        Pattern.compile("\\byou\\s+have\\s+\\w[\\w\\s]*", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\byou\\s+are\\s+diagnosed\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\byour\\s+diagnosis\\s+is\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\byou\\s+will\\s+develop\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bprognosis\\s+is\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\byou\\s+are\\s+suffering\\s+from\\b", Pattern.CASE_INSENSITIVE),
    };

    /**
     * Splits answer text into sentences using common sentence-ending punctuation.
     * Each segment is trimmed; empty segments are discarded.
     */
    private static final Pattern SENTENCE_SPLITTER = Pattern.compile("(?<=[.!?])\\s+");

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Scans {@code answer} for sentences that contain forbidden clinical-judgment language.
     * Each offending sentence is removed and a warning entry is appended to {@code warnings}.
     *
     * @param answer   the answer text to inspect (may be {@code null} or blank)
     * @param warnings mutable list to which warning messages are appended
     * @return the cleaned answer with offending sentences removed; empty string if all sentences
     *         were removed or the input was blank
     */
    public String checkDiagnosis(String answer, List<String> warnings) {
        if (answer == null || answer.isBlank()) {
            return answer == null ? "" : answer;
        }

        // Split into sentences, filter, then reassemble
        String[] sentences = SENTENCE_SPLITTER.split(answer);
        List<String> retained = new ArrayList<>();

        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (containsForbiddenPattern(trimmed)) {
                warnings.add("Safety: Removed clinical judgment language from response: \""
                        + abbreviate(trimmed, 120) + "\"");
            } else {
                retained.add(trimmed);
            }
        }

        return String.join(" ", retained);
    }

    /**
     * Verifies that every {@link SourceCitation} in {@code sources} corresponds to a document
     * that exists in the knowledge-base {@code catalog}. Citations whose
     * {@link SourceCitation#documentName()} is not a key in the catalog are removed and a
     * warning is appended to {@code warnings}.
     *
     * @param sources  the list of citations to verify (may be {@code null})
     * @param warnings mutable list to which warning messages are appended
     * @param catalog  the in-memory knowledge-base catalog keyed by document name
     * @return a new, filtered list containing only citations present in the catalog
     */
    public List<SourceCitation> verifySourceCitations(
            List<SourceCitation> sources,
            List<String> warnings,
            Map<String, KnowledgeDocument> catalog) {

        if (sources == null || sources.isEmpty()) {
            return new ArrayList<>();
        }

        List<SourceCitation> verified = new ArrayList<>();
        for (SourceCitation citation : sources) {
            if (citation == null) {
                continue;
            }
            String docName = citation.documentName();
            if (docName != null && catalog.containsKey(docName)) {
                verified.add(citation);
            } else {
                warnings.add("Safety: Removed unverified source citation: \""
                        + (docName != null ? docName : "<null>") + "\" — not found in knowledge base.");
            }
        }
        return verified;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean containsForbiddenPattern(String sentence) {
        for (Pattern pattern : DIAGNOSIS_PATTERNS) {
            if (pattern.matcher(sentence).find()) {
                return true;
            }
        }
        return false;
    }

    /** Truncates a string to {@code maxLen} characters, appending "…" if truncated. */
    private String abbreviate(String text, int maxLen) {
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen - 1) + "…";
    }
}
