package com.healthcare.navigator.agent.medication;

import com.healthcare.navigator.knowledge.KnowledgeBaseLoader;
import com.healthcare.navigator.knowledge.KnowledgeDocument;
import com.healthcare.navigator.rag.RagPipeline;
import com.healthcare.navigator.rag.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Spring AI tool bean providing medication information retrieval capabilities
 * to the {@code MedicationAgent}.
 *
 * <p>Three {@link Tool}-annotated methods are exposed:
 * <ul>
 *   <li>{@link #searchMedicationInformation} — RAG retrieval over medication documents</li>
 *   <li>{@link #getPatientMedications} — catalog lookup for patient-specific medication docs</li>
 *   <li>{@link #checkMedicationInteraction} — catalog lookup for interaction reference docs</li>
 * </ul>
 *
 * <p>Patient IDs are hashed (SHA-256) before appearing in any log output to avoid
 * logging PHI at any log level.
 *
 * <p>All methods handle exceptions gracefully — errors are logged and an empty list
 * is returned rather than propagating the exception to the calling agent.
 *
 * <p>Requirements: 5.1, 5.5, 5.6 | Design: §2.5 Medication Agent
 */
@Component
public class MedicationAgentTools {

    private static final Logger log = LoggerFactory.getLogger(MedicationAgentTools.class);

    private final RagPipeline ragPipeline;
    private final KnowledgeBaseLoader knowledgeBaseLoader;

    @Value("${rag.similarity-threshold:0.7}")
    private double similarityThreshold;

    public MedicationAgentTools(RagPipeline ragPipeline, KnowledgeBaseLoader knowledgeBaseLoader) {
        this.ragPipeline = ragPipeline;
        this.knowledgeBaseLoader = knowledgeBaseLoader;
    }

    /**
     * Searches medication documents using the RAG pipeline for relevant information.
     *
     * <p>Delegates to {@link RagPipeline#retrieve(String, int, double, String)} with a
     * generated correlation query ID.
     *
     * @param query the natural-language query about medication information
     * @param k     maximum number of results to return
     * @return ordered list of retrieval results (highest similarity first); may be empty
     */
    @Tool(description = "Search medication documents for relevant information using the RAG pipeline")
    public List<RetrievalResult> searchMedicationInformation(String query, int k) {
        String queryId = "medication-" + UUID.randomUUID();
        log.debug("searchMedicationInformation: k={}, queryId={}", k, queryId);

        try {
            return ragPipeline.retrieve(query, k, similarityThreshold, queryId);
        } catch (Exception e) {
            log.error("searchMedicationInformation: retrieval failed for queryId={}: {}", queryId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Retrieves patient-specific medication document metadata from the knowledge base catalog.
     *
     * <p>A document matches if:
     * <ul>
     *   <li>its {@code documentType} contains "medication" (case-insensitive), AND</li>
     *   <li>its {@code patientId} field matches the given {@code patientId} (case-insensitive)</li>
     * </ul>
     *
     * @param patientId the patient whose medication documents are requested
     * @return list of matching {@link KnowledgeDocument} metadata records; empty if none found
     */
    @Tool(description = "Retrieve patient-specific medication document metadata from the knowledge base catalog")
    public List<KnowledgeDocument> getPatientMedications(String patientId) {
        log.debug("getPatientMedications: patientIdHash={}", hashPatientId(patientId));

        try {
            var catalog = knowledgeBaseLoader.getCatalog();
            if (catalog == null || catalog.isEmpty()) {
                log.debug("getPatientMedications: catalog is empty, returning empty list");
                return Collections.emptyList();
            }

            String normalizedPatientId = patientId != null ? patientId.toLowerCase() : null;

            return catalog.values().stream()
                    .filter(doc -> isMedicationDocForPatient(doc, normalizedPatientId))
                    .toList();
        } catch (Exception e) {
            log.error("getPatientMedications: lookup failed for patientIdHash={}: {}",
                    hashPatientId(patientId), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Looks up medication interaction reference documents in the knowledge base catalog.
     *
     * <p>A document matches if its {@code documentType} contains "interaction" or
     * "medication-interaction" (case-insensitive). The {@code medicationNames} list is
     * provided for context and logged (as a count only, not the names, to avoid PHI leakage),
     * but document filtering is done by type — the LLM calling this tool is responsible for
     * interpreting which interactions apply to the given medication set.
     *
     * @param medicationNames list of medication names to check for interactions
     * @return list of matching interaction reference {@link KnowledgeDocument} records; empty if none found
     */
    @Tool(description = "Look up medication interaction reference documents in the knowledge base for the given list of medication names")
    public List<KnowledgeDocument> checkMedicationInteraction(List<String> medicationNames) {
        int count = medicationNames != null ? medicationNames.size() : 0;
        log.debug("checkMedicationInteraction: medicationCount={}", count);

        try {
            var catalog = knowledgeBaseLoader.getCatalog();
            if (catalog == null || catalog.isEmpty()) {
                log.debug("checkMedicationInteraction: catalog is empty, returning empty list");
                return Collections.emptyList();
            }

            return catalog.values().stream()
                    .filter(this::isInteractionDocument)
                    .toList();
        } catch (Exception e) {
            log.error("checkMedicationInteraction: lookup failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns true if the document is a medication document for the given patient.
     * Requires both documentType containing "medication" and patientId matching.
     */
    private boolean isMedicationDocForPatient(KnowledgeDocument doc, String normalizedPatientId) {
        boolean isMedicationDoc = doc.documentType() != null
                && doc.documentType().toLowerCase().contains("medication");

        boolean patientIdMatches = normalizedPatientId != null
                && doc.patientId() != null
                && doc.patientId().toLowerCase().equals(normalizedPatientId);

        return isMedicationDoc && patientIdMatches;
    }

    /**
     * Returns true if the document is an interaction reference document.
     * Matches documentType containing "interaction" or "medication-interaction".
     */
    private boolean isInteractionDocument(KnowledgeDocument doc) {
        if (doc.documentType() == null) {
            return false;
        }
        String lowerType = doc.documentType().toLowerCase();
        return lowerType.contains("interaction") || lowerType.contains("medication-interaction");
    }

    /**
     * Produces a SHA-256 hex digest of the given patient ID for safe log emission.
     * Returns {@code "<null>"} if the input is null.
     */
    private String hashPatientId(String patientId) {
        if (patientId == null) {
            return "<null>";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(patientId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM spec; this branch is unreachable in practice
            return "<hash-error>";
        }
    }
}
