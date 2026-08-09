package com.healthcare.navigator.rag;

/**
 * A single ranked result returned by the vector store similarity search.
 *
 * @param documentName    the name of the source document
 * @param documentType    the type/category of the source document
 * @param chunkIndex      zero-based position of the chunk within the source document
 * @param content         the raw text content of the retrieved chunk
 * @param similarityScore the cosine similarity score (0.0 – 1.0); higher is more similar
 * @param patientId       the patient this document belongs to (null for general documents)
 */
public record RetrievalResult(
        String documentName,
        String documentType,
        int chunkIndex,
        String content,
        double similarityScore,
        String patientId
) {}
