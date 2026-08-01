-- Runs at Testcontainers Postgres startup (before Hibernate DDL) so the vector type
-- exists when the resource_embeddings table is created with ddl-auto=create.
CREATE EXTENSION IF NOT EXISTS vector;
