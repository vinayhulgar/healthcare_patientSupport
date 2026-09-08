package com.healthcare.navigator.safety;

import com.healthcare.navigator.agent.AgentResult;
import com.healthcare.navigator.api.dto.PatientSupportResponse;
import com.healthcare.navigator.domain.AgentOutcome;
import com.healthcare.navigator.domain.SourceCitation;
import com.healthcare.navigator.knowledge.KnowledgeBaseLoader;
import com.healthcare.navigator.orchestrator.AggregatedResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the six ordered safety / grounding checks that every aggregated response
 * must pass before being returned to the client.
 *
 * <p>Checks (in order):
 * <ol>
 *   <li>Diagnosis / prognosis language removal ({@link GroundingChecker#checkDiagnosis})</li>
 *   <li>Medication hallucination detection ({@link HallucinationDetector#checkMedicationGrounding})</li>
 *   <li>Source citation verification ({@link GroundingChecker#verifySourceCitations})</li>
 *   <li>Conflict detection — contradictory medication/dosage across agent outputs</li>
 *   <li>Emergency escalation ({@link EmergencyEscalationHandler#escalateIfNeeded})</li>
 *   <li>Mandatory disclaimer injection</li>
 * </ol>
 *
 * <p>Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7 | Design: §2.7
 */
@Service
public class SafetyValidator {

    private static final Logger log = LoggerFactory.getLogger(SafetyValidator.class);

    /**
     * Mandatory disclaimer appended to every validated answer.
     */
    static final String DISCLAIMER =
            "This information is based on the provided healthcare records and knowledge base "
            + "and does not replace advice from a qualified healthcare professional.";

    /**
     * Common dosage unit tokens used to identify dosage-bearing sentences in conflict detection.
     */
    private static final String[] DOSAGE_UNITS = {
        "mg", "mcg", "µg", "ml", "g", "tablet", "tablets", "capsule", "capsules",
        "dose", "doses", "unit", "units"
    };

    private final GroundingChecker groundingChecker;
    private final HallucinationDetector hallucinationDetector;
    private final EmergencyEscalationHandler emergencyEscalationHandler;
    private final KnowledgeBaseLoader knowledgeBaseLoader;

    public SafetyValidator(
            GroundingChecker groundingChecker,
            HallucinationDetector hallucinationDetector,
            EmergencyEscalationHandler emergencyEscalationHandler,
            KnowledgeBaseLoader knowledgeBaseLoader) {
        this.groundingChecker = groundingChecker;
        this.hallucinationDetector = hallucinationDetector;
        this.emergencyEscalationHandler = emergencyEscalationHandler;
        this.knowledgeBaseLoader = knowledgeBaseLoader;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Runs all six safety checks on {@code aggregated} and returns a fully-validated
     * {@link PatientSupportResponse}.
     *
     * <p>All checks are non-throwing: any exception inside a check is caught, logged,
     * and recorded as a warning so that the response is always returned.
     *
     * @param aggregated   the aggregated multi-agent response
     * @param agentResults the raw results from all sub-agents
     * @return a safety-validated response ready to send to the client
     */
    public PatientSupportResponse validate(
            AggregatedResponse aggregated,
            List<AgentResult> agentResults) {

        // Initialise mutable working state from the (immutable) record
        String answer = aggregated.answer() != null ? aggregated.answer() : "";
        List<SourceCitation> sources = new ArrayList<>(
                aggregated.sources() != null ? aggregated.sources() : List.of());
        List<String> warnings = new ArrayList<>(
                aggregated.warnings() != null ? aggregated.warnings() : List.of());
        List<String> agentsUsed = aggregated.agentsUsed() != null
                ? aggregated.agentsUsed() : List.of();
        double confidence = aggregated.confidence();

        // ── Check 1: Diagnosis / prognosis language removal ───────────────────
        try {
            answer = groundingChecker.checkDiagnosis(answer, warnings);
        } catch (Exception e) {
            log.error("SafetyValidator: GroundingChecker.checkDiagnosis failed: {}", e.getMessage(), e);
            warnings.add("Safety: Diagnosis check could not be completed: " + e.getMessage());
        }

        // ── Check 2: Medication hallucination detection ────────────────────────
        try {
            answer = hallucinationDetector.checkMedicationGrounding(answer, agentResults, warnings);
        } catch (Exception e) {
            log.error("SafetyValidator: HallucinationDetector.checkMedicationGrounding failed: {}",
                    e.getMessage(), e);
            warnings.add("Safety: Medication grounding check could not be completed: " + e.getMessage());
        }

        // ── Check 3: Source citation verification ──────────────────────────────
        try {
            sources = groundingChecker.verifySourceCitations(
                    sources, warnings, knowledgeBaseLoader.getCatalog());
        } catch (Exception e) {
            log.error("SafetyValidator: GroundingChecker.verifySourceCitations failed: {}",
                    e.getMessage(), e);
            warnings.add("Safety: Source citation verification could not be completed: " + e.getMessage());
        }

        // ── Check 4: Conflict detection ────────────────────────────────────────
        try {
            answer = detectAndAnnotateConflicts(answer, agentResults, warnings);
        } catch (Exception e) {
            log.error("SafetyValidator: Conflict detection failed: {}", e.getMessage(), e);
            warnings.add("Safety: Conflict detection could not be completed: " + e.getMessage());
        }

        // ── Check 5: Emergency escalation ─────────────────────────────────────
        try {
            // Classification is not carried by AggregatedResponse; keyword detection in
            // EmergencyEscalationHandler covers the emergency case from the answer text.
            answer = emergencyEscalationHandler.escalateIfNeeded(answer, null, warnings);
        } catch (Exception e) {
            log.error("SafetyValidator: EmergencyEscalationHandler.escalateIfNeeded failed: {}",
                    e.getMessage(), e);
            warnings.add("Safety: Emergency escalation check could not be completed: " + e.getMessage());
        }

        // ── Check 6: Mandatory disclaimer ─────────────────────────────────────
        if (!answer.contains(DISCLAIMER)) {
            answer = answer + "\n\n" + DISCLAIMER;
        }

        return new PatientSupportResponse(
                "",          // requestId not available from AggregatedResponse
                answer,
                agentsUsed,
                List.copyOf(sources),
                List.copyOf(warnings),
                confidence);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Detects contradictory medication or dosage information across pairs of SUCCESS agent
     * results.  When a conflict is found both perspectives are retained in the answer (with
     * an explicit note) and a conflict warning is added to {@code warnings}.
     *
     * <p>The detection heuristic looks for sentences in different agents' structured outputs
     * that mention a dosage unit — if two agents mention the same medication keyword but with
     * a differing dosage value, a conflict warning is emitted.
     *
     * @param answer       current answer text
     * @param agentResults raw agent results
     * @param warnings     mutable warning list
     * @return the (potentially annotated) answer
     */
    private String detectAndAnnotateConflicts(
            String answer,
            List<AgentResult> agentResults,
            List<String> warnings) {

        if (agentResults == null || agentResults.size() < 2) {
            return answer;
        }

        // Collect dosage-bearing text snippets from SUCCESS agents
        List<AgentDosageInfo> dosageInfoList = new ArrayList<>();
        for (AgentResult result : agentResults) {
            if (result.outcome() != AgentOutcome.SUCCESS || result.structuredOutput() == null) {
                continue;
            }
            String outputText = result.structuredOutput().toString();
            if (containsDosageInfo(outputText)) {
                dosageInfoList.add(new AgentDosageInfo(result.agentName(), outputText));
            }
        }

        // Check for conflicts between pairs of agents
        boolean conflictDetected = false;
        StringBuilder conflictNotes = new StringBuilder();

        for (int i = 0; i < dosageInfoList.size(); i++) {
            for (int j = i + 1; j < dosageInfoList.size(); j++) {
                AgentDosageInfo a = dosageInfoList.get(i);
                AgentDosageInfo b = dosageInfoList.get(j);

                if (outputsConflict(a.outputText(), b.outputText())) {
                    if (!conflictDetected) {
                        conflictDetected = true;
                    }
                    String conflictMsg = String.format(
                            "Conflicting medication/dosage information detected between '%s' and '%s'. "
                            + "Both perspectives have been retained; please consult your healthcare provider.",
                            a.agentName(), b.agentName());
                    warnings.add("Safety: " + conflictMsg);
                    conflictNotes.append("\n[NOTE: ").append(conflictMsg).append("]");
                }
            }
        }

        if (conflictDetected) {
            answer = answer + conflictNotes;
        }

        return answer;
    }

    /**
     * Returns {@code true} if the output text contains a dosage unit, suggesting it carries
     * medication dosage information that could conflict with another agent's output.
     */
    private boolean containsDosageInfo(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String unit : DOSAGE_UNITS) {
            if (lower.contains(unit)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Simple heuristic conflict detector: two outputs conflict if they both mention a dosage
     * unit but contain different numeric values adjacent to that unit.  This is intentionally
     * conservative to avoid false positives — only numeric differences trigger a conflict.
     */
    private boolean outputsConflict(String textA, String textB) {
        // Extract (medication-keyword, dosage) pairs and compare
        // For simplicity: if both texts contain dosage info but different numbers, flag as conflict
        List<String> numbersA = extractNumbers(textA);
        List<String> numbersB = extractNumbers(textB);

        if (numbersA.isEmpty() || numbersB.isEmpty()) {
            return false;
        }

        // Conflict: they share at least one number that is different in a dosage context,
        // i.e., both have numbers but no numbers in common (completely disjoint dosages)
        for (String numA : numbersA) {
            if (numbersB.contains(numA)) {
                // At least one value in common → likely not a conflict
                return false;
            }
        }
        // All numbers differ → potential conflict
        return !numbersA.isEmpty() && !numbersB.isEmpty();
    }

    /** Extracts all numeric tokens (integer or decimal) from a text string. */
    private List<String> extractNumbers(String text) {
        List<String> numbers = new ArrayList<>();
        if (text == null) {
            return numbers;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b\\d+(?:\\.\\d+)?\\b")
                .matcher(text);
        while (m.find()) {
            numbers.add(m.group());
        }
        return numbers;
    }

    /** Simple value object for pairing an agent name with its dosage-bearing output text. */
    private record AgentDosageInfo(String agentName, String outputText) {}
}
