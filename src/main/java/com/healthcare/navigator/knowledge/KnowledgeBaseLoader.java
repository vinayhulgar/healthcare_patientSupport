package com.healthcare.navigator.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads all JSON files from {@code classpath:knowledge-base/} at application startup
 * and builds an in-memory catalog of {@link KnowledgeDocument} metadata records.
 *
 * <p>Each file is parsed for its top-level {@code documentType}, {@code creationDate}
 * (ISO 8601), and optionally {@code patientId} fields. The document name is derived
 * from the filename (without extension).
 *
 * <p>Parse failures are logged and skipped — startup is never halted.
 */
@Component
public class KnowledgeBaseLoader {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseLoader.class);

    private final ObjectMapper objectMapper;

    /**
     * Volatile map populated once during {@link #loadKnowledgeBase()}.
     * After that event the map is replaced with an unmodifiable view.
     */
    private volatile Map<String, KnowledgeDocument> catalog = Collections.emptyMap();

    public KnowledgeBaseLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Scans {@code classpath:knowledge-base/*.json} and populates the in-memory catalog.
     * Called automatically once the Spring application context is fully initialised.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadKnowledgeBase() {
        Map<String, KnowledgeDocument> loaded = new HashMap<>();

        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:knowledge-base/*.json");

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                // Strip .json extension to derive the document name
                String documentName = filename.endsWith(".json")
                        ? filename.substring(0, filename.length() - 5)
                        : filename;

                try (InputStream is = resource.getInputStream()) {
                    JsonNode root = objectMapper.readTree(is);

                    String documentType = getTextOrNull(root, "documentType");
                    String creationDateStr = getTextOrNull(root, "creationDate");
                    String patientId = getTextOrNull(root, "patientId");

                    if (documentType == null || creationDateStr == null) {
                        log.warn("Skipping knowledge-base document '{}': missing required fields "
                                + "(documentType={}, creationDate={})",
                                documentName, documentType, creationDateStr);
                        continue;
                    }

                    LocalDate creationDate = LocalDate.parse(creationDateStr);

                    KnowledgeDocument doc = new KnowledgeDocument(
                            documentName,
                            documentType,
                            creationDate,
                            patientId
                    );
                    loaded.put(documentName, doc);
                    log.info("Loaded knowledge-base document: {}", documentName);

                } catch (Exception e) {
                    log.error("Failed to parse knowledge-base document '{}': {}", documentName, e.getMessage());
                    // Continue loading remaining documents
                }
            }
        } catch (Exception e) {
            log.error("Failed to scan knowledge-base resources: {}", e.getMessage());
        }

        this.catalog = Collections.unmodifiableMap(loaded);
        log.info("Knowledge base loaded: {} documents", this.catalog.size());
    }

    /**
     * Returns an unmodifiable view of the in-memory document catalog, keyed by document name.
     *
     * @return the catalog map (never null, may be empty before startup completes)
     */
    public Map<String, KnowledgeDocument> getCatalog() {
        return catalog;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String getTextOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return (node != null && !node.isNull() && node.isTextual()) ? node.asText() : null;
    }
}
