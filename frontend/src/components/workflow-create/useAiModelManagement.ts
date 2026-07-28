import { useCallback, useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';
import {
  createAiModel,
  deleteAiModel,
  listAiModels,
  listAllAiModels,
  setAiModelEnabled,
  type AiStructuredOutputMode,
} from '../../api';
import { cloudProviderPreset } from './modelProviders';
import type { AiModel, EndpointModelGroup, ModelSettingsTab } from './types';

type ModelDto = {
  id: string;
  displayName: string;
  providerType: 'OPENAI_COMPATIBLE_LOCAL' | 'OPENAI_COMPATIBLE_API';
  baseUrl: string;
  modelName: string;
  enabled?: boolean;
  defaultModel?: boolean;
  hasCredential?: boolean;
  structuredOutputMode?: AiStructuredOutputMode;
};

function aiModelFromDto(model: ModelDto): AiModel {
  return {
    id: model.id,
    label: model.displayName || model.modelName,
    endpoint: model.baseUrl,
    modelName: model.modelName,
    provider: model.providerType === 'OPENAI_COMPATIBLE_API' ? 'api' : 'local',
    enabled: model.enabled,
    defaultModel: model.defaultModel,
    hasCredential: model.hasCredential,
    structuredOutputMode: model.structuredOutputMode,
  };
}

function endpointHost(endpoint: string) {
  try {
    return new URL(endpoint).host;
  } catch {
    return endpoint.replace(/^https?:\/\//, '').replace(/\/v1\/?$/, '');
  }
}

/**
 * Self-contained AI model management: the list of configured models, the add-model forms, and the
 * enable/disable/delete actions. This is the same surface the workflow-create model picker manages,
 * factored out so the standalone AI Settings page can drive {@link ModelSettingsModal} on its own
 * (it deliberately omits the chat-only "selected model" concern).
 */
export function useAiModelManagement() {
  const [allModels, setAllModels] = useState<AiModel[]>([]);
  const [settingsTab, setSettingsTab] = useState<ModelSettingsTab>('add');
  const [expandedEndpoint, setExpandedEndpoint] = useState<string | null>(null);
  const [managingModels, setManagingModels] = useState(false);
  const [addingModel, setAddingModel] = useState<'local' | 'api' | null>(null);

  const [discoverEndpoint, setDiscoverEndpoint] = useState('');
  const [localModelName, setLocalModelName] = useState('');
  const [localCredentialRef, setLocalCredentialRef] = useState('');
  const [modelActionMessage, setModelActionMessage] = useState<string | null>(null);
  const [modelActionSuccess, setModelActionSuccess] = useState<boolean | null>(null);

  const [apiProvider, setApiProvider] = useState('DeepSeek');
  const [apiModelName, setApiModelName] = useState('');
  const [apiEndpoint, setApiEndpoint] = useState('https://api.deepseek.com');
  const [apiCredentialRef, setApiCredentialRef] = useState('');
  const [apiActionMessage, setApiActionMessage] = useState<string | null>(null);
  const [apiActionSuccess, setApiActionSuccess] = useState<boolean | null>(null);

  const refreshModelLists = useCallback(async () => {
    const enabledDtos = await listAiModels();
    const allDtos = await listAllAiModels().catch(() => enabledDtos);
    setAllModels((allDtos as ModelDto[]).map(aiModelFromDto));
  }, []);

  useEffect(() => {
    let cancelled = false;
    listAllAiModels()
      .catch(() => listAiModels())
      .then((dtos) => {
        if (!cancelled) setAllModels((dtos as ModelDto[]).map(aiModelFromDto));
      })
      .catch((error: Error) => {
        if (!cancelled) toast.error(`Could not load configured AI models: ${error.message}`);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (allModels.length > 0) {
      setExpandedEndpoint((current) => current || allModels[0].endpoint);
    }
  }, [allModels]);

  const addLocalModel = async () => {
    const endpoint = discoverEndpoint.trim();
    const modelName = localModelName.trim();
    if (!endpoint || !modelName) {
      setModelActionMessage('Enter both an endpoint and exact model name.');
      setModelActionSuccess(false);
      return;
    }
    setAddingModel('local');
    setModelActionMessage(null);
    setModelActionSuccess(null);
    try {
      const created = await createAiModel({
        displayName: modelName,
        providerType: 'OPENAI_COMPATIBLE_LOCAL',
        baseUrl: endpoint,
        modelName,
        credential: localCredentialRef.trim() || null,
        defaultModel: allModels.length === 0,
      });
      await refreshModelLists();
      setModelActionMessage(`Connected ${created.displayName || created.modelName}.`);
      setModelActionSuccess(true);
      setLocalModelName('');
      setLocalCredentialRef('');
      setSettingsTab('added');
    } catch (error: any) {
      setModelActionMessage(error.message || 'Failed to add local model.');
      setModelActionSuccess(false);
    } finally {
      setAddingModel(null);
    }
  };

  const changeApiProvider = (provider: string) => {
    const preset = cloudProviderPreset(provider);
    setApiProvider(preset.name);
    setApiEndpoint(preset.endpoint);
    setApiModelName('');
    setApiActionMessage(null);
    setApiActionSuccess(null);
  };

  const addApiModel = async () => {
    const endpoint = apiEndpoint.trim();
    const modelName = apiModelName.trim();
    if (!endpoint || !modelName) {
      setApiActionMessage('Enter both an endpoint and exact model name.');
      setApiActionSuccess(false);
      return;
    }
    setAddingModel('api');
    setApiActionMessage(null);
    setApiActionSuccess(null);
    try {
      const created = await createAiModel({
        displayName: modelName,
        providerType: 'OPENAI_COMPATIBLE_API',
        baseUrl: endpoint,
        modelName,
        credential: apiCredentialRef.trim() || null,
        defaultModel: allModels.length === 0,
      });
      await refreshModelLists();
      setApiActionMessage(`Connected ${created.displayName || created.modelName}.`);
      setApiActionSuccess(true);
      setApiModelName('');
      setApiCredentialRef('');
      setSettingsTab('added');
    } catch (error: any) {
      setApiActionMessage(error.message || 'Failed to add cloud model.');
      setApiActionSuccess(false);
    } finally {
      setAddingModel(null);
    }
  };

  const updateEndpointEnabled = async (endpoint: string, enabled: boolean) => {
    const endpointModels = allModels.filter((model) => model.endpoint === endpoint);
    if (endpointModels.length === 0) return;
    setManagingModels(true);
    try {
      await Promise.all(endpointModels.map((model) => setAiModelEnabled(model.id, { enabled })));
      await refreshModelLists();
      toast.success('Endpoint updated');
    } catch (error: any) {
      toast.error(error.message || 'Failed to update endpoint models.');
    } finally {
      setManagingModels(false);
    }
  };

  const updateSingleModelEnabled = async (model: AiModel, enabled: boolean) => {
    setManagingModels(true);
    try {
      await setAiModelEnabled(model.id, { enabled });
      await refreshModelLists();
      toast.success('Model updated');
    } catch (error: any) {
      toast.error(error.message || 'Failed to update model.');
    } finally {
      setManagingModels(false);
    }
  };

  const deleteEndpointModels = async (endpoint: string) => {
    const endpointModels = allModels.filter((model) => model.endpoint === endpoint);
    if (endpointModels.length === 0) return;
    setManagingModels(true);
    try {
      await Promise.all(endpointModels.map((model) => deleteAiModel(model.id)));
      await refreshModelLists();
      setExpandedEndpoint((current) => (current === endpoint ? null : current));
      toast.success('Endpoint removed');
    } catch (error: any) {
      toast.error(error.message || 'Failed to delete endpoint models.');
    } finally {
      setManagingModels(false);
    }
  };

  const copyEndpoint = async (endpoint: string) => {
    try {
      await navigator.clipboard.writeText(endpoint);
      toast.success('Copied');
    } catch {
      toast.error('Could not copy endpoint.');
    }
  };

  const endpointGroups = useMemo<EndpointModelGroup[]>(() => {
    const grouped = new Map<string, AiModel[]>();
    for (const model of allModels) {
      const existing = grouped.get(model.endpoint) || [];
      existing.push(model);
      grouped.set(model.endpoint, existing);
    }
    return [...grouped.entries()].map(([endpoint, endpointModels]) => ({
      endpoint,
      host: endpointHost(endpoint),
      models: endpointModels,
      enabledCount: endpointModels.filter((model) => model.enabled !== false).length,
      provider: endpointModels.some((model) => model.provider === 'api') ? 'api' as const : 'local' as const,
      hasCredential: endpointModels.some((model) => model.hasCredential === true),
    }));
  }, [allModels]);

  const localEndpointNeedsDockerHint =
    /\/\/(localhost|127\.0\.0\.1)(:|\/|$)/i.test(discoverEndpoint.trim());

  return {
    settingsTab,
    setSettingsTab,
    discoverEndpoint,
    setDiscoverEndpoint,
    localModelName,
    setLocalModelName,
    addLocalModel,
    addingModel,
    localCredentialRef,
    setLocalCredentialRef,
    localEndpointNeedsDockerHint,
    modelActionMessage,
    modelActionSuccess,
    apiProvider,
    changeApiProvider,
    apiEndpoint,
    setApiEndpoint,
    apiModelName,
    setApiModelName,
    apiCredentialRef,
    setApiCredentialRef,
    addApiModel,
    apiActionMessage,
    apiActionSuccess,
    endpointGroups,
    expandedEndpoint,
    setExpandedEndpoint,
    managingModels,
    copyEndpoint,
    updateEndpointEnabled,
    deleteEndpointModels,
    updateSingleModelEnabled,
  };
}
