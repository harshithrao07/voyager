import type { WorkflowAiResourcePlan } from '../../api';

export type DefinitionMode = 'manual' | 'ai';

export type AiModel = {
  id: string;
  label: string;
  endpoint: string;
  modelName?: string;
  provider: 'local' | 'api';
  enabled?: boolean;
  defaultModel?: boolean;
  hasCredential?: boolean;
};

export type ChatMessage = {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  createdAt: number;
  persisted?: boolean;
  modelConfigId?: string | null;
  modelDisplayName?: string | null;
  durationMs?: number | null;
  inputTokens?: number | null;
  outputTokens?: number | null;
  totalTokens?: number | null;
  thinkingContent?: string | null;
  finishReason?: string | null;
  regeneratedFromMessageId?: string | null;
  resourcePlan?: WorkflowAiResourcePlan | null;
  streamingStatus?: 'processing' | 'streaming';
  streamingPhase?: 'thinking' | 'answer';
  /** Live label for the current model call of a turn, e.g. "Repairing the response (2 of 3)". */
  streamingStage?: string | null;
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
  provider: 'local' | 'api';
  hasCredential: boolean;
};

export type ModelSettingsTab = 'add' | 'added';
