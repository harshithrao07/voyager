-- One-time schema upgrade for inline encrypted AI/MCP secrets and MCP custom headers.
-- Run this before starting the upgraded production application when
-- spring.jpa.hibernate.ddl-auto=validate. The statements are idempotent.

ALTER TABLE ai_model_configs
    ADD COLUMN IF NOT EXISTS credential_encrypted TEXT;

ALTER TABLE mcp_servers
    ADD COLUMN IF NOT EXISTS auth_token_encrypted TEXT,
    ADD COLUMN IF NOT EXISTS secret_env TEXT,
    ADD COLUMN IF NOT EXISTS secret_headers TEXT;

-- Hibernate's enum check constraint is not expanded by ddl-auto=update.
ALTER TABLE mcp_servers
    DROP CONSTRAINT IF EXISTS mcp_servers_auth_type_check;
ALTER TABLE mcp_servers
    ADD CONSTRAINT mcp_servers_auth_type_check
    CHECK (auth_type IN ('NONE', 'BEARER_TOKEN', 'API_KEY', 'BASIC', 'CUSTOM_HEADERS'));

-- References cannot be converted to encrypted values without the corresponding
-- plaintext. Re-enter those credentials through the write-only UI/API after the
-- upgrade; the obsolete reference columns contain names, not secret material.
ALTER TABLE ai_model_configs
    DROP CONSTRAINT IF EXISTS ai_model_configs_credential_ref_check;
ALTER TABLE ai_model_configs
    DROP COLUMN IF EXISTS credential_ref;
ALTER TABLE mcp_servers
    DROP COLUMN IF EXISTS auth_token_ref;
