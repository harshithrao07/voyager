import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Braces, Sparkles } from 'lucide-react';
import { toast } from 'sonner';
import {
  continueWorkflowAiConversation,
  createWorkflowAiDraft,
  createAiModel,
  createWorkflow,
  createWorkflowRevision,
  deleteAiModel,
  getWorkflowAiConversation,
  getWorkflowAiDraft,
  listFunctions,
  listAllAiModels,
  listAiModels,
  listMcpServers,
  listMcpKnownTools,
  provisionWorkflowAiResources,
  regenerateWorkflowAiMessage,
  saveWorkflowAiConversation,
  saveWorkflowAiDraft,
  saveWorkflowAiWorkspace,
  saveWorkflowAiDraftWorkspace,
  setAiModelEnabled,
  startWorkflowAiConversation,
  updateWorkflowCanvasLayout,
  type FunctionDefinitionDTO,
  type McpToolDTO,
  type WorkflowAiMessageDTO,
  type WorkflowAiProposedFunction,
  type WorkflowAiResourcePlan,
  type WorkflowAiResponse,
  type WorkflowAiWorkspaceRequest,
  type WorkflowDefinitionResponseDTO,
  type WorkflowResponseDTO,
} from '../api';
import { ChatComposer } from './workflow-create/ChatComposer';
import { ManualWorkflowEditor } from './workflow-create/ManualWorkflowEditor';
import { ModelSettingsModal } from './workflow-create/ModelSettingsModal';
import { cloudProviderPreset } from './workflow-create/modelProviders';
import { SidebarAiChat } from './workflow-create/SidebarAiChat';
import { ResourcePlanCard } from './workflow-create/ResourcePlanCard';
import { WorkflowAiEmptyState } from './workflow-create/WorkflowAiEmptyState';
import type { TaskResourceOption } from './workflow-create/StateEditorForm';
import { filterCanvasPositionsForDefinition } from './workflow-create/nestedMachine';
import { collectAslIssues } from '../utils/aslValidation';
import { validateCron } from '../utils/cronValidation';
import type {
  AiModel,
  ChatMessage,
  DefinitionMode,
  ModelSettingsTab,
  WorkflowPreview,
} from './workflow-create/types';
import type { CanvasNodePositions } from '../types/workflowCanvas';

const starterDefinition = {
  StartAt: '',
  States: {},
};

export type WorkflowRevisionEditContext = {
  workflow: WorkflowResponseDTO;
  baseRevision: {
    revision: number;
    definition: unknown;
    canvasLayout: CanvasNodePositions;
    active: boolean;
  };
};

type Props = {
  onWorkflowCreated: (workflow: WorkflowResponseDTO) => void;
  onWorkflowRevisionSaved?: (
    workflowId: string,
    revision: WorkflowDefinitionResponseDTO,
  ) => void | Promise<void>;
  onUnsavedChangesChange?: (dirty: boolean) => void;
  onNavigate?: (path: string, options?: { replace?: boolean }) => void;
  onConversationUpdated?: () => void;
  routeChatId?: string;
  routeDraftId?: string;
  revisionEdit?: WorkflowRevisionEditContext;
};

function newIdempotencyKey() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return `workflow-${crypto.randomUUID()}`;
  }

  return `workflow-${Date.now()}`;
}

function formatJson(value: unknown) {
  return JSON.stringify(value, null, 2);
}

function parseDefinition(value: string) {
  const parsed = JSON.parse(value);

  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('Definition must be a JSON object.');
  }

  if (!('StartAt' in parsed) || !('States' in parsed)) {
    throw new Error('Definition must include StartAt and States.');
  }

  return parsed;
}

function stableJsonValue(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(stableJsonValue);
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>)
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([key, entry]) => [key, stableJsonValue(entry)]),
    );
  }
  return value;
}

function stableJson(value: unknown) {
  return JSON.stringify(stableJsonValue(value));
}

function definitionFingerprint(value: string) {
  try {
    return stableJson(JSON.parse(value));
  } catch {
    return value;
  }
}

function newChatMessageId() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }

  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

const lastAiModelStorageKey = 'voyager:last-ai-model-id';

function readLastSelectedModelId() {
  try {
    return window.localStorage.getItem(lastAiModelStorageKey) || '';
  } catch {
    return '';
  }
}

function writeLastSelectedModelId(value: string) {
  try {
    if (value) {
      window.localStorage.setItem(lastAiModelStorageKey, value);
    }
  } catch {
    // Ignore storage restrictions.
  }
}

function chatMessageFromDto(message: WorkflowAiMessageDTO): ChatMessage {
  return {
    id: message.id,
    role: message.role === 'USER' ? 'user' : 'assistant',
    content: message.content,
    createdAt: new Date(message.createdAt).getTime(),
    persisted: true,
    modelConfigId: message.modelConfigId,
    modelDisplayName: message.modelDisplayName,
    durationMs: message.durationMs,
    inputTokens: message.inputTokens,
    outputTokens: message.outputTokens,
    totalTokens: message.totalTokens,
    thinkingContent: message.thinkingContent,
    finishReason: message.finishReason,
    regeneratedFromMessageId: message.regeneratedFromMessageId,
    resourcePlan: message.resourcePlan,
  };
}

function visibleChatMessages(messages: WorkflowAiMessageDTO[]) {
  const supersededMessageIds = new Set(
    messages
      .map((message) => message.regeneratedFromMessageId)
      .filter((messageId): messageId is string => Boolean(messageId)),
  );
  return messages
    .filter((message) => message.role !== 'SYSTEM')
    .filter((message) => !supersededMessageIds.has(message.id))
    .map(chatMessageFromDto);
}

function aiModelFromDto(model: {
  id: string;
  displayName: string;
  providerType: 'OPENAI_COMPATIBLE_LOCAL' | 'OPENAI_COMPATIBLE_API';
  baseUrl: string;
  modelName: string;
  enabled?: boolean;
  defaultModel?: boolean;
  hasCredential?: boolean;
}): AiModel {
  return {
    id: model.id,
    label: model.displayName || model.modelName,
    endpoint: model.baseUrl,
    modelName: model.modelName,
    provider: model.providerType === 'OPENAI_COMPATIBLE_API' ? 'api' : 'local',
    enabled: model.enabled,
    defaultModel: model.defaultModel,
    hasCredential: model.hasCredential,
  };
}

