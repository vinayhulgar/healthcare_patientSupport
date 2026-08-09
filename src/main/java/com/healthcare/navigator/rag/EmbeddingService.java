package com.healthcare.navigator.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Thin wrapper around the Spring AI {@link EmbeddingModel} that provides
 * both batch and single-text embedding operations for the RAG pipeline.
 *
 * <p>All calls are wrapped so that any underlying exception is re-thrown as a
 * {@link RuntimeException} preserving the original cause.
 */
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * Embeds a list of text chunks in a single batch call.
     *
     * @param texts the texts to embed; must not be null or empty
     * @return a 2-D array where {@code result[i]} is the embedding vector for {@code texts.get(i)}
     * @throws RuntimeException if the embedding model call fails
     */
    public float[][] embed(List<String> texts) {
        try {
            List<float[]> embeddings = embeddingModel.embed(texts);
            return embeddings.toArray(new float[0][]);
        } catch (Exception e) {
            throw new RuntimeException("Batch embedding failed: " + e.getMessage(), e);
        }
    }

    /**
     * Embeds a single query text.
     *
     * @param text the text to embed; must not be null
     * @return the embedding vector
     * @throws RuntimeException if the embedding model call fails
     */
    public float[] embed(String text) {
        try {
            return embeddingModel.embed(text);
        } catch (Exception e) {
            throw new RuntimeException("Single embedding failed: " + e.getMessage(), e);
        }
    }
}
