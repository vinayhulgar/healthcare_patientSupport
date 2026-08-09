package com.healthcare.navigator.knowledge;

import java.time.LocalDate;

/**
 * Metadata record for a document in the knowledge base.
 *
 * @param documentName the unique name of the document
 * @param documentType the type/category of the document (e.g., "discharge", "medication")
 * @param creationDate the ISO 8601 creation date of the document
 * @param patientId    the patient this document belongs to (null for general documents)
 */
public record KnowledgeDocument(
        String documentName,
        String documentType,
        LocalDate creationDate,
        String patientId
) {}
