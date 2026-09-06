package com.healthcare.navigator.agent.clinical;

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
 * Spring AI tool bean providing clinical information retrieval capabilities
 * to the {@code ClinicalInformationAgent}.
 *
 * <p>Two {@link Tool}-annotated methods are exposed:
 * <ul>
 *   <li>{@link #searchClinicalDocuments} — RAG retrieval over clinical/discharge docs</li>
 *   <li>{@link #getDischargeInstructions} — catalog lookup for discharge documents</li>
 * </ul>
 *
 * <p>Patient IDs are hashed (SHA-256) before appearing in any log output to avoid
 * logging PHI at any log level.
 *
 * <p>Requirements: 4.1, 4.3 | Design: §2.5 Clinical Information Agent
 */
@Component
public class ClinicalAgentTools {

    private static final Logger log = LoggerFactory.getLogger(ClinicalAgentTools.class);

    private final RagPipeline ragPipeline;
    private final KnowledgeBaseLoader knowledgeBaseLoader;

    @Value("${rag.similarity-threshold:0.7}")
    private double similarityThreshold;

    public ClinicalAgentTools(RagPipeline ragPipeline, KnowledgeBaseLoader knowledgeBaseLoader) {
        this.ragPipeline = ragPipeline;
        this.knowledgeBaseLoader = knowledgeBaseLoader;
    }

    /**
     * Searches clinical and discharge documents using the RAG pipeline.
     *
     * <p>Delegates to {@link RagPipeline#retrieve(String, int, double, String)} with a
     * generated correlation query ID. The patient ID is hashed before logging.
     *
     * @param query     the natural-language query to search for
     * @param patientId the patient identifier (used for correlation; hashed in logs)
     * @param k         maximum number of results to return
     * @return ordered list of retrieval results (highest similarity first); may be empty
     */
    @Tool(description = "Search clinical and discharge documents for relevant information using the RAG pipeline")
    public List<RetrievalResult> searchClinicalDocuments(String query, String patientId, int k) {
        String queryId = "clinical-" + UUID.randomUUID();

        log.debug("searchClinicalDocuments: patientIdHash={}, k={}, queryId={}",
                hashPatientId(patientId), k, queryId);

        return ragPipeline.retrieve(query, k, similarityThreshold, queryId);
    }

    /**
     * Retrieves discharge document metadata for the given patient from the knowledge base catalog.
     *
     * <p>A document matches if:
     * <ul>
     *   <li>its {@code patientId} field matches {@code patientId} (case-insensitive), OR</li>
     *   <li>its {@code documentType} or {@code documentName} contains "discharge" (case-insensitive)</li>
     * </ul>
     *
     * @param patientId the patient whose discharge instructions are requested
     * @return list of matching {@link KnowledgeDocument} metadata records; empty if none found
     */
    @Tool(description = "Retrieve discharge instruction document metadata for a specific patient from the knowledge base catalog")
    public List<KnowledgeDocument> getDischargeInstructions(String patientId) {
        log.debug("getDischargeInstructions: patientIdHash={}", hashPatientId(patientId));

        var catalog = knowledgeBaseLoader.getCatalog();
        if (catalog == null || catalog.isEmpty()) {
            log.debug("getDischargeInstructions: catalog is empty, returning empty list");
            return Collections.emptyList();
        }

        String normalizedPatientId = patientId != null ? patientId.toLowerCase() : null;

        return catalog.values().stream()
                .filter(doc -> matchesDischargeForPatient(doc, normalizedPatientId))
                .toList();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns true if the document is a discharge document for the given patient.
     * Matches on patientId (case-insensitive) OR documentType/documentName containing "discharge".
     */
    private boolean matchesDischargeForPatient(KnowledgeDocument doc, String normalizedPatientId) {
        boolean patientIdMatches = normalizedPatientId != null
                && doc.patientId() != null
                && doc.patientId().toLowerCase().equals(normalizedPatientId);

        boolean isDischargeDoc =
                (doc.documentType() != null && doc.documentType().toLowerCase().contains("discharge"))
                || (doc.documentName() != null && doc.documentName().toLowerCase().contains("discharge"));

        return patientIdMatches || isDischargeDoc;
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
