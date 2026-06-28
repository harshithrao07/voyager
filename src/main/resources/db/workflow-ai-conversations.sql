CREATE TABLE IF NOT EXISTS ai_model_configs (
    id UUID PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    provider_type VARCHAR(64) NOT NULL,
    base_url VARCHAR(255) NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    api_key VARCHAR(1024),
    enabled BOOLEAN NOT NULL,
    default_model BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE ai_model_configs
    ADD COLUMN IF NOT EXISTS api_key VARCHAR(1024);

CREATE INDEX IF NOT EXISTS idx_ai_model_configs_enabled
    ON ai_model_configs (enabled);

CREATE INDEX IF NOT EXISTS idx_ai_model_configs_default
    ON ai_model_configs (default_model);

CREATE TABLE IF NOT EXISTS workflow_ai_conversations (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    initial_instruction TEXT NOT NULL,
    model_config_id UUID NOT NULL
        REFERENCES ai_model_configs (id),
    stage VARCHAR(64) NOT NULL,
    draft_asl JSONB,
    final_plan JSONB,
    draft_workflow_payload JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_workflow_ai_conversations_stage
    ON workflow_ai_conversations (stage);

CREATE INDEX IF NOT EXISTS idx_workflow_ai_conversations_updated
    ON workflow_ai_conversations (updated_at);

CREATE TABLE IF NOT EXISTS workflow_ai_messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL
        REFERENCES workflow_ai_conversations (id),
    model_config_id UUID
        REFERENCES ai_model_configs (id),
    role VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    structured_payload JSONB,
    thinking_content TEXT,
    duration_ms BIGINT,
    input_tokens INTEGER,
    output_tokens INTEGER,
    total_tokens INTEGER,
    finish_reason VARCHAR(128),
    regenerated_from_message_id UUID
        REFERENCES workflow_ai_messages (id),
    metadata_json JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE workflow_ai_messages
    ADD COLUMN IF NOT EXISTS model_config_id UUID REFERENCES ai_model_configs (id);

ALTER TABLE workflow_ai_messages
    ADD COLUMN IF NOT EXISTS thinking_content TEXT;

ALTER TABLE workflow_ai_messages
    ADD COLUMN IF NOT EXISTS duration_ms BIGINT;

ALTER TABLE workflow_ai_messages
    ADD COLUMN IF NOT EXISTS input_tokens INTEGER;

ALTER TABLE workflow_ai_messages
    ADD COLUMN IF NOT EXISTS output_tokens INTEGER;

ALTER TABLE workflow_ai_messages
    ADD COLUMN IF NOT EXISTS total_tokens INTEGER;

ALTER TABLE workflow_ai_messages
    ADD COLUMN IF NOT EXISTS finish_reason VARCHAR(128);

ALTER TABLE workflow_ai_messages
    ADD COLUMN IF NOT EXISTS regenerated_from_message_id UUID REFERENCES workflow_ai_messages (id);

ALTER TABLE workflow_ai_messages
    ADD COLUMN IF NOT EXISTS metadata_json JSONB;

UPDATE workflow_ai_messages message
SET model_config_id = conversation.model_config_id
FROM workflow_ai_conversations conversation
WHERE message.conversation_id = conversation.id
  AND message.model_config_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_workflow_ai_messages_conversation_created
    ON workflow_ai_messages (conversation_id, created_at);
