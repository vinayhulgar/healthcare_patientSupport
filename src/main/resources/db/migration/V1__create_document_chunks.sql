-- V1__create_document_chunks.sql
-- Flyway / manual init script for the Healthcare Care Navigator
-- Requirement 7.3 | Design: §pgvector Schema
--
-- Ensures the required PostgreSQL extensions are available before creating
-- the document_chunks table and its IVFFlat similarity-search index.

-- Enable uuid-ossp so gen_random_uuid() is available (also provided by pgcrypto;
-- both are included here for maximum compatibility across PG versions).
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Enable the pgvector extension for the vector data type and IVFFlat indexing.
CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------------
-- Table: document_chunks
--
-- Stores text chunks derived from knowledge-base documents together with
-- their 1536-dimension OpenAI embedding vectors.  NULL patient_id indicates
-- a general (non-patient-specific) document.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS document_chunks (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    document_name TEXT        NOT NULL,
    document_type TEXT        NOT NULL,
    patient_id    TEXT,                             -- NULL for general documents
    creation_date DATE        NOT NULL,
    chunk_index   INT         NOT NULL,
    content       TEXT        NOT NULL,
    embedding     vector(1536),                     -- dimension matches text-embedding-ada-002
    created_at    TIMESTAMPTZ DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Index: IVFFlat cosine-distance index for approximate nearest-neighbour search
--
-- lists = 100 is a reasonable default for datasets up to ~1 M rows.
-- Rebuild or increase lists when the table grows significantly.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS document_chunks_embedding_idx
    ON document_chunks
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
