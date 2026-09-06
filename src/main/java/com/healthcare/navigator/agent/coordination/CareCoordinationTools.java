package com.healthcare.navigator.agent.coordination;

import com.healthcare.navigator.knowledge.KnowledgeBaseLoader;
import com.healthcare.navigator.knowledge.KnowledgeDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * Spring AI tool bean providing care coordination retrieval capabilities
 * to the {@code CareCoordinationAgent}.
 *
 * <p>Three {@link Tool}-annotated methods are exposed:
 * <ul>
 *   <li>{@link #getFollowUpPlan} — catalog lookup for follow-up plan and care-plan docs</li>
 *   <li>{@link #getAppointments} — catalog lookup for appointment documents</li>
 *   <li>{@link #getCareTasks} — catalog lookup for task, care-plan, and discharge docs</li>
 * </ul>
 *
 * <p>Patient IDs are hashed (SHA-256) before appearing in any log output to avoid
 * logging PHI at any log level.
 *
 * <p>All methods handle exceptions gracefully — errors are logged and an empty list
 * is returned rather than propagating the exception to the calling agent.
 *
 * <p>Requirements: 6.1, 6.2 | Design: §2.5 Care Coordination Agent
 */
@Component
public class CareCoordinationTools {

    private static final Logger log = LoggerFactory.getLogger(CareCoordinationTools.class);

    private final KnowledgeBaseLoader knowledgeBaseLoader;

    public CareCoordinationTools(KnowledgeBaseLoader knowledgeBaseLoader) {
        this.knowledgeBaseLoader = knowledgeBaseLoader;
    }

    /**
     * Retrieves the patient's follow-up plan and care-plan documents from the knowledge base catalog.
     *
     * <p>A document matches if:
     * <ul>
     *   <li>its {@code documentType} contains "follow-up" or "care-plan" (case-insensitive), AND</li>
     *   <li>its {@code patientId} matches the given {@code patientId} (case-insensitive)</li>
     * </ul>
     *
     * @param patientId the patient whose follow-up plan documents are requested
     * @return list of matching {@link KnowledgeDocument} metadata records; empty if none found
     */
    @Tool(description = "Retrieve the patient's follow-up plan and care-plan documents from the knowledge base catalog")
    public List<KnowledgeDocument> getFollowUpPlan(String patientId) {
        log.debug("getFollowUpPlan: patientIdHash={}", hashPatientId(patientId));

        try {
            var catalog = knowledgeBaseLoader.getCatalog();
            if (catalog == null || catalog.isEmpty()) {
                log.debug("getFollowUpPlan: catalog is empty, returning empty list");
                return Collections.emptyList();
            }

            String normalizedPatientId = patientId != null ? patientId.toLowerCase() : null;

            return catalog.values().stream()
                    .filter(doc -> isFollowUpDocForPatient(doc, normalizedPatientId))
                    .toList();
        } catch (Exception e) {
            log.error("getFollowUpPlan: lookup failed for patientIdHash={}: {}",
                    hashPatientId(patientId), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Retrieves scheduled appointment documents from the knowledge base catalog.
     *
     * <p>A document matches if its {@code documentType} contains "appointment"
     * (case-insensitive) AND either:
     * <ul>
     *   <li>the document's {@code patientId} matches the given {@code patientId}
     *       (case-insensitive), OR</li>
     *   <li>the document has no {@code patientId} (general appointment guidelines)</li>
     * </ul>
     *
     * @param patientId the patient whose appointment documents are requested
     * @return list of matching {@link KnowledgeDocument} metadata records; empty if none found
     */
    @Tool(description = "Retrieve scheduled appointment documents for a patient or general appointment guidelines from the knowledge base catalog")
    public List<KnowledgeDocument> getAppointments(String patientId) {
        log.debug("getAppointments: patientIdHash={}", hashPatientId(patientId));

        try {
            var catalog = knowledgeBaseLoader.getCatalog();
            if (catalog == null || catalog.isEmpty()) {
                log.debug("getAppointments: catalog is empty, returning empty list");
                return Collections.emptyList();
            }

            String normalizedPatientId = patientId != null ? patientId.toLowerCase() : null;

            return catalog.values().stream()
                    .filter(doc -> isAppointmentDocForPatientOrGeneral(doc, normalizedPatientId))
                    .toList();
        } catch (Exception e) {
            log.error("getAppointments: lookup failed for patientIdHash={}: {}",
                    hashPatientId(patientId), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Retrieves care task, care-plan, and discharge documents for the given patient
     * from the knowledge base catalog.
     *
     * <p>A document matches if:
     * <ul>
     *   <li>its {@code documentType} contains "task", "care-plan", or "discharge"
     *       (case-insensitive), AND</li>
     *   <li>its {@code patientId} matches the given {@code patientId} (case-insensitive)</li>
     * </ul>
     *
     * @param patientId the patient whose care task documents are requested
     * @return list of matching {@link KnowledgeDocument} metadata records; empty if none found
     */
    @Tool(description = "Retrieve care tasks, reminders, care-plan entries, and discharge documents for a patient from the knowledge base catalog")
    public List<KnowledgeDocument> getCareTasks(String patientId) {
        log.debug("getCareTasks: patientIdHash={}", hashPatientId(patientId));

        try {
            var catalog = knowledgeBaseLoader.getCatalog();
            if (catalog == null || catalog.isEmpty()) {
                log.debug("getCareTasks: catalog is empty, returning empty list");
                return Collections.emptyList();
            }

            String normalizedPatientId = patientId != null ? patientId.toLowerCase() : null;

            return catalog.values().stream()
                    .filter(doc -> isCareTaskDocForPatient(doc, normalizedPatientId))
                    .toList();
        } catch (Exception e) {
            log.error("getCareTasks: lookup failed for patientIdHash={}: {}",
                    hashPatientId(patientId), e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns true if the document is a follow-up or care-plan document for the given patient.
     */
    private boolean isFollowUpDocForPatient(KnowledgeDocument doc, String normalizedPatientId) {
        boolean isFollowUpOrCarePlan = doc.documentType() != null
                && (doc.documentType().toLowerCase().contains("follow-up")
                    || doc.documentType().toLowerCase().contains("care-plan"));

        boolean patientIdMatches = normalizedPatientId != null
                && doc.patientId() != null
                && doc.patientId().toLowerCase().equals(normalizedPatientId);

        return isFollowUpOrCarePlan && patientIdMatches;
    }

    /**
     * Returns true if the document is an appointment document that applies to the given
     * patient (patient-specific match) or has no patientId (general guidelines).
     */
    private boolean isAppointmentDocForPatientOrGeneral(KnowledgeDocument doc, String normalizedPatientId) {
        boolean isAppointmentDoc = doc.documentType() != null
                && doc.documentType().toLowerCase().contains("appointment");

        if (!isAppointmentDoc) {
            return false;
        }

        // General appointment guideline (no patientId) — always include
        boolean isGeneral = doc.patientId() == null;

        // Patient-specific appointment document
        boolean patientIdMatches = normalizedPatientId != null
                && doc.patientId() != null
                && doc.patientId().toLowerCase().equals(normalizedPatientId);

        return isGeneral || patientIdMatches;
    }

    /**
     * Returns true if the document is a care task, care-plan, or discharge document
     * belonging to the given patient.
     */
    private boolean isCareTaskDocForPatient(KnowledgeDocument doc, String normalizedPatientId) {
        boolean isCareTaskType = doc.documentType() != null
                && (doc.documentType().toLowerCase().contains("task")
                    || doc.documentType().toLowerCase().contains("care-plan")
                    || doc.documentType().toLowerCase().contains("discharge"));

        boolean patientIdMatches = normalizedPatientId != null
                && doc.patientId() != null
                && doc.patientId().toLowerCase().equals(normalizedPatientId);

        return isCareTaskType && patientIdMatches;
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
