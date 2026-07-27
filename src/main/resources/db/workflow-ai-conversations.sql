CREATE TABLE IF NOT EXISTS ai_model_configs (
    id UUID PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    provider_type VARCHAR(64) NOT NULL,
    base_url VARCHAR(255) NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    credential_encrypted TEXT,
    enabled BOOLEAN NOT NULL,
    default_model BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE ai_model_configs
    ADD COLUMN IF NOT EXISTS credential_encrypted TEXT;

ALTER TABLE ai_model_configs
    ADD COLUMN IF NOT EXISTS evaluation_run_id UUID,
    ADD COLUMN IF NOT EXISTS evaluation_status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS evaluation_mode VARCHAR(32),
    ADD COLUMN IF NOT EXISTS evaluation_repetitions INTEGER,
    ADD COLUMN IF NOT EXISTS evaluation_completed_cases INTEGER,
    ADD COLUMN IF NOT EXISTS evaluation_total_cases INTEGER,
    ADD COLUMN IF NOT EXISTS evaluation_cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS evaluation_result JSONB,
    ADD COLUMN IF NOT EXISTS evaluation_error TEXT,
    ADD COLUMN IF NOT EXISTS evaluation_started_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS evaluation_finished_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE ai_model_configs
    ADD COLUMN IF NOT EXISTS structured_output_mode VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN';

-- Legacy releases used either a plaintext api_key or a deployment reference.
-- Neither can be converted to ciphertext without its plaintext value; operators
-- re-enter credentials through the write-only UI/API after the upgrade.
ALTER TABLE ai_model_configs
    DROP COLUMN IF EXISTS api_key;

ALTER TABLE ai_model_configs
    DROP CONSTRAINT IF EXISTS ai_model_configs_credential_ref_check;

ALTER TABLE ai_model_configs
    DROP COLUMN IF EXISTS credential_ref;

-- Hibernate creates an enum check constraint on fresh local databases, but ddl-auto=update
-- does not expand that constraint when a new enum value is added. Recreate it idempotently
-- so existing installations can store cloud OpenAI-compatible providers as well.
ALTER TABLE ai_model_configs
    DROP CONSTRAINT IF EXISTS ai_model_configs_provider_type_check;

ALTER TABLE ai_model_configs
    ADD CONSTRAINT ai_model_configs_provider_type_check
    CHECK (provider_type IN ('OPENAI_COMPATIBLE_LOCAL', 'OPENAI_COMPATIBLE_API'));

CREATE INDEX IF NOT EXISTS idx_ai_model_configs_enabled
    ON ai_model_configs (enabled);

CREATE INDEX IF NOT EXISTS idx_ai_model_configs_default
    ON ai_model_configs (default_model);

CREATE TABLE IF NOT EXISTS workflow_ai_conversations (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    custom_name VARCHAR(120),
    initial_instruction TEXT NOT NULL,
    model_config_id UUID
        REFERENCES ai_model_configs (id),
    workspace_kind VARCHAR(32) NOT NULL DEFAULT 'AI_CHAT',
    stage VARCHAR(64) NOT NULL,
    draft_asl JSONB,
    workspace_definition_text TEXT,
    final_plan JSONB,
    draft_workflow_payload JSONB,
    resource_plan JSONB,
    resource_plan_message_id UUID,
    canvas_layout JSONB,
    workspace_settings JSONB,
    workflow_id UUID,
    conversation_summary TEXT,
    summarized_through_message_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE workflow_ai_conversations
    ADD COLUMN IF NOT EXISTS conversation_summary TEXT;

ALTER TABLE workflow_ai_conversations
    ADD COLUMN IF NOT EXISTS custom_name VARCHAR(120);

ALTER TABLE workflow_ai_conversations
    ADD COLUMN IF NOT EXISTS workspace_kind VARCHAR(32) NOT NULL DEFAULT 'AI_CHAT';

-- A manual draft exists before the user chooses an AI model. The same row acquires a model when
-- AI is first used, keeping the canonical /draft/{id} workspace instead of creating /c/{id}.
ALTER TABLE workflow_ai_conversations
    ALTER COLUMN model_config_id DROP NOT NULL;

ALTER TABLE workflow_ai_conversations
    ADD COLUMN IF NOT EXISTS workspace_definition_text TEXT;

ALTER TABLE workflow_ai_conversations
    ADD COLUMN IF NOT EXISTS summarized_through_message_id UUID;

ALTER TABLE workflow_ai_conversations
    ADD COLUMN IF NOT EXISTS canvas_layout JSONB;

ALTER TABLE workflow_ai_conversations
    ADD COLUMN IF NOT EXISTS workspace_settings JSONB;

-- Once a chat creates a workflow, all later saves from /c/{id} target this workflow's
-- revision history instead of replaying the idempotent create request.
ALTER TABLE workflow_ai_conversations
    ADD COLUMN IF NOT EXISTS workflow_id UUID;

-- Functions/MCP capabilities the model proposed but that don't yet exist in the catalog, held
-- while the user reviews and approves them before any workflow ASL is generated.
ALTER TABLE workflow_ai_conversations
    ADD COLUMN IF NOT EXISTS resource_plan JSONB;

-- Keeps a pending resource proposal anchored to the assistant turn that first requested it. A UUID
-- (rather than a foreign key) avoids a circular conversation/message creation dependency.
ALTER TABLE workflow_ai_conversations
    ADD COLUMN IF NOT EXISTS resource_plan_message_id UUID;

-- Hibernate creates an enum check constraint on the stage column on fresh local databases, but
-- ddl-auto=update does not expand it when a new stage value is added. Recreate it idempotently so
-- existing installations can persist the RESOURCES_PROPOSED stage.
ALTER TABLE workflow_ai_conversations
    DROP CONSTRAINT IF EXISTS workflow_ai_conversations_stage_check;

ALTER TABLE workflow_ai_conversations
    ADD CONSTRAINT workflow_ai_conversations_stage_check
    CHECK (stage IN (
        'COLLECTING_WORKFLOW_DETAILS',
        'RESOURCES_PROPOSED',
        'ASL_READY',
        'ASL_UNDER_REVIEW',
        'COLLECTING_SCHEDULE_DETAILS',
        'PLAN_READY',
        'ACCEPTED'
    ));

CREATE INDEX IF NOT EXISTS idx_workflow_ai_conversations_workflow
    ON workflow_ai_conversations (workflow_id);

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
