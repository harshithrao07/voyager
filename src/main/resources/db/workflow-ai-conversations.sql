CREATE TABLE IF NOT EXISTS ai_model_configs (
    id UUID PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    provider_type VARCHAR(64) NOT NULL,
    base_url VARCHAR(255) NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL,
    default_model BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

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
    role VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    structured_payload JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_workflow_ai_messages_conversation_created
    ON workflow_ai_messages (conversation_id, created_at);
