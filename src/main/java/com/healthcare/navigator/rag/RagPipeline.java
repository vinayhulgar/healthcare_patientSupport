package com.healthcare.navigator.rag;

import com.healthcare.navigator.knowledge.KnowledgeBaseLoader;
import com.healthcare.navigator.knowledge.KnowledgeDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Orchestrates the full Retrieval-Augmented Generation (RAG) pipeline:
 *
 * <ol>
 *   <li><b>Indexing</b> (on startup, after {@link KnowledgeBaseLoader}): reads raw JSON
 *       from the knowledge-base resources, chunks each document via {@link DocumentChunker},
 *       embeds the chunks via {@link EmbeddingService}, and upserts into the
 *       vector store via {@link VectorStoreService}.</li>
 *   <li><b>Retrieval</b>: embeds a natural-language query and returns the top-K most
 *       similar chunks from the vector store.</li>
 * </ol>
 *
 * <p>Per-document embedding failures during indexing are logged and skipped — startup
 * is never halted by a single document failure.
 */
@Component
public class RagPipeline {

    private static final Logger log = LoggerFactory.getLogger(RagPipeline.class);

    private final KnowledgeBaseLoader knowledgeBaseLoader;
    private final DocumentChunker documentChunker;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;

    @Value("${rag.top-k:5}")
    private int defaultTopK;

    @Value("${rag.similarity-threshold:0.7}")
    private double defaultThreshold;

    public RagPipeline(KnowledgeBaseLoader knowledgeBaseLoader,
                       DocumentChunker documentChunker,
                       EmbeddingService embeddingService,
                       VectorStoreService vectorStoreService) {
        this.knowledgeBaseLoader = knowledgeBaseLoader;
        this.documentChunker = documentChunker;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
    }

    /**
     * Indexes all documents in the knowledge base into the vector store.
     *
     * <p>Runs after {@link KnowledgeBaseLoader#loadKnowledgeBase()} (guaranteed by
     * {@link Order}). For each {@link KnowledgeDocument} in the catalog:
     * <ol>
     *   <li>Reads the raw JSON content from {@code classpath:knowledge-base/<documentName>.json}</li>
     *   <li>Chunks the content via {@link DocumentChunker}</li>
     *   <li>Embeds all chunks in batch via {@link EmbeddingService}</li>
     *   <li>Upserts the chunks and embeddings via {@link VectorStoreService}</li>
     * </ol>
     *
     * <p>Any failure for a specific document is logged at ERROR level and processing
     * continues with the next document.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(10) // Run after KnowledgeBaseLoader (which has default order)
    public void indexKnowledgeBase() {
        var catalog = knowledgeBaseLoader.getCatalog();

        if (catalog.isEmpty()) {
            log.warn("RAG pipeline: knowledge base catalog is empty — nothing to index");
            return;
        }

        log.info("RAG pipeline: starting indexing of {} documents", catalog.size());

        int indexed = 0;
        int failed = 0;

        for (KnowledgeDocument doc : catalog.values()) {
            try {
                String rawJson = readClasspathResource(
                        "knowledge-base/" + doc.documentName() + ".json");

                // Extract the content field from the raw JSON using simple parsing.
                // We read the full JSON text as the chunk content so the entire document
                // (including metadata fields) is available for retrieval.
                String contentToChunk = extractContentField(rawJson, doc.documentName());

                List<TextChunk> chunks = documentChunker.chunk(doc, contentToChunk);

                if (chunks.isEmpty()) {
                    log.warn("RAG pipeline: no chunks produced for document '{}'", doc.documentName());
                    continue;
                }

                // Extract text content from each chunk for batch embedding
                List<String> chunkTexts = chunks.stream()
                        .map(TextChunk::content)
                        .toList();

                float[][] embeddings = embeddingService.embed(chunkTexts);

                vectorStoreService.upsert(chunks, embeddings);

                log.info("RAG pipeline: indexed document '{}' ({} chunks)", doc.documentName(), chunks.size());
                indexed++;

            } catch (Exception e) {
                log.error("RAG pipeline: failed to index document '{}': {}", doc.documentName(), e.getMessage());
                failed++;
            }
        }

        log.info("RAG pipeline: indexing complete — {} indexed, {} failed", indexed, failed);
    }

    /**
     * Retrieves the most relevant document chunks for the given query text.
     *
     * <p>Emits a structured log entry after retrieval:
     * <ul>
     *   <li>On results: {@code correlationId}, {@code queryId}, {@code k},
     *       {@code chunksRetrieved}, {@code similarityScores[]}</li>
     *   <li>On empty result: {@code correlationId}, {@code queryId},
     *       {@code chunksRetrieved: 0} — no similarity scores logged (Req. 7.6)</li>
     * </ul>
     *
     * @param queryText the natural-language query from the patient or agent
     * @param k         maximum number of results to return
     * @param threshold minimum similarity score (0.0 – 1.0)
     * @param queryId   opaque identifier for correlation logging
     * @return ordered list of retrieval results (highest similarity first); may be empty
     */
    public List<RetrievalResult> retrieve(String queryText, int k, double threshold, String queryId) {
        float[] queryEmbedding = embeddingService.embed(queryText);
        List<RetrievalResult> results = vectorStoreService.findTopK(queryText, queryEmbedding, k, threshold, queryId);
        logRetrievalResult(queryId, k, results);
        return results;
    }

