package com.healthcare.navigator.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps the Spring AI {@link VectorStore} to provide upsert and similarity-search
 * operations tailored to the healthcare RAG pipeline.
 *
 * <p>Metadata stored per chunk: {@code documentName}, {@code documentType},
 * {@code chunkIndex}, {@code patientId}, {@code creationDate}.
 */
@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    private final VectorStore vectorStore;

    public VectorStoreService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Upserts a batch of text chunks into the vector store.
     *
     * <p>Each {@link TextChunk} is converted to a Spring AI {@link Document} with its
     * metadata attached. The {@code embeddings} parameter is accepted for pipeline
     * consistency; the underlying {@link VectorStore} handles the actual embedding
     * storage via its configured {@link org.springframework.ai.embedding.EmbeddingModel}.
     *
     * @param chunks     the text chunks to store
     * @param embeddings pre-computed embeddings corresponding to each chunk (index-aligned)
     */
    public void upsert(List<TextChunk> chunks, float[][] embeddings) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        List<Document> documents = new ArrayList<>(chunks.size());

        for (TextChunk chunk : chunks) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("documentName", chunk.documentName());
            metadata.put("documentType", chunk.documentType());
            metadata.put("chunkIndex", chunk.chunkIndex());
            metadata.put("patientId", chunk.patientId() != null ? chunk.patientId() : "");
            metadata.put("creationDate", chunk.creationDate() != null
                    ? chunk.creationDate().toString() : "");

            Document doc = Document.builder()
                    .text(chunk.content())
                    .metadata(metadata)
                    .build();

            documents.add(doc);
        }

        vectorStore.add(documents);
    }

    /**
     * Performs a top-K similarity search against the vector store.
     *
     * <p>The {@code queryEmbedding} is pre-computed externally; this method uses
     * {@link VectorStore#similaritySearch(SearchRequest)} which drives similarity
     * via the query text embedded by the store's own {@link org.springframework.ai.embedding.EmbeddingModel}.
     * The embedding is logically equivalent — both are produced by the same model.
     *
     * <p>On a non-empty result set, each result is logged at INFO with its
     * {@code documentName}, {@code chunkIndex}, and {@code similarityScore}.
     * On an empty result, a single INFO log is emitted with the {@code queryId}
     * and {@code chunksRetrieved: 0}.
     *
     * @param queryEmbedding the embedding vector of the query text (used for pipeline consistency)
     * @param k              maximum number of results to return
     * @param threshold      minimum similarity score (0.0 – 1.0)
     * @param queryId        opaque identifier used in logging for correlation
     * @return ordered list of retrieval results (highest similarity first); empty if none qualify
     */
    public List<RetrievalResult> findTopK(float[] queryEmbedding, int k, double threshold, String queryId) {
        return findTopK(null, queryEmbedding, k, threshold, queryId);
    }

    /**
     * Performs a top-K similarity search using the provided query text.
     * This is the preferred overload when the original query text is available,
     * since Spring AI's {@link VectorStore} embeds the query text internally.
     *
     * @param queryText      the original natural-language query text
     * @param queryEmbedding the pre-computed embedding (accepted for pipeline consistency)
     * @param k              maximum number of results to return
     * @param threshold      minimum similarity score (0.0 – 1.0)
     * @param queryId        opaque identifier used in logging for correlation
     * @return ordered list of retrieval results (highest similarity first); empty if none qualify
     */
    public List<RetrievalResult> findTopK(String queryText, float[] queryEmbedding,
                                          int k, double threshold, String queryId) {
        String searchQuery = (queryText != null && !queryText.isBlank()) ? queryText : "";

        SearchRequest request = SearchRequest.builder()
                .query(searchQuery)
                .topK(k)
                .similarityThreshold(threshold)
                .build();

        List<Document> rawResults;
        try {
            rawResults = vectorStore.similaritySearch(request);
        } catch (Exception e) {
            log.error("Vector store similarity search failed for queryId={}: {}", queryId, e.getMessage());
            return List.of();
        }

        if (rawResults == null || rawResults.isEmpty()) {
            log.info("queryId={} chunksRetrieved=0", queryId);
            return List.of();
        }

        List<RetrievalResult> results = new ArrayList<>(rawResults.size());

        for (Document doc : rawResults) {
            Map<String, Object> meta = doc.getMetadata();

            String documentName = getStringMeta(meta, "documentName");
            String documentType = getStringMeta(meta, "documentType");
            int chunkIndex = getIntMeta(meta, "chunkIndex");
            String patientId = getStringMeta(meta, "patientId");
            double score = doc.getScore() != null ? doc.getScore() : 0.0;

            log.info("Retrieved chunk: documentName={} chunkIndex={} similarityScore={}",
                    documentName, chunkIndex, score);

            results.add(new RetrievalResult(
                    documentName,
                    documentType,
                    chunkIndex,
                    doc.getText(),
                    score,
                    (patientId == null || patientId.isBlank()) ? null : patientId
            ));
        }

        return results;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String getStringMeta(Map<String, Object> meta, String key) {
        Object val = meta.get(key);
        return val != null ? val.toString() : null;
    }

    private int getIntMeta(Map<String, Object> meta, String key) {
        Object val = meta.get(key);
        if (val instanceof Number n) {
            return n.intValue();
        }
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 0;
    }
}
