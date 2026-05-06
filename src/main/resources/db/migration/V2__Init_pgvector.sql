-- ============================================================
-- V2: Initialize pgvector Extension and API Vector Store Table
-- Run this script manually on your PostgreSQL database:
--   ai_toolcheck_vector
-- ============================================================

-- Step 1: Enable the pgvector extension (requires pg_vector to be installed on the server)
CREATE EXTENSION IF NOT EXISTS vector;

-- Step 2: Create the main table for storing API endpoint embeddings
-- The `embedding` column uses vector(1024) to match the output
-- dimension of the mxbai-embed-large model.
CREATE TABLE IF NOT EXISTS api_vector_store (
    id            UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    source_id     VARCHAR(255),          -- Optional: ID of the API Endpoint from MySQL for traceability
    content       TEXT        NOT NULL,  -- The raw text that was embedded (openApiFragment + enriched output)
    embedding     vector(1024) NOT NULL, -- The 1024-dimensional embedding from mxbai-embed-large
    created_at    TIMESTAMP   NOT NULL DEFAULT now()
);

-- Step 3: Create an IVFFLAT index for fast approximate nearest-neighbor search
-- This index is critical for performance when the table grows large.
-- `lists` = sqrt(number of rows) is a good starting point. Start with 100.
CREATE INDEX IF NOT EXISTS idx_api_vector_store_embedding
    ON api_vector_store
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

-- Verification query (run after migration to confirm setup)
-- SELECT COUNT(*) FROM api_vector_store;
-- SELECT pg_size_pretty(pg_total_relation_size('api_vector_store'));
