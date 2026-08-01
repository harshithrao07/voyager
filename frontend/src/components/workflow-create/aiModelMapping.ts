import type { AiModelRole, AiStructuredOutputMode } from '../../api';
import type { AiModel } from './types';

/**
 * Shape of an AI model config as returned by the API. Kept as one definition so the
 * DTO → {@link AiModel} mapping can't drift between the model picker and the settings modal
 * (a dropped field here previously leaked embedding models into the ranking/judge lists).
 */
export type ModelDto = {
  id: string;
  displayName: string;
  providerType: 'OPENAI_COMPATIBLE_LOCAL' | 'OPENAI_COMPATIBLE_API';
  role?: AiModelRole;
  baseUrl: string;
  modelName: string;
  enabled?: boolean;
  defaultModel?: boolean;
  hasCredential?: boolean;
  structuredOutputMode?: AiStructuredOutputMode;
};

/** The single DTO → AiModel mapper shared by every model-management surface. */
export function aiModelFromDto(model: ModelDto): AiModel {
  return {
    id: model.id,
    label: model.displayName || model.modelName,
    endpoint: model.baseUrl,
    modelName: model.modelName,
    provider: model.providerType === 'OPENAI_COMPATIBLE_API' ? 'api' : 'local',
    role: model.role ?? 'CHAT',
    enabled: model.enabled,
    defaultModel: model.defaultModel,
    hasCredential: model.hasCredential,
    structuredOutputMode: model.structuredOutputMode,
  };
}

/** Human-readable host for grouping models by endpoint. */
export function endpointHost(endpoint: string): string {
  try {
    return new URL(endpoint).host;
  } catch {
    return endpoint.replace(/^https?:\/\//, '').replace(/\/v1\/?$/, '');
  }
}