    /**
     * Retrieves the most relevant document chunks using the configured default top-K and threshold.
     *
     * @param queryText the natural-language query
     * @param queryId   opaque identifier for correlation logging
     * @return ordered list of retrieval results; may be empty
     */
    public List<RetrievalResult> retrieve(String queryText, String queryId) {
        return retrieve(queryText, defaultTopK, defaultThreshold, queryId);
    }

    // ── structured log helpers ────────────────────────────────────────────────

    /**
     * Emits the structured RAG retrieval log event (Requirements 11.4, 7.5, 7.6).
     *
     * <ul>
     *   <li>Results present → include {@code similarityScores} array</li>
     *   <li>Zero results → omit similarity scores entirely</li>
     * </ul>
     */
    private void logRetrievalResult(String queryId, int k, List<RetrievalResult> results) {
        String correlationId = MDC.get("correlationId");
        int chunksRetrieved = results.size();

        if (chunksRetrieved == 0) {
            log.info("RAG retrieval: correlationId={} queryId={} chunksRetrieved=0",
                    correlationId, queryId);
        } else {
            List<Double> scores = results.stream()
                    .map(RetrievalResult::similarityScore)
                    .collect(Collectors.toList());
            log.info("RAG retrieval: correlationId={} queryId={} k={} chunksRetrieved={} similarityScores={}",
                    correlationId, queryId, k, chunksRetrieved, scores);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String readClasspathResource(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        try (var is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Extracts the {@code content} field from a JSON string using a simple search.
     * Falls back to the full raw JSON if no {@code content} field is found.
     *
     * <p>Uses Jackson-free string extraction to avoid circular dependency on ObjectMapper
     * (which is already used by {@link KnowledgeBaseLoader}).
     *
     * @param rawJson      the full JSON text
     * @param documentName document name used in warning log
     * @return the value of the {@code content} field, or the full JSON as fallback
     */
    private String extractContentField(String rawJson, String documentName) {
        // Look for "content": "..." — use a basic extraction approach
        // to avoid re-parsing the entire JSON hierarchy with ObjectMapper here.
        // The content field value may contain escaped quotes and newlines.
        String contentKey = "\"content\"";
        int keyIdx = rawJson.indexOf(contentKey);
        if (keyIdx < 0) {
            log.warn("RAG pipeline: document '{}' has no 'content' field — using full JSON", documentName);
            return rawJson;
        }

        // Find the colon after the key
        int colonIdx = rawJson.indexOf(':', keyIdx + contentKey.length());
        if (colonIdx < 0) {
            return rawJson;
        }

        // Find the opening quote of the value
        int openQuoteIdx = rawJson.indexOf('"', colonIdx + 1);
        if (openQuoteIdx < 0) {
            return rawJson;
        }

        // Find the closing quote, respecting escaped characters
        StringBuilder content = new StringBuilder();
        int i = openQuoteIdx + 1;
        while (i < rawJson.length()) {
            char c = rawJson.charAt(i);
            if (c == '\\' && i + 1 < rawJson.length()) {
                char next = rawJson.charAt(i + 1);
                switch (next) {
                    case '"' -> content.append('"');
                    case '\\' -> content.append('\\');
                    case 'n' -> content.append('\n');
                    case 'r' -> content.append('\r');
                    case 't' -> content.append('\t');
                    default -> content.append(next);
                }
                i += 2;
            } else if (c == '"') {
                break;
            } else {
                content.append(c);
                i++;
            }
        }

        String result = content.toString().strip();
        return result.isBlank() ? rawJson : result;
    }
}
