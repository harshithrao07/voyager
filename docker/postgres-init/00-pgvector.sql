-- Runs once at Postgres container initialization (empty data dir) via
-- docker-entrypoint-initdb.d, before the app connects. Guarantees the `vector`
-- extension exists so Hibernate can build the resource_embeddings vector column.
CREATE EXTENSION IF NOT EXISTS vector;
