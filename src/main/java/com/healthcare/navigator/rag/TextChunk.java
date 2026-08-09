package com.healthcare.navigator.rag;

import java.time.LocalDate;

/**
 * A fixed-size sliding-window chunk of text extracted from a {@link com.healthcare.navigator.knowledge.KnowledgeDocument}.
 *
 * @param documentName the name of the source document
 * @param documentType the type/category of the source document
 * @param chunkIndex   zero-based position of this chunk within the document
 * @param patientId    the patient this document belongs to (null for general documents)
 * @param creationDate the creation date of the source document
 * @param content      the raw text content of this chunk
 */
public record TextChunk(
        String documentName,
        String documentType,
        int chunkIndex,
        String patientId,
        LocalDate creationDate,
        String content
) {}
