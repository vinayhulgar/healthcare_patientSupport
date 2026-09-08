package com.healthcare.navigator.orchestrator;

import com.healthcare.navigator.domain.RequestClassification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Classifies an incoming patient question into exactly one of the five defined
 * {@link RequestClassification} categories using a Spring AI {@link ChatClient}
 * with structured output.
 *
 * <p>If the LLM cannot confidently assign a category, the system prompt directs
 * it to default to {@link RequestClassification#GENERAL_HEALTHCARE}. A fallback
 * note is logged whenever the resulting classification is {@code GENERAL_HEALTHCARE}
 * and confidence is below a defined threshold.
 *
 * <p>All exceptions are caught internally; the method always returns a valid
 * {@link ClassificationResult} — defaulting to {@code GENERAL_HEALTHCARE} when
 * an error occurs.
 *
 * <p>Requirements: 2.1, 2.2, 2.5 | Design: §2.4
 */
@Component
public class RequestClassifier {

    private static final Logger log = LoggerFactory.getLogger(RequestClassifier.class);

    /**
     * Confidence threshold below which a {@code GENERAL_HEALTHCARE} classification
     * is treated as an uncertain fallback and logged accordingly (Req 2.2).
     */
    private static final double FALLBACK_CONFIDENCE_THRESHOLD = 0.6;

    /**
     * System prompt that instructs the LLM to classify the patient question into
     * exactly one of the five defined categories. Instructions are kept in the
     * system prompt only — patient question content flows through the user message.
     */
    private static final String SYSTEM_PROMPT = """
            You are a healthcare query classifier. Classify the patient question into \
            EXACTLY ONE of the following five categories:

            - CLINICAL_INFORMATION : questions about medical conditions, symptoms, \
              diagnoses, treatments, procedures, lab results, or discharge instructions.
            - MEDICATION           : questions about medications, dosages, side effects, \
              drug interactions, refills, or pharmacy concerns.
            - CARE_COORDINATION    : questions about appointments, referrals, follow-ups, \
              care plans, home health services, or coordination between providers.
            - GENERAL_HEALTHCARE   : general wellness questions, preventive care, lifestyle \
              advice, or questions that do not fit any more specific category. \
              Also use this category when you are uncertain.
            - EMERGENCY_OR_HIGH_RISK : questions involving chest pain, difficulty breathing, \
              stroke symptoms, severe bleeding, suicidal ideation, or any other potentially \
              life-threatening situation.

            Rules:
            1. Return ONLY the classification value exactly as written above (e.g. "MEDICATION").
            2. Set confidence to a value between 0.0 and 1.0 reflecting your certainty.
            3. Provide a brief reasoning (1-2 sentences) explaining the classification.
            4. When uncertain, classify as GENERAL_HEALTHCARE with a lower confidence score.
            5. Never invent new categories; always use one of the five listed above.
            """;

    private final ChatClient chatClient;

    public RequestClassifier(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Classifies the patient {@code question} and returns a {@link ClassificationResult}.
     *
     * <p>The raw classification, confidence, and reasoning are logged at DEBUG level
     * using the MDC {@code correlationId} when present. A WARN-level fallback note
     * is logged when the result is {@link RequestClassification#GENERAL_HEALTHCARE}
     * with confidence below {@value #FALLBACK_CONFIDENCE_THRESHOLD} (Req 2.2).
     *
     * <p>On any exception, returns a safe fallback result with
     * {@link RequestClassification#GENERAL_HEALTHCARE} and confidence 0.0.
     *
     * @param question the patient's natural-language question
     * @return a {@link ClassificationResult} — never {@code null}
     */
    public ClassificationResult classify(String question) {
        String correlationId = MDC.get("correlationId");

        try {
            ClassificationResult result = chatClient
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(question)
                    .call()
                    .entity(ClassificationResult.class);

            if (result == null) {
                log.warn("RequestClassifier: ChatClient returned null for correlationId={}; "
                        + "falling back to GENERAL_HEALTHCARE", correlationId);
                return fallback("ChatClient returned null response");
            }

            // Normalise null classification to the safe default
            RequestClassification classification = result.classification() != null
                    ? result.classification()
                    : RequestClassification.GENERAL_HEALTHCARE;

            double confidence = clampConfidence(result.confidence());
            String reasoning = result.reasoning() != null ? result.reasoning() : "";

            ClassificationResult normalised = new ClassificationResult(classification, confidence, reasoning);

            // Execution log — Req 2.5
            log.debug("RequestClassifier: correlationId={} classification={} confidence={} reasoning=\"{}\"",
                    correlationId, classification, confidence, reasoning);

            // Fallback note — Req 2.2
            if (classification == RequestClassification.GENERAL_HEALTHCARE
                    && confidence < FALLBACK_CONFIDENCE_THRESHOLD) {
                log.warn("RequestClassifier: classification fell back to GENERAL_HEALTHCARE "
                                + "(low confidence={}) for correlationId={}",
                        confidence, correlationId);
            }

            return normalised;

        } catch (Exception e) {
            log.error("RequestClassifier: classification failed for correlationId={}: {}; "
                    + "falling back to GENERAL_HEALTHCARE", correlationId, e.getMessage());
            return fallback("Exception during classification: " + e.getMessage());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns a safe fallback {@link ClassificationResult} with
     * {@link RequestClassification#GENERAL_HEALTHCARE} and logs a fallback note (Req 2.2).
     */
    private ClassificationResult fallback(String reason) {
        String correlationId = MDC.get("correlationId");
        log.warn("RequestClassifier: classification fell back to GENERAL_HEALTHCARE "
                + "for correlationId={} reason=\"{}\"", correlationId, reason);
        return new ClassificationResult(
                RequestClassification.GENERAL_HEALTHCARE,
                0.0,
                "Fallback classification: " + reason);
    }

    /**
     * Clamps the confidence value to the valid [0.0, 1.0] range.
     */
    private double clampConfidence(double raw) {
        return Math.max(0.0, Math.min(1.0, raw));
    }

    // ── Structured output record ──────────────────────────────────────────────

    /**
     * Structured output record returned by the {@link ChatClient} and used throughout
     * the orchestrator pipeline.
     *
     * @param classification the determined {@link RequestClassification} category
     * @param confidence     certainty score in [0.0, 1.0]
     * @param reasoning      brief explanation of the classification decision
     */
    public record ClassificationResult(
            RequestClassification classification,
            double confidence,
            String reasoning
    ) {}
}
