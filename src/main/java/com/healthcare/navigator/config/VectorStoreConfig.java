package com.healthcare.navigator.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Spring configuration for the PgVector vector store used in the RAG pipeline.
 *
 * <p>Overrides the Spring AI auto-configuration to target the {@code document_chunks}
 * table defined by the healthcare-navigator schema, with 1536-dimensional embeddings
 * (matching {@code text-embedding-ada-002}) and cosine-distance similarity.
 *
 * <p>The {@code EmbeddingModel} bean is provided by the Spring AI OpenAI auto-configuration
 * ({@code spring-ai-starter-model-openai}). A {@code @ConditionalOnMissingBean} guard is
 * included so this class remains safe to use in contexts where the auto-configuration is
 * absent (e.g. tests with a manual mock).
 */
@Configuration
public class VectorStoreConfig {

    /**
     * PgVectorStore bean configured for the {@code document_chunks} table.
     *
     * <p>Configuration values:
     * <ul>
     *   <li>Table: {@code document_chunks}</li>
     *   <li>Embedding column: {@code embedding} (fixed by the PgVectorStore schema)</li>
     *   <li>Dimensions: 1536 (OpenAI {@code text-embedding-ada-002})</li>
     *   <li>Distance type: {@code COSINE_DISTANCE}</li>
     *   <li>Index type: {@code HNSW}</li>
     *   <li>Schema initialisation: disabled — schema is managed by Flyway/manual DDL</li>
     * </ul>
     *
     * <p>The {@code @ConditionalOnMissingBean} annotation ensures that the Spring AI
     * auto-configuration's default {@code vectorStore} bean is replaced by this one
     * without causing a duplicate-bean conflict.
     *
     * @param jdbcTemplate  the auto-configured JDBC template for the application datasource
     * @param embeddingModel the OpenAI embedding model bean (auto-configured by Spring AI)
     * @return a fully configured {@link PgVectorStore} instance
     */
    @Bean
    @ConditionalOnMissingBean(PgVectorStore.class)
    public PgVectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("document_chunks")
                .dimensions(1536)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(false)
                .build();
    }

    /**
     * Guard bean that surfaces an explicit {@code EmbeddingModel} only when none is
     * provided by auto-configuration. In production the OpenAI starter wires this
     * automatically; this method exists solely so the application context does not fail
     * in minimal test configurations where the OpenAI starter is excluded.
     *
     * @param embeddingModel the embedding model to re-expose
     * @return the same {@link EmbeddingModel} instance
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel embeddingModel(EmbeddingModel embeddingModel) {
        return embeddingModel;
    }
}
