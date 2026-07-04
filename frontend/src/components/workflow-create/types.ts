import type { WorkflowAiStage, WorkflowPriorityDTO } from '../../api';

export type DefinitionMode = 'manual' | 'ai';

export type AiModel = {
  id: string;
  label: string;
  endpoint: string;
  modelName?: string;
  provider: 'local' | 'api';
  enabled?: boolean;
  defaultModel?: boolean;
};

export type ChatMessage = {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  createdAt: number;
  modelConfigId?: string | null;
  modelDisplayName?: string | null;
  durationMs?: number | null;
  inputTokens?: number | null;
  outputTokens?: number | null;
  totalTokens?: number | null;
  thinkingContent?: string | null;
  finishReason?: string | null;
  regeneratedFromMessageId?: string | null;
  streamingStatus?: 'processing' | 'streaming';
  streamingPhase?: 'thinking' | 'answer';
};

export type ChatSnapshot = {
  messages: ChatMessage[];
  isEditorOpen: boolean;
  conversationId: string | null;
  conversationStage: WorkflowAiStage;
  name: string;
  priority: WorkflowPriorityDTO;
  maxAttempts: number;
  cronExpression: string;
  timezone: string;
  idempotencyKey: string;
  definitionText: string;
  validationIssues: string[];
  modelId: string;
  error: string | null;
};

export type DefinitionStatus = {
  valid: boolean;
  message: string;
};

export type DefinitionStats = {
  startAt: string;
  stateCount: number;
  terminalCount: number;
};

export type WorkflowPreview = DefinitionStats & {
  taskCount: number;
  choiceCount: number;
  waitCount: number;
  passCount: number;
  stateTypes: string[];
};

export type EndpointModelGroup = {
  endpoint: string;
  host: string;
  models: AiModel[];
  enabledCount: number;
};

export type ModelSettingsTab = 'add' | 'added' | 'defaults' | 'search';