function endpointHost(endpoint: string) {
  try {
    return new URL(endpoint).host;
  } catch {
    return endpoint.replace(/^https?:\/\//, '').replace(/\/v1\/?$/, '');
  }
}

function functionTaskResource(fn: FunctionDefinitionDTO) {
  return `voyager://function/${fn.name}@v${fn.activeVersion}`;
}

export function CreateWorkflowView({
  onWorkflowCreated,
  onWorkflowRevisionSaved,
  onUnsavedChangesChange,
  onNavigate,
  onConversationUpdated,
  routeChatId,
  routeDraftId,
  revisionEdit,
}: Props) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [linkedWorkflowId, setLinkedWorkflowId] = useState<string | null>(null);
  const [mode, setMode] = useState<DefinitionMode>(revisionEdit ? 'manual' : 'ai');
  const [name, setName] = useState(revisionEdit?.workflow.name || '');
  const [maxAttempts, setMaxAttempts] = useState(revisionEdit?.workflow.maxAttempts ?? 3);
  const [cronExpression, setCronExpression] = useState(revisionEdit?.workflow.cronExpression || '');
  const [timezone, setTimezone] = useState(revisionEdit?.workflow.timezone || 'UTC');
  const [idempotencyKey, setIdempotencyKey] = useState(
    revisionEdit?.workflow.idempotencyKey || newIdempotencyKey(),
  );
  const [instruction, setInstruction] = useState('');
  const [previewPrompt, setPreviewPrompt] = useState<string | null>(null);
  const [models, setModels] = useState<AiModel[]>([]);
  const [allModels, setAllModels] = useState<AiModel[]>([]);
  const [customFunctions, setCustomFunctions] = useState<FunctionDefinitionDTO[]>([]);
  const [mcpTools, setMcpTools] = useState<McpToolDTO[]>([]);
  const [modelId, setModelId] = useState('');
  const [modelSearch, setModelSearch] = useState('');
  const [modelPickerOpen, setModelPickerOpen] = useState(false);
  const [modelPickerPlacement, setModelPickerPlacement] = useState<'down' | 'up'>('down');
  const [addModelOpen, setAddModelOpen] = useState(false);
  const [settingsTab, setSettingsTab] = useState<ModelSettingsTab>('add');
  const [localCredentialRef, setLocalCredentialRef] = useState('');
  const [localModelName, setLocalModelName] = useState('');
  const [apiProvider, setApiProvider] = useState('DeepSeek');
  const [apiModelName, setApiModelName] = useState('');
  const [apiEndpoint, setApiEndpoint] = useState('https://api.deepseek.com');
  const [apiCredentialRef, setApiCredentialRef] = useState('');
  const [apiActionMessage, setApiActionMessage] = useState<string | null>(null);
  const [apiActionSuccess, setApiActionSuccess] = useState<boolean | null>(null);
  const [modelActionMessage, setModelActionMessage] = useState<string | null>(null);
  const [modelActionSuccess, setModelActionSuccess] = useState<boolean | null>(null);
  const [addingModel, setAddingModel] = useState<'local' | 'api' | null>(null);
  const [discoverEndpoint, setDiscoverEndpoint] = useState('');
  const [expandedEndpoint, setExpandedEndpoint] = useState<string | null>(null);
  const [managingModels, setManagingModels] = useState(false);
  const [definitionText, setDefinitionText] = useState(() => formatJson(
    revisionEdit?.baseRevision.definition || starterDefinition,
  ));
  const [canvasNodePositions, setCanvasNodePositions] = useState<CanvasNodePositions>(
    revisionEdit?.baseRevision.canvasLayout || {},
  );
  const [validationIssues, setValidationIssues] = useState<string[]>([]);
  const [conversationLoading, setConversationLoading] = useState(false);
  const [draftCreating, setDraftCreating] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [activeResourcePlanMessageId, setActiveResourcePlanMessageId] = useState<string | null>(null);
  const [activeResourcePlan, setActiveResourcePlan] = useState<WorkflowAiResourcePlan | null>(null);
  const [regeneratingMessageId, setRegeneratingMessageId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [revisionSaveCompleted, setRevisionSaveCompleted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expandedThinkingMessageIds, setExpandedThinkingMessageIds] = useState<Set<string>>(() => new Set());
  const modelPickerRef = useRef<HTMLDivElement | null>(null);
  const instructionTextareaRef = useRef<HTMLTextAreaElement | null>(null);
  const regeneratingRef = useRef(false);
  const streamingTimerRefs = useRef<number[]>([]);
  const lastLoadedRouteChatIdRef = useRef<string | null>(null);
  const lastSavedWorkspaceRef = useRef<string | null>(null);
  const workspaceSaveTimerRef = useRef<number | null>(null);
  const pendingWorkspaceSaveRef = useRef<(() => void) | null>(null);
  const workspaceSaveQueueRef = useRef<Promise<void>>(Promise.resolve());
  const workspaceSaveErrorShownRef = useRef(false);
  const draftCreationRef = useRef(false);

  const hasSelectedModel = models.some((model) => model.id === modelId);
  const canGenerate = instruction.trim().length > 0
    && !conversationLoading
    && !draftCreating
    && !generating
    && !saving
    && hasSelectedModel;
  const definitionJsonStatus = useMemo(() => {
    try {
      parseDefinition(definitionText);
      return { valid: true, message: 'Definition JSON is well-formed' };
    } catch (err: any) {
      return { valid: false, message: err.message || 'Definition JSON is invalid' };
    }
  }, [definitionText]);
  const taskResourceOptions = useMemo<TaskResourceOption[]>(() => ([
    ...customFunctions
      .filter((fn) => fn.status === 'ENABLED' && fn.activeVersion !== null)
      .map((fn) => ({
        value: functionTaskResource(fn),
        label: fn.name,
        description: `Function v${fn.activeVersion}`,
      })),
    ...mcpTools.map((tool) => ({
      value: `voyager://mcp/${tool.serverId}/${tool.toolName}`,
      label: tool.toolName,
      description: `MCP · ${tool.serverId}`,
    })),
  ]), [customFunctions, mcpTools]);

  const enqueueWorkspaceSave = useCallback((
    targetConversationId: string,
    request: WorkflowAiWorkspaceRequest,
    signature: string,
    manualDraft: boolean,
  ) => {
    workspaceSaveQueueRef.current = workspaceSaveQueueRef.current
      .catch(() => undefined)
      .then(() => manualDraft
        ? saveWorkflowAiDraftWorkspace(targetConversationId, request)
        : saveWorkflowAiWorkspace(targetConversationId, request))
      .then(() => {
        if (lastLoadedRouteChatIdRef.current === targetConversationId) {
          lastSavedWorkspaceRef.current = signature;
        }
        workspaceSaveErrorShownRef.current = false;
        onConversationUpdated?.();
      })
      .catch((err: Error) => {
        console.warn('Could not autosave conversation workspace.', err);
        if (!workspaceSaveErrorShownRef.current) {
          workspaceSaveErrorShownRef.current = true;
          toast.error('Could not save this conversation workspace.');
        }
      });
  }, [onConversationUpdated]);

  const currentWorkspaceRequest = useCallback((
    nextDefinitionText = definitionText,
  ): WorkflowAiWorkspaceRequest => ({
    definitionText: nextDefinitionText,
    canvasLayout: canvasNodePositions,
    settings: {
      name,
      maxAttempts,
      cronExpression,
      timezone,
      idempotencyKey,
    },
  }), [canvasNodePositions, cronExpression, definitionText, idempotencyKey, maxAttempts, name, timezone]);

  const ensureManualDraft = async (nextDefinitionText = definitionText) => {
    if (revisionEdit || conversationId || routeChatId || routeDraftId || draftCreationRef.current) {
      return;
    }
    draftCreationRef.current = true;
    setDraftCreating(true);
    const request = currentWorkspaceRequest(nextDefinitionText);
    try {
      const draft = await createWorkflowAiDraft(request);
      lastLoadedRouteChatIdRef.current = draft.id;
      lastSavedWorkspaceRef.current = JSON.stringify(request);
      setConversationId(draft.id);
      onConversationUpdated?.();
      onNavigate?.(`/draft/${encodeURIComponent(draft.id)}`, { replace: true });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Could not create a persistent draft.';
      setError(message);
      toast.error(message);
    } finally {
      draftCreationRef.current = false;
      setDraftCreating(false);
    }
  };

  const flushPendingWorkspaceSave = () => {
    if (workspaceSaveTimerRef.current !== null) {
      window.clearTimeout(workspaceSaveTimerRef.current);
      workspaceSaveTimerRef.current = null;
    }
    const pendingSave = pendingWorkspaceSaveRef.current;
    pendingWorkspaceSaveRef.current = null;
    pendingSave?.();
  };

  useEffect(() => {
    let cancelled = false;

    listFunctions()
      .then((functions) => {
        if (!cancelled) {
          setCustomFunctions(functions);
        }
      })
      .catch((err) => {
        console.warn('Function registry unavailable for workflow resource picker.', err);
      });

    listMcpServers('ENABLED')
      .then((servers) => Promise.all(
        servers.map((server) => listMcpKnownTools(server.serverId, true).catch(() => [])),
      ))
      .then((toolLists) => {
        if (!cancelled) {
          setMcpTools(toolLists.flat());
        }
      })
      .catch((err) => {
        console.warn('MCP registry unavailable for workflow resource picker.', err);
      });

    return () => {
      cancelled = true;
    };
  }, []);
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setModelPickerOpen(false);
        setAddModelOpen(false);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  const definitionStats = useMemo<WorkflowPreview>(() => {
    try {
      const definition = parseDefinition(definitionText);
      const statesObject = definition.States && typeof definition.States === 'object'
        ? definition.States as Record<string, Record<string, unknown>>
        : {};
      const states = Object.entries(statesObject);
      const stateTypes = [...new Set(states.map(([, state]) => (
        typeof state.Type === 'string' ? state.Type : 'Unknown'
      )))].sort();
      return {
        startAt: typeof definition.StartAt === 'string' && definition.StartAt ? definition.StartAt : '-',
        stateCount: states.length,
        terminalCount: states.filter(([, state]) => (
          state?.End || state?.Type === 'Succeed' || state?.Type === 'Fail'
        )).length,
        taskCount: states.filter(([, state]) => state?.Type === 'Task').length,
        choiceCount: states.filter(([, state]) => state?.Type === 'Choice').length,
        waitCount: states.filter(([, state]) => state?.Type === 'Wait').length,
        passCount: states.filter(([, state]) => state?.Type === 'Pass').length,
        stateTypes,
      };
    } catch {
      return {
        startAt: '-',
        stateCount: 0,
        terminalCount: 0,
        taskCount: 0,
        choiceCount: 0,
        waitCount: 0,
        passCount: 0,
        stateTypes: [],
      };
    }
  }, [definitionText]);

  // Full client-side structural, graph, JSONata-dialect, and state-field checks.
  // The backend remains authoritative for parsing the JSONata expression body.
  const localValidationIssues = useMemo<string[]>(() => {
    try {
      return collectAslIssues(parseDefinition(definitionText));
    } catch {
      return [];
    }
  }, [definitionText]);

  const builderValidationIssues = useMemo<string[]>(() => (
    [...new Set([...validationIssues, ...localValidationIssues])]
  ), [validationIssues, localValidationIssues]);

  const definitionStatus = useMemo(() => {
    if (!definitionJsonStatus.valid) return definitionJsonStatus;
    if (localValidationIssues.length > 0) {
      const suffix = localValidationIssues.length === 1 ? 'issue' : 'issues';
      return {
        valid: false,
        message: `${localValidationIssues.length} ASL validation ${suffix}`,
      };
    }
    return { valid: true, message: 'Frontend ASL checks pass' };
  }, [definitionJsonStatus, localValidationIssues]);

  const revisionDefinitionDirty = useMemo(() => (
    revisionEdit
      ? definitionFingerprint(definitionText) !== stableJson(revisionEdit.baseRevision.definition)
      : false
  ), [definitionText, revisionEdit]);
  const revisionLayoutDirty = useMemo(() => (
    revisionEdit
      ? stableJson(canvasNodePositions) !== stableJson(revisionEdit.baseRevision.canvasLayout || {})
      : false
  ), [canvasNodePositions, revisionEdit]);
  const hasUnsavedRevisionChanges = Boolean(
    revisionEdit
    && !revisionSaveCompleted
    && (revisionDefinitionDirty || revisionLayoutDirty),
  );

  const canSave = name.trim().length > 0
    && idempotencyKey.trim().length > 0
    && definitionStatus.valid
    && builderValidationIssues.length === 0
    && validateCron(cronExpression) === null
    && (!(routeChatId || routeDraftId) || Boolean(conversationId))
    && (!revisionEdit || hasUnsavedRevisionChanges)
    && !saving
    && !generating
    && !draftCreating;

  useEffect(() => {
    onUnsavedChangesChange?.(hasUnsavedRevisionChanges);
    return () => onUnsavedChangesChange?.(false);
  }, [hasUnsavedRevisionChanges, onUnsavedChangesChange]);

  useEffect(() => {
    if (!hasUnsavedRevisionChanges) return undefined;
    const warnBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warnBeforeUnload);
    return () => window.removeEventListener('beforeunload', warnBeforeUnload);
  }, [hasUnsavedRevisionChanges]);

  const handleDefinitionTextChange = (value: string) => {
    setDefinitionText(value);
    // Backend/AI issues describe the previous definition. The local validator
    // immediately recomputes the current definition as the user edits it.
    setValidationIssues([]);

    try {
      const nextDefinition = JSON.parse(value) as { States?: unknown };
      const states = nextDefinition.States;
      if (states && typeof states === 'object' && !Array.isArray(states) && Object.keys(states).length > 0) {
        void ensureManualDraft(value);
      }
    } catch {
      // Incomplete code-editor input remains local until it contains its first parseable state.
    }
  };

  const applyModelLists = (enabledDtos: Parameters<typeof aiModelFromDto>[0][], allDtos: Parameters<typeof aiModelFromDto>[0][]) => {
    const nextModels = enabledDtos.map(aiModelFromDto);
    const nextAllModels = allDtos.map(aiModelFromDto);
    setModels(nextModels);
    setAllModels(nextAllModels);
    setModelId((current) => (
      current && nextModels.some((model) => model.id === current)
        ? current
        : nextModels.find((model) => model.id === readLastSelectedModelId())?.id || nextModels[0]?.id || ''
    ));
    setExpandedEndpoint((current) => current || nextAllModels[0]?.endpoint || null);
  };

  const selectModel = (nextModelId: string) => {
    setModelId(nextModelId);
    writeLastSelectedModelId(nextModelId);
  };

  const refreshModelLists = async () => {
    const enabledDtos = await listAiModels();
    const allDtos = await listAllAiModels().catch(() => enabledDtos);
    applyModelLists(enabledDtos, allDtos);
  };

  useEffect(() => {
    let cancelled = false;

    Promise.all([
      listAiModels(),
      listAllAiModels().catch(() => null),
    ])
      .then(([enabledDtos, allDtos]) => {
        if (cancelled) return;
        applyModelLists(enabledDtos, allDtos || enabledDtos);
      })
      .catch((err: Error) => {
        if (cancelled) return;
        setError(`Could not load configured AI models: ${err.message}`);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (modelId) {
      writeLastSelectedModelId(modelId);
    }
  }, [modelId]);

  useEffect(() => {
    const routeWorkspaceId = routeDraftId || routeChatId;
    if (!routeWorkspaceId || revisionEdit
        || lastLoadedRouteChatIdRef.current === routeWorkspaceId) {
      return undefined;
    }

    let cancelled = false;
    flushPendingWorkspaceSave();
    lastLoadedRouteChatIdRef.current = routeWorkspaceId;
    lastSavedWorkspaceRef.current = null;
    setConversationLoading(true);
    setMessages([]);
    setConversationId(null);
    setLinkedWorkflowId(null);
    setValidationIssues([]);
    setActiveResourcePlanMessageId(null);
    setActiveResourcePlan(null);
    setExpandedThinkingMessageIds(new Set());
    setCanvasNodePositions({});
    setError(null);

    const loadWorkspace = routeDraftId
      ? getWorkflowAiDraft(routeWorkspaceId)
      : getWorkflowAiConversation(routeWorkspaceId);
    loadWorkspace
      .then((conversation) => {
        if (cancelled) return;
        const visibleMessages = visibleChatMessages(conversation.messages);
        const fallbackPlanOwner = visibleMessages.find((message) => Boolean(message.resourcePlan));
        const planOwnerMessageId = conversation.resourcePlanMessageId
          ?? fallbackPlanOwner?.id
          ?? null;
        // Keep the owner's original attachment immutable. The conversation-level resourcePlan is
        // the unresolved subset and is passed separately to the card as progress state. Hide only
        // legacy duplicate attachments while a proposal is active.
        const restoredMessages = visibleMessages.map((message) => {
          if (conversation.stage !== 'RESOURCES_PROPOSED' || !conversation.resourcePlan) {
            return message;
          }
          if (message.id === planOwnerMessageId) {
            return message;
          }
          return message.resourcePlan ? { ...message, resourcePlan: null } : message;
        });
        const restoredDefinition = conversation.aslDefinition
          || conversation.draftWorkflowPayload?.definition
          || starterDefinition;
        const restoredDefinitionText = conversation.workspaceDefinitionText
          ?? formatJson(restoredDefinition);
        const restoredCanvasLayout = conversation.canvasLayout || {};
        const restoredName = conversation.workspaceSettings?.name
          ?? conversation.draftWorkflowPayload?.name
          ?? conversation.name
          ?? '';
        const restoredMaxAttempts = conversation.workspaceSettings?.maxAttempts
          ?? conversation.draftWorkflowPayload?.maxAttempts
          ?? 3;
        const restoredCronExpression = conversation.workspaceSettings?.cronExpression
          ?? conversation.draftWorkflowPayload?.cronExpression
          ?? '';
        const restoredTimezone = conversation.workspaceSettings?.timezone
          ?? conversation.draftWorkflowPayload?.timezone
          ?? 'UTC';
        const restoredIdempotencyKey = conversation.workspaceSettings?.idempotencyKey
          ?? conversation.draftWorkflowPayload?.idempotencyKey
          ?? newIdempotencyKey();
        const restoredWorkspace: WorkflowAiWorkspaceRequest = {
          definitionText: restoredDefinitionText,
          canvasLayout: restoredCanvasLayout,
          settings: {
            name: restoredName,
            maxAttempts: restoredMaxAttempts,
            cronExpression: restoredCronExpression,
            timezone: restoredTimezone,
            idempotencyKey: restoredIdempotencyKey,
          },
        };
        setMessages(restoredMessages);
        setConversationId(conversation.id);
        setLinkedWorkflowId(conversation.workflowId || null);
        setMode('manual');
        if (conversation.modelConfigId) {
          setModelId(conversation.modelConfigId);
          writeLastSelectedModelId(conversation.modelConfigId);
        }
        setName(restoredName);
        setMaxAttempts(restoredMaxAttempts);
        setCronExpression(restoredCronExpression);
        setTimezone(restoredTimezone);
        setIdempotencyKey(restoredIdempotencyKey);
        setDefinitionText(restoredDefinitionText);
        setCanvasNodePositions(restoredCanvasLayout);
        // Restore the pending resource-approval card if the chat was left mid-review, so a refresh
        // does not silently drop the proposed functions/MCP requirements.
        setActiveResourcePlanMessageId(
          conversation.stage === 'RESOURCES_PROPOSED' && conversation.resourcePlan
            ? planOwnerMessageId
            : null,
        );
        setActiveResourcePlan(
          conversation.stage === 'RESOURCES_PROPOSED' && conversation.resourcePlan
            ? conversation.resourcePlan
            : null,
        );
        // Hydration is read-only. Treat the values returned (including legacy fallbacks) as the
        // saved baseline so merely opening an old chat does not autosave it and refresh updatedAt.
        lastSavedWorkspaceRef.current = JSON.stringify(restoredWorkspace);
        setExpandedThinkingMessageIds(new Set(
          restoredMessages
            .filter((message) => Boolean(message.thinkingContent))
            .map((message) => message.id),
        ));
      })
      .catch((err: Error) => {
        if (cancelled) return;
        lastLoadedRouteChatIdRef.current = null;
        const message = `Could not load conversation: ${err.message}`;
        setError(message);
        toast.error(message);
      })
      .finally(() => {
        if (!cancelled) {
          setConversationLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [routeChatId, routeDraftId, revisionEdit]);

  useEffect(() => {
    if (workspaceSaveTimerRef.current !== null) {
      window.clearTimeout(workspaceSaveTimerRef.current);
      workspaceSaveTimerRef.current = null;
    }
    pendingWorkspaceSaveRef.current = null;

    if (!conversationId || conversationLoading || revisionEdit) return;

    const request = currentWorkspaceRequest();
    const signature = JSON.stringify(request);
    if (signature === lastSavedWorkspaceRef.current) return;

    const executeSave = () => enqueueWorkspaceSave(
      conversationId,
      request,
      signature,
      Boolean(routeDraftId),
    );
    pendingWorkspaceSaveRef.current = executeSave;
    workspaceSaveTimerRef.current = window.setTimeout(() => {
      workspaceSaveTimerRef.current = null;
      pendingWorkspaceSaveRef.current = null;
      executeSave();
    }, 400);
  }, [
    canvasNodePositions,
    conversationId,
    conversationLoading,
    cronExpression,
    currentWorkspaceRequest,
    definitionText,
    enqueueWorkspaceSave,
    idempotencyKey,
    maxAttempts,
    name,
    revisionEdit,
    routeDraftId,
    timezone,
  ]);

  useEffect(() => () => {
    flushPendingWorkspaceSave();
  }, []);

  useEffect(() => () => {
    streamingTimerRefs.current.forEach((timerId) => window.clearTimeout(timerId));
    streamingTimerRefs.current = [];
  }, []);

  const scheduleStreamingTick = (callback: () => void, delayMs: number) => {
    const timerId = window.setTimeout(() => {
      streamingTimerRefs.current = streamingTimerRefs.current.filter((id) => id !== timerId);
      callback();
    }, delayMs);
    streamingTimerRefs.current.push(timerId);
  };

  const updateMessageById = (messageId: string, updater: (message: ChatMessage) => ChatMessage) => {
    setMessages((current) => current.map((message) => (
      message.id === messageId ? updater(message) : message
    )));
  };

  const streamAssistantMessage = (
    assistantMessage: ChatMessage,
    replaceMessageId?: string,
  ) => {
    // Stream under the server's message id, not the local placeholder it replaces: the id is what
    // regenerate posts back, and a placeholder id 404s.
    const targetId = assistantMessage.id;
    const fullThinking = assistantMessage.thinkingContent || '';
    const fullContent = assistantMessage.content || '';
    const streamingMessage: ChatMessage = {
      ...assistantMessage,
      content: '',
      thinkingContent: fullThinking ? '' : null,
      streamingStatus: 'streaming',
      streamingPhase: fullThinking ? 'thinking' : 'answer',
    };

    setMessages((current) => {
      if (replaceMessageId) {
        return current.map((message) => (
          message.id === replaceMessageId ? streamingMessage : message
        ));
      }
      return [...current, streamingMessage];
    });

    if (fullThinking) {
      setExpandedThinkingMessageIds((current) => {
        const next = new Set(current);
        next.add(targetId);
        return next;
      });
    }

    const reveal = (
      field: 'thinkingContent' | 'content',
      value: string,
      chunkSize: number,
      onDone: () => void,
    ) => {
      let index = 0;
      const tick = () => {
        index = Math.min(value.length, index + chunkSize);
        updateMessageById(targetId, (message) => ({
          ...message,
          [field]: value.slice(0, index),
        }));

        if (index < value.length) {
          scheduleStreamingTick(tick, 22);
        } else {
          onDone();
        }
      };

      scheduleStreamingTick(tick, 80);
    };

    const finish = () => {
      updateMessageById(targetId, () => ({
        ...assistantMessage,
        streamingStatus: undefined,
        streamingPhase: undefined,
      }));
    };

    const revealAnswer = () => {
      updateMessageById(targetId, (message) => ({
        ...message,
        streamingPhase: 'answer',
      }));

      if (!fullContent) {
        finish();
        return;
      }

      reveal('content', fullContent, 5, finish);
    };

    if (fullThinking) {
      reveal('thinkingContent', fullThinking, 8, () => {
        scheduleStreamingTick(revealAnswer, 220);
      });
    } else {
      revealAnswer();
    }
  };

  const applyWorkflowAiResponse = (
    response: WorkflowAiResponse,
    options: { replaceMessageId?: string; animate?: boolean } = {},
  ) => {
    lastLoadedRouteChatIdRef.current = response.conversationId;
    setConversationId(response.conversationId);
    if (response.workflowId) {
      setLinkedWorkflowId(response.workflowId);
    }
    setValidationIssues(response.validationIssues || []);
    onConversationUpdated?.();

    if (response.aslDefinition) {
      setDefinitionText(formatJson(response.aslDefinition));
    }

    if (response.draftWorkflowPayload) {
      setName(response.draftWorkflowPayload.name || response.conversationName || name);
      setCronExpression(response.draftWorkflowPayload.cronExpression || '');
      setTimezone(response.draftWorkflowPayload.timezone || 'UTC');
      setMaxAttempts(response.draftWorkflowPayload.maxAttempts ?? 3);
      setIdempotencyKey(response.draftWorkflowPayload.idempotencyKey || idempotencyKey);
      if (response.draftWorkflowPayload.definition) {
        setDefinitionText(formatJson(response.draftWorkflowPayload.definition));
      }
    } else if (response.stage === 'PLAN_READY' && !name.trim()) {
      setName(response.conversationName || 'AI generated workflow');
    }

    const responsePlanOwnerId = response.resourcePlanMessageId
      ?? (response.stage === 'RESOURCES_PROPOSED' ? response.assistantMessage?.id : null)
      ?? null;
    const assistantMessage: ChatMessage = response.assistantMessage
      ? {
          ...chatMessageFromDto(response.assistantMessage),
          resourcePlan: response.assistantMessage.resourcePlan ?? null,
        }
      : {
          id: newChatMessageId(),
          role: 'assistant' as const,
          content: response.message || 'Updated workflow conversation.',
          createdAt: Date.now(),
          modelConfigId: modelId || null,
          modelDisplayName: selectedModel?.label || null,
          resourcePlan: responsePlanOwnerId ? response.resourcePlan ?? null : null,
        };

    // The message attachment is immutable history. Active resourcePlan is only the unresolved
    // subset and drives per-item pending/completed state without replacing that attachment.
    const activePlanOwnerId = response.stage === 'RESOURCES_PROPOSED' && response.resourcePlan
      ? responsePlanOwnerId ?? assistantMessage.id
      : null;
    setActiveResourcePlanMessageId(activePlanOwnerId);
    setActiveResourcePlan(activePlanOwnerId ? response.resourcePlan ?? null : null);

    if (assistantMessage.thinkingContent) {
      setExpandedThinkingMessageIds((current) => {
        const next = new Set(current);
        next.add(assistantMessage.id);
        return next;
      });
    }

    if (options.animate) {
      streamAssistantMessage(assistantMessage, options.replaceMessageId);
      return;
    }

    setMessages((prev) => {
      if (options.replaceMessageId) {
        return prev.map((message) => (
          message.id === options.replaceMessageId ? assistantMessage : message
        ));
      }
      return [...prev, assistantMessage];
    });
  };

  // The assistant only benefits from the editor's ASL once it actually has states; an empty
  // starter definition would just be noise in the prompt.
  const currentEditorDefinition = () => {
    try {
      const parsed = parseDefinition(definitionText);
      return Object.keys(parsed.States || {}).length > 0 ? parsed : null;
    } catch {
      return null;
    }
  };

  // When the editor JSON can't be sent as a parsed definition (mid-typing, unparseable, or an empty
  // starter machine), forward the raw buffer so the assistant can still see the in-progress edit.
  const inProgressEditorText = (editorDefinition: unknown) => {
    if (editorDefinition) return null;
    const trimmed = definitionText.trim();
    if (!trimmed) return null;
    try {
      const parsed = JSON.parse(trimmed);
      if (parsed && typeof parsed === 'object' && Object.keys(parsed.States || {}).length === 0) {
        return null;
      }
    } catch {
      // Unparseable buffer is exactly the in-progress case we want the assistant to see.
    }
    return definitionText;
  };

  const handleGenerate = async (
    overrideInstruction?: string,
    options: { includeDefinition?: boolean } = {},
  ) => {
    const currentInstruction = (overrideInstruction ?? instruction).trim();
    if (!currentInstruction || conversationLoading || generating || saving) return;

    const selectedModelForSend = models.find((model) => model.id === modelId) || null;
    if (!selectedModelForSend) {
      if (overrideInstruction !== undefined) {
        setInstruction(currentInstruction);
        setPreviewPrompt(null);
        window.requestAnimationFrame(() => instructionTextareaRef.current?.focus());
      }
      setModelPickerOpen(true);
      toast.error('Select a model before sending.');
      return;
    }

    const sentAt = Date.now();
    const startingNewConversation = !conversationId;
    const processingMessageId = newChatMessageId();
    
    setMessages((prev) => [
      ...prev,
      {
        id: newChatMessageId(),
        role: 'user',
        content: currentInstruction,
        createdAt: sentAt,
        modelConfigId: modelId || null,
        modelDisplayName: selectedModelForSend?.label || null,
      },
      {
        id: processingMessageId,
        role: 'assistant',
        content: '',
        createdAt: Date.now(),
        modelConfigId: modelId || null,
        modelDisplayName: selectedModelForSend?.label || null,
        streamingStatus: 'processing',
      },
    ]);
    setInstruction('');
    // The empty state is only an entry point: once a conversation exists the workflow lives in the
    // manual editor, with this conversation continuing in its sidebar.
    setMode('manual');
    setGenerating(true);
    setError(null);
    setValidationIssues([]);

    const editorDefinition = options.includeDefinition ? currentEditorDefinition() : null;
    const editorDefinitionText = options.includeDefinition
      ? inProgressEditorText(editorDefinition)
      : null;

    try {
      const response = conversationId
        ? await continueWorkflowAiConversation({
            conversationId,
            message: currentInstruction,
            modelConfigId: modelId || null,
            definition: editorDefinition,
            definitionText: editorDefinitionText,
          })
        : await startWorkflowAiConversation({
            instruction: currentInstruction,
            modelConfigId: modelId || null,
            userDateTime: new Date().toISOString(),
            definition: editorDefinition,
            definitionText: editorDefinitionText,
          });
      applyWorkflowAiResponse(response, {
        replaceMessageId: processingMessageId,
        animate: true,
      });
      if (startingNewConversation) {
        onNavigate?.(`/c/${encodeURIComponent(response.conversationId)}`, { replace: true });
      }
    } catch (err: any) {
      setError(err.message || 'Failed to generate workflow definition.');
      setMessages((prev) => prev.map((message) => (
        message.id === processingMessageId
          ? {
              ...message,
              content: `**Error**: ${err.message || 'Failed to generate workflow.'}`,
              streamingStatus: undefined,
              streamingPhase: undefined,
            }
          : message
      )));
    } finally {
      setGenerating(false);
    }
  };


  const toggleThinking = (messageId: string) => {
    setExpandedThinkingMessageIds((current) => {
      const next = new Set(current);
      if (next.has(messageId)) {
        next.delete(messageId);
      } else {
        next.add(messageId);
      }
      return next;
    });
  };

  const handleRegenerateMessage = async (message: ChatMessage) => {
    // The guard is a ref, not state: repeated clicks land in the same tick, where a state update
    // has not been applied yet and every call would still see an idle guard.
    if (!conversationId || message.role !== 'assistant' || regeneratingRef.current) return;

    regeneratingRef.current = true;
    setRegeneratingMessageId(message.id);
    setError(null);
    try {
      const response = await regenerateWorkflowAiMessage(message.id, {
        modelConfigId: modelId || null,
      });
      // The server appends a fresh message, so swap it in place of the one being retried.
      applyWorkflowAiResponse(response, {
        replaceMessageId: message.id,
        animate: true,
      });
    } catch (err: any) {
      setError(err.message || 'Failed to regenerate message.');
    } finally {
      regeneratingRef.current = false;
      setRegeneratingMessageId(null);
    }
  };

  const handleProvisionResources = async (functions: WorkflowAiProposedFunction[]) => {
    if (!conversationId || generating || saving) return;

    const selectedModelForSend = models.find((model) => model.id === modelId) || null;
    const processingMessageId = newChatMessageId();
    setMessages((prev) => [
      ...prev,
      {
        id: processingMessageId,
        role: 'assistant',
        content: '',
        createdAt: Date.now(),
        modelConfigId: modelId || null,
        modelDisplayName: selectedModelForSend?.label || null,
        streamingStatus: 'processing',
      },
    ]);
    setGenerating(true);
    setError(null);
    try {
      const response = await provisionWorkflowAiResources({
        conversationId,
        functions,
        modelConfigId: modelId || null,
      });
      applyWorkflowAiResponse(response, { replaceMessageId: processingMessageId, animate: true });
    } catch (err: any) {
      setError(err.message || 'Failed to create resources.');
      setMessages((prev) => prev.map((message) => (
        message.id === processingMessageId
          ? {
              ...message,
              content: `**Error**: ${err.message || 'Failed to create resources.'}`,
              streamingStatus: undefined,
              streamingPhase: undefined,
            }
          : message
      )));
    } finally {
      setGenerating(false);
    }
  };

  const handleContinueAfterMcp = () => {
    // Resume the existing resource proposal without manufacturing a new user chat turn. The
    // backend re-reads the live catalog and tells the model exactly which requirements now match.
    void handleProvisionResources([]);
  };

  const handleSave = async (activateRevision = false) => {
    if (!definitionStatus.valid) {
      setError(definitionStatus.message);
      return;
    }
    if (builderValidationIssues.length > 0) {
      setError('Fix the ASL validation issues before saving the workflow.');
      return;
    }

    setSaving(true);
    setError(null);

    try {
      const definition = parseDefinition(definitionText);
      const positions = filterCanvasPositionsForDefinition(definition, canvasNodePositions);

      if (revisionEdit) {
        let savedRevision = await createWorkflowRevision(revisionEdit.workflow.id, {
          definition,
          activate: activateRevision,
        });
        try {
          savedRevision = await updateWorkflowCanvasLayout(
            revisionEdit.workflow.id,
            savedRevision.revision,
            positions,
          );
        } catch (layoutError) {
          toast.error(layoutError instanceof Error
            ? `Revision saved, but its canvas layout could not be saved: ${layoutError.message}`
            : 'Revision saved, but its canvas layout could not be saved.');
        }

        const createdNewRevision = savedRevision.revision !== revisionEdit.baseRevision.revision;
        setRevisionSaveCompleted(true);
        onUnsavedChangesChange?.(false);
        await onWorkflowRevisionSaved?.(revisionEdit.workflow.id, savedRevision);
        if (createdNewRevision) {
          toast.success(savedRevision.active
            ? `Revision ${savedRevision.revision} saved and activated.`
            : `Revision ${savedRevision.revision} saved.`);
        } else {
          toast.success(savedRevision.active
            ? `Revision ${savedRevision.revision} is active; its canvas layout is saved.`
            : `Revision ${savedRevision.revision} canvas layout saved.`);
        }
        onNavigate?.(`/workflows/${encodeURIComponent(revisionEdit.workflow.id)}`);
        return;
      }

      if (conversationId) {
        // Persist the latest editor/settings snapshot before finalizing. The dedicated endpoint
        // permanently links this workspace to the created workflow, so reopening /c/{id} or
        // /draft/{id} can save a new revision instead of replaying the idempotent create request.
        flushPendingWorkspaceSave();
        await workspaceSaveQueueRef.current.catch(() => undefined);
        const updatingExistingWorkflow = Boolean(linkedWorkflowId);
        const saveRequest = {
          workflow: {
            name: name.trim(),
            cronExpression: cronExpression.trim() || null,
            timezone: timezone.trim() || 'UTC',
            maxAttempts,
            idempotencyKey: idempotencyKey.trim(),
            definition,
          },
          canvasLayout: positions,
        };
        const saved = routeDraftId
          ? await saveWorkflowAiDraft(conversationId, saveRequest)
          : await saveWorkflowAiConversation(conversationId, saveRequest);
        setLinkedWorkflowId(saved.workflow.id);
        toast.success(updatingExistingWorkflow
          ? `Revision ${saved.revision.revision} saved${saved.revision.active ? ' and activated' : ''}.`
          : 'Workflow saved. Future saves from this workspace will create revisions.');
        onWorkflowCreated(saved.workflow);
        return;
      }

      const workflow = await createWorkflow({
        name: name.trim(),
        cronExpression: cronExpression.trim() || null,
        timezone: timezone.trim() || 'UTC',
        maxAttempts,
        idempotencyKey: idempotencyKey.trim(),
        definition,
      });
      if (Object.keys(positions).length > 0) {
        try {
          await updateWorkflowCanvasLayout(
            workflow.id,
            workflow.activeDefinition?.revision || 1,
            positions,
          );
        } catch (layoutError) {
          toast.error(layoutError instanceof Error
            ? `Workflow created, but its canvas layout could not be saved: ${layoutError.message}`
            : 'Workflow created, but its canvas layout could not be saved.');
        }
      }
      onWorkflowCreated(workflow);
    } catch (err: any) {
      setError(err.message || (revisionEdit ? 'Failed to save workflow revision.' : 'Failed to create workflow.'));
    } finally {
      setSaving(false);
    }
  };

  const toLocalModel = (model: Parameters<typeof aiModelFromDto>[0]): AiModel => ({
    ...aiModelFromDto(model),
  });

  const localCredential = () => localCredentialRef.trim() || null;

  const mergeModels = (nextModels: AiModel[]) => {
    const enabledNextModels = nextModels.filter((model) => model.enabled !== false);
    setModels((current) => [
      ...enabledNextModels,
      ...current.filter((item) => !enabledNextModels.some((model) => model.id === item.id)),
    ]);
    setAllModels((current) => [
      ...nextModels,
      ...current.filter((item) => !nextModels.some((model) => model.id === item.id)),
    ]);
    if (enabledNextModels.length > 0 && (!modelId || models.length === 0)) {
      setModelId(enabledNextModels[0].id);
    }
    if (nextModels.length > 0) {
      setExpandedEndpoint((current) => current || nextModels[0].endpoint);
    }
  };

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
        credential: localCredential(),
        defaultModel: models.length === 0,
      });
      const model = toLocalModel(created);
      mergeModels([model]);
      selectModel(model.id);
      setModelActionMessage(`Connected ${model.label}.`);
      setModelActionSuccess(true);
      setLocalModelName('');
      setLocalCredentialRef('');
      setSettingsTab('added');
    } catch (err: any) {
      setModelActionMessage(err.message || 'Failed to add local model.');
      setModelActionSuccess(false);
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
    } catch (err: any) {
      toast.error(err.message || 'Failed to update endpoint models.');
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
    } catch (err: any) {
      toast.error(err.message || 'Failed to update model.');
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
      setExpandedEndpoint((current) => current === endpoint ? null : current);
      toast.success('Endpoint removed');
    } catch (err: any) {
      toast.error(err.message || 'Failed to delete endpoint models.');
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

  const apiCredential = () => apiCredentialRef.trim() || null;

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
        credential: apiCredential(),
        defaultModel: models.length === 0,
      });
      const model = toLocalModel(created);
      mergeModels([model]);
      selectModel(model.id);
      setApiActionMessage(`Connected ${model.label}.`);
      setApiActionSuccess(true);
      setApiModelName('');
      setApiCredentialRef('');
      setSettingsTab('added');
    } catch (err: any) {
      setApiActionMessage(err.message || 'Failed to add cloud model.');
      setApiActionSuccess(false);
    } finally {
      setAddingModel(null);
    }
  };

  const fieldClass = 'mt-2 h-10 w-full rounded-DEFAULT border border-border-subtle bg-surface-base px-3 text-body-md text-on-surface outline-none transition-colors placeholder:text-on-surface-variant/45 focus:border-secondary';
  const monoFieldClass = `${fieldClass} font-mono-sm text-[11px]`;
  const selectedModel = models.find((model) => model.id === modelId) || null;
  const filteredModels = models.filter((model) => {
    const query = modelSearch.trim().toLowerCase();
    if (!query) return true;
    return `${model.label} ${model.endpoint}`.toLowerCase().includes(query);
  });
  const endpointGroups = useMemo(() => {
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
      hasCredential: endpointModels.some((model) => model.hasCredential),
    }));
  }, [allModels]);
  const localEndpointNeedsDockerHint = /\/\/(localhost|127\.0\.0\.1)(:|\/|$)/i.test(discoverEndpoint.trim());
  const modeSwitch = (
    <div className="mode-switch grid w-[300px] grid-cols-2 rounded-lg p-1" data-mode={mode}>
      <button
        type="button"
        onClick={() => setMode('ai')}
        className={`relative z-10 flex h-10 items-center justify-center gap-2 rounded-md font-mono-sm text-label-mono transition-colors ${mode === 'ai' ? 'text-primary' : 'text-on-surface-variant hover:text-on-surface'}`}
      >
        <Sparkles size={15} />
        AI Generator
      </button>
      <button
        type="button"
        data-testid="workflow-mode-manual"
        onClick={() => setMode('manual')}
        className={`relative z-10 flex h-10 items-center justify-center gap-2 rounded-md font-mono-sm text-label-mono transition-colors ${mode === 'manual' ? 'text-primary' : 'text-on-surface-variant hover:text-on-surface'}`}
      >
        <Braces size={15} />
        Manual ASL
      </button>
    </div>
  );

  useEffect(() => {
    if (!modelPickerOpen) return;

    const handlePointerDown = (event: PointerEvent) => {
      if (modelPickerRef.current?.contains(event.target as Node)) return;
      setModelPickerOpen(false);
    };

    document.addEventListener('pointerdown', handlePointerDown);

    return () => {
      document.removeEventListener('pointerdown', handlePointerDown);
    };
  }, [modelPickerOpen]);

  useEffect(() => {
    const textarea = instructionTextareaRef.current;
    if (!textarea) return;

    textarea.style.height = 'auto';
    const maxHeight = 220;
    const nextHeight = Math.min(textarea.scrollHeight, maxHeight);
    textarea.style.height = `${nextHeight}px`;
    textarea.style.overflowY = textarea.scrollHeight > maxHeight ? 'auto' : 'hidden';
  }, [instruction]);

  const handleSubmitPrompt = (prompt: string) => {
    setPreviewPrompt(null);
    void handleGenerate(prompt);
  };

  const handleImportTemplate = (raw: string, fileName?: string) => {
    try {
      const parsed = parseDefinition(raw);
      const formattedDefinition = formatJson(parsed);
      handleDefinitionTextChange(formattedDefinition);
      setCanvasNodePositions({});
      setValidationIssues([]);
      setError(null);
      setMode('manual');
      toast.success(fileName ? `Imported ${fileName} into the ASL editor.` : 'Template imported into the ASL editor.');
      return true;
    } catch (err: any) {
      toast.error(err?.message ? `Import failed: ${err.message}` : 'Import failed. Expected ASL JSON with StartAt and States.');
      return false;
    }
  };

  const composerNode = (options: { placeholder: string; onSubmit: () => void; compact?: boolean }) => (
    <ChatComposer
      instruction={instruction}
      onInstructionChange={setInstruction}
      placeholder={options.placeholder}
      onSubmit={options.onSubmit}
      compact={options.compact}
      instructionTextareaRef={instructionTextareaRef}
      modelPickerRef={modelPickerRef}
      selectedModel={selectedModel}
      filteredModels={filteredModels}
      modelId={modelId}
      modelSearch={modelSearch}
      onModelSearchChange={setModelSearch}
      modelPickerOpen={modelPickerOpen}
      setModelPickerOpen={setModelPickerOpen}
      modelPickerPlacement={modelPickerPlacement}
      setModelPickerPlacement={setModelPickerPlacement}
      onModelSelected={selectModel}
      onOpenModelSettings={() => {
        setModelPickerOpen(false);
        setAddModelOpen(true);
      }}
      generating={generating || conversationLoading}
      canGenerate={canGenerate}
    />
  );

  const chatInputNode = composerNode({
    placeholder: previewPrompt || 'Describe a workflow...',
    onSubmit: () => handleGenerate(),
  });

  const handleSidebarPrompt = (prompt?: string) => handleGenerate(prompt, { includeDefinition: true });

  // Revision edits stay out of the assistant: starting a conversation routes to /c/<id>, which would
  // navigate away from the revision being edited, and accepting a plan creates a separate workflow.
  const sidebarAiChatNode = revisionEdit ? undefined : (
    <SidebarAiChat
      messages={messages}
      generating={generating}
      error={error}
      hasStates={definitionStats.stateCount > 0}
      onSubmitPrompt={handleSidebarPrompt}
      expandedThinkingMessageIds={expandedThinkingMessageIds}
      onToggleThinking={toggleThinking}
      regeneratingMessageId={regeneratingMessageId}
      onRegenerateMessage={handleRegenerateMessage}
      renderMessageAttachment={(message) => message.resourcePlan ? (
        <ResourcePlanCard
          plan={message.resourcePlan}
          activePlan={message.id === activeResourcePlanMessageId ? activeResourcePlan : null}
          busy={generating}
          resolved={message.id !== activeResourcePlanMessageId}
          onApprove={handleProvisionResources}
          onContinue={handleContinueAfterMcp}
          onOpenMcpServers={() => onNavigate?.('/mcp')}
        />
      ) : undefined}
      chatInputNode={composerNode({
        placeholder: 'Ask for a change to this workflow...',
        onSubmit: () => handleSidebarPrompt(),
        compact: true,
      })}
    />
  );

  return (
    <div className="relative flex h-full min-h-0 flex-col text-on-surface">
      {!revisionEdit && messages.length === 0 && (
        <header className="pointer-events-none absolute right-10 top-1 z-30 flex justify-end">
          <div className="pointer-events-auto">
            {modeSwitch}
          </div>
        </header>
      )}
      {mode === 'ai' ? (
        <div className="flex min-h-0 flex-1 overflow-hidden bg-transparent">
          <div className="relative flex flex-1 flex-col overflow-hidden">
            <WorkflowAiEmptyState
              chatInputNode={chatInputNode}
              onSubmitPrompt={handleSubmitPrompt}
              onPreviewPrompt={setPreviewPrompt}
              onImportTemplate={handleImportTemplate}
            />
          </div>
        </div>
      ) : (
        <ManualWorkflowEditor
          definitionText={definitionText}
          onDefinitionTextChange={handleDefinitionTextChange}
          definitionStatus={definitionStatus}
          definitionStats={definitionStats}
          error={error}
          validationIssues={builderValidationIssues}
          saving={saving}
          canSave={canSave}
          onSave={() => void handleSave(Boolean(revisionEdit?.workflow.cronExpression))}
          onSaveWithoutActivation={revisionEdit ? () => void handleSave(false) : undefined}
          saveCreatesRevision={Boolean(linkedWorkflowId)}
          revisionEdit={revisionEdit ? {
            baseRevision: revisionEdit.baseRevision.revision,
            recurring: Boolean(revisionEdit.workflow.cronExpression),
            baseRevisionActive: revisionEdit.baseRevision.active,
            hasUnsavedChanges: hasUnsavedRevisionChanges,
          } : undefined}
          onCancelRevisionEdit={revisionEdit
            ? () => onNavigate?.(`/workflows/${encodeURIComponent(revisionEdit.workflow.id)}`)
            : undefined}
          name={name}
          onNameChange={setName}
          maxAttempts={maxAttempts}
          onMaxAttemptsChange={setMaxAttempts}
          idempotencyKey={idempotencyKey}
          onIdempotencyKeyChange={setIdempotencyKey}
          cronExpression={cronExpression}
          onCronExpressionChange={setCronExpression}
          timezone={timezone}
          onTimezoneChange={setTimezone}
          fieldClass={fieldClass}
          monoFieldClass={monoFieldClass}
          taskResourceOptions={taskResourceOptions}
          reserveTopControlsSpace={!revisionEdit && messages.length === 0}
          initialNodePositions={canvasNodePositions}
          onNodePositionsChange={setCanvasNodePositions}
          onImportTemplate={handleImportTemplate}
          aiChatNode={sidebarAiChatNode}
          startInAiChat={messages.length > 0}
        />
      )}

      {/* Outside the mode branch: the model picker is reachable from both composers. */}
      {addModelOpen && (
        <ModelSettingsModal
          settingsTab={settingsTab}
          onSettingsTabChange={setSettingsTab}
          onClose={() => setAddModelOpen(false)}
          fieldClass={fieldClass}
          discoverEndpoint={discoverEndpoint}
          onDiscoverEndpointChange={setDiscoverEndpoint}
          localModelName={localModelName}
          onLocalModelNameChange={setLocalModelName}
          onAddLocalModel={addLocalModel}
          addingModel={addingModel}
          localCredentialRef={localCredentialRef}
          onLocalCredentialRefChange={setLocalCredentialRef}
          localEndpointNeedsDockerHint={localEndpointNeedsDockerHint}
          modelActionMessage={modelActionMessage}
          modelActionSuccess={modelActionSuccess}
          apiProvider={apiProvider}
          onApiProviderChange={changeApiProvider}
          apiEndpoint={apiEndpoint}
          onApiEndpointChange={setApiEndpoint}
          apiModelName={apiModelName}
          onApiModelNameChange={setApiModelName}
          apiCredentialRef={apiCredentialRef}
          onApiCredentialRefChange={setApiCredentialRef}
          onAddApiModel={addApiModel}
          apiActionMessage={apiActionMessage}
          apiActionSuccess={apiActionSuccess}
          endpointGroups={endpointGroups}
          expandedEndpoint={expandedEndpoint}
          setExpandedEndpoint={setExpandedEndpoint}
          managingModels={managingModels}
          onCopyEndpoint={copyEndpoint}
          onUpdateEndpointEnabled={updateEndpointEnabled}
          onDeleteEndpointModels={deleteEndpointModels}
          onUpdateSingleModelEnabled={updateSingleModelEnabled}
        />
      )}
    </div>
  );
}
