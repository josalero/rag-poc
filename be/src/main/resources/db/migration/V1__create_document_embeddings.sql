-- pgvector extension (run once per database)
CREATE EXTENSION IF NOT EXISTS vector;

-- Table for LangChain4j PgVectorEmbeddingStore (app.pgvector.table=document_embeddings).
-- Dimension 1536 matches OpenAI text-embedding-3-small; must match your embedding model.
CREATE TABLE IF NOT EXISTS document_embeddings (
    embedding_id UUID PRIMARY KEY,
    embedding vector(1536) NOT NULL,
    text TEXT,
    metadata JSONB
);
