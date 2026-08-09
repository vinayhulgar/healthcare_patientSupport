package com.healthcare.navigator.rag;

import com.healthcare.navigator.knowledge.KnowledgeDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a document's text content into overlapping fixed-size chunks using a
 * word-based sliding window (1 word ≈ 1 token).
 *
 * <ul>
 *   <li>Window size: 512 words per chunk</li>
 *   <li>Overlap:     64 words between consecutive chunks</li>
 * </ul>
 *
 * <p>Each produced {@link TextChunk} carries the document's metadata so that
 * downstream embedding and retrieval components can surface provenance information.
 */
@Component
public class DocumentChunker {

    /** Number of words (approximate tokens) per chunk. */
    static final int CHUNK_SIZE = 512;

    /** Number of overlapping words between consecutive chunks. */
    static final int OVERLAP = 64;

    /**
     * Splits {@code fullContent} into overlapping {@link TextChunk}s.
     *
     * <p>Words are obtained by splitting on whitespace. Chunks advance by
     * {@code CHUNK_SIZE - OVERLAP} words at a time. A trailing chunk is always
     * emitted for any remaining words.
     *
     * @param doc         metadata record for the source document
     * @param fullContent the complete text to chunk (e.g. the {@code content} field of the JSON)
     * @return ordered list of chunks; empty list if {@code fullContent} is blank
     */
    public List<TextChunk> chunk(KnowledgeDocument doc, String fullContent) {
        List<TextChunk> chunks = new ArrayList<>();

        if (fullContent == null || fullContent.isBlank()) {
            return chunks;
        }

        String[] words = fullContent.strip().split("\\s+");

        if (words.length == 0) {
            return chunks;
        }

        int step = CHUNK_SIZE - OVERLAP;   // advance by (512 - 64) = 448 words per chunk
        int chunkIndex = 0;

        for (int start = 0; start < words.length; start += step) {
            int end = Math.min(start + CHUNK_SIZE, words.length);
            String content = String.join(" ", java.util.Arrays.copyOfRange(words, start, end));

            chunks.add(new TextChunk(
                    doc.documentName(),
                    doc.documentType(),
                    chunkIndex++,
                    doc.patientId(),
                    doc.creationDate(),
                    content
            ));

            // If this chunk reached the end of the document, stop
            if (end == words.length) {
                break;
            }
        }

        return chunks;
    }
}
