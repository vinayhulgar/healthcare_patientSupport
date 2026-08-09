package com.healthcare.navigator.domain;

/**
 * Represents a citation to a source document used in an agent response.
 *
 * @param documentName the name of the source document
 * @param documentType the type/category of the document
 * @param section      the specific section within the document (nullable)
 */
public record SourceCitation(
        String documentName,
        String documentType,
        String section
) {}
