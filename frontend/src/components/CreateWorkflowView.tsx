import { useEffect, useMemo, useRef, useState } from 'react';
import { Braces, Sparkles } from 'lucide-react';
import { toast } from 'sonner';
import {
  acceptWorkflowAiPlan,
  continueWorkflowAiConversation,
  createWorkflow,
  deleteAiModel,
  discoverAiModels,
  getWorkflowAiConversation,
  listAllAiModels,
  listAiModels,
  regenerateWorkflowAiMessage,
  reviewWorkflowAiAsl,
  setAiModelEnabled,
  startWorkflowAiConversation,
  testAiModel,
  type WorkflowAiMessageDTO,
  type WorkflowAiConversationSummaryDTO,
  type WorkflowAiResponse,
  type WorkflowAiStage,
  type WorkflowPriorityDTO,
  type WorkflowResponseDTO,
} from '../api';
import { AslReviewPanel } from './workflow-create/AslReviewPanel';
import { ChatComposer } from './workflow-create/ChatComposer';
import { ChatMessageList } from './workflow-create/ChatMessageList';
import { ManualWorkflowEditor } from './workflow-create/ManualWorkflowEditor';
import { ModelSettingsModal } from './workflow-create/ModelSettingsModal';
import { WorkflowAiEmptyState } from './workflow-create/WorkflowAiEmptyState';
import { WorkflowStageStrip } from './workflow-create/WorkflowStageStrip';
import type {
  AiModel,
  ChatMessage,
  ChatSnapshot,
  DefinitionMode,
  ModelSettingsTab,
  WorkflowPreview,
} from './workflow-create/types';

const starterDefinition = {
  StartAt: 'Done',
  States: {
    Done: {
      Type: 'Succeed',
    },
  },
};

type Props = {
  onWorkflowCreated: (workflow: WorkflowResponseDTO) => void;
  onNavigate?: (path: string, options?: { replace?: boolean }) => void;
  routeChatId?: string;
  onChatStarted?: (chat: WorkflowAiConversationSummaryDTO) => void;
  onChatUpdated?: (previousId: string | null, chat: WorkflowAiConversationSummaryDTO) => void;
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

function newChatMessageId() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }

  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function newConversationRouteId() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }

  return `local-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function chatStorageKey(chatId: string) {
  return `voyager-chat:${chatId}`;
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

function chatSummary(
  id: string,
  name: string,
  stage: WorkflowAiStage,
  initialInstruction: string,
): WorkflowAiConversationSummaryDTO {
  const timestamp = new Date().toISOString();
  return {
    id,
    name: name.trim() || initialInstruction.trim().slice(0, 48) || 'New workflow chat',
    stage,
    initialInstruction,
    createdAt: timestamp,
    updatedAt: timestamp,
  };
}

function formatMessageTime(value: number) {
  return new Intl.DateTimeFormat(undefined, {
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

function chatMessageFromDto(message: WorkflowAiMessageDTO): ChatMessage {
  return {
    id: message.id,
    role: message.role === 'USER' ? 'user' : 'assistant',
    content: message.content,
    createdAt: new Date(message.createdAt).getTime(),
    modelConfigId: message.modelConfigId,
    modelDisplayName: message.modelDisplayName,
    durationMs: message.durationMs,
    inputTokens: message.inputTokens,
    outputTokens: message.outputTokens,
    totalTokens: message.totalTokens,
    thinkingContent: message.thinkingContent,
    finishReason: message.finishReason,
    regeneratedFromMessageId: message.regeneratedFromMessageId,
  };
}

function aiModelFromDto(model: {
  id: string;
  displayName: string;
  baseUrl: string;
  modelName: string;
  enabled?: boolean;
  defaultModel?: boolean;
}): AiModel {
  return {
    id: model.id,
    label: model.displayName || model.modelName,
    endpoint: model.baseUrl,
    modelName: model.modelName,
    provider: 'local',
    enabled: model.enabled,
    defaultModel: model.defaultModel,
  };
}

function endpointHost(endpoint: string) {
  try {
    return new URL(endpoint).host;
  } catch {
    return endpoint.replace(/^https?:\/\//, '').replace(/\/v1\/?$/, '');
  }
}

export function CreateWorkflowView({
  onWorkflowCreated,
  onNavigate,
  routeChatId,
  onChatStarted,
  onChatUpdated,
}: Props) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isEditorOpen, setIsEditorOpen] = useState(false);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [conversationStage, setConversationStage] = useState<WorkflowAiStage>('COLLECTING_WORKFLOW_DETAILS');
  const [editingMessageId, setEditingMessageId] = useState<string | null>(null);
  const [editingMessageContent, setEditingMessageContent] = useState('');
  const [copiedMessageId, setCopiedMessageId] = useState<string | null>(null);
  const [chatEntered, setChatEntered] = useState(false);
  const [mode, setMode] = useState<DefinitionMode>('ai');
  const [name, setName] = useState('');
  const [priority, setPriority] = useState<WorkflowPriorityDTO>('MEDIUM');
  const [maxAttempts, setMaxAttempts] = useState(3);
  const [cronExpression, setCronExpression] = useState('');
  const [timezone, setTimezone] = useState('UTC');
  const [idempotencyKey, setIdempotencyKey] = useState(newIdempotencyKey);
  const [instruction, setInstruction] = useState('');
  const [models, setModels] = useState<AiModel[]>([]);
  const [allModels, setAllModels] = useState<AiModel[]>([]);
  const [modelId, setModelId] = useState('');
  const [modelSearch, setModelSearch] = useState('');
  const [modelPickerOpen, setModelPickerOpen] = useState(false);
  const [modelPickerPlacement, setModelPickerPlacement] = useState<'down' | 'up'>('down');
  const [addModelOpen, setAddModelOpen] = useState(false);
  const [settingsTab, setSettingsTab] = useState<ModelSettingsTab>('add');
  const [localApiKey, setLocalApiKey] = useState('');
  const [showLocalApiKey, setShowLocalApiKey] = useState(false);
  const [localActionsOpen, setLocalActionsOpen] = useState(false);
  const [apiProvider, setApiProvider] = useState('DeepSeek');
  const [apiModelName, setApiModelName] = useState('');
  const [apiEndpoint, setApiEndpoint] = useState('https://api.deepseek.com/v1');
  const [modelActionMessage, setModelActionMessage] = useState<string | null>(null);
  const [modelActionSuccess, setModelActionSuccess] = useState<boolean | null>(null);
  const [addingModel, setAddingModel] = useState(false);
  const [testingModel, setTestingModel] = useState(false);
  const [discoverEndpoint, setDiscoverEndpoint] = useState('');
  const [discoveringModels, setDiscoveringModels] = useState(false);
  const [discoveredModelNames, setDiscoveredModelNames] = useState<string[]>([]);
  const [expandedEndpoint, setExpandedEndpoint] = useState<string | null>(null);
  const [managingModels, setManagingModels] = useState(false);
  const [definitionText, setDefinitionText] = useState(formatJson(starterDefinition));
  const [validationIssues, setValidationIssues] = useState<string[]>([]);
  const [generating, setGenerating] = useState(false);
  const [regeneratingMessageId, setRegeneratingMessageId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [accepting, setAccepting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expandedThinkingMessageIds, setExpandedThinkingMessageIds] = useState<Set<string>>(() => new Set());
  const modelPickerRef = useRef<HTMLDivElement | null>(null);
  const localActionsRef = useRef<HTMLDivElement | null>(null);
  const instructionTextareaRef = useRef<HTMLTextAreaElement | null>(null);
  const chatScrollRef = useRef<HTMLDivElement | null>(null);
  const chatEndRef = useRef<HTMLDivElement | null>(null);
  const lastLoadedRouteChatIdRef = useRef<string | null>(null);
  const suppressNextSnapshotSaveRef = useRef(false);
  const streamingTimerRefs = useRef<number[]>([]);

  const hasSelectedModel = models.some((model) => model.id === modelId);
  const canGenerate = instruction.trim().length > 0 && !generating && !saving && !accepting && hasSelectedModel;
  const canSave = name.trim().length > 0 && idempotencyKey.trim().length > 0 && !saving && !generating;
  const definitionStatus = useMemo(() => {
    try {
      parseDefinition(definitionText);
      return { valid: true, message: 'ASL JSON looks ready' };
    } catch (err: any) {
      return { valid: false, message: err.message || 'Definition JSON is invalid' };
    }
  }, [definitionText]);
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setModelPickerOpen(false);
        setAddModelOpen(false);
        setLocalActionsOpen(false);
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
        startAt: typeof definition.StartAt === 'string' ? definition.StartAt : '-',
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
    let allDtos = enabledDtos;
    try {
      allDtos = await listAllAiModels();
    } catch {
      allDtos = enabledDtos;
    }
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

  useEffect(() => () => {
    streamingTimerRefs.current.forEach((timerId) => window.clearTimeout(timerId));
    streamingTimerRefs.current = [];
  }, []);

  useEffect(() => {
    if (!routeChatId || lastLoadedRouteChatIdRef.current === routeChatId) {
      return;
    }

    lastLoadedRouteChatIdRef.current = routeChatId;

    try {
      const rawSnapshot = window.sessionStorage.getItem(chatStorageKey(routeChatId));
      if (!rawSnapshot) {
        if (messages.length > 0 && (!conversationId || conversationId === routeChatId)) {
          return;
        }

        setMessages([]);
        setError(null);
        getWorkflowAiConversation(routeChatId)
          .then((conversation) => {
            setMessages(conversation.messages.map(chatMessageFromDto));
            setIsEditorOpen(Boolean(conversation.aslDefinition));
            setConversationId(conversation.id);
            setConversationStage(conversation.stage);
            if (conversation.modelConfigId) {
              selectModel(conversation.modelConfigId);
            }
            setName(conversation.draftWorkflowPayload?.name || conversation.name || '');
            setPriority(conversation.draftWorkflowPayload?.priority || 'MEDIUM');
            setMaxAttempts(conversation.draftWorkflowPayload?.maxAttempts ?? 3);
            setCronExpression(conversation.draftWorkflowPayload?.cronExpression || '');
            setTimezone(conversation.draftWorkflowPayload?.timezone || 'UTC');
            setIdempotencyKey(conversation.draftWorkflowPayload?.idempotencyKey || newIdempotencyKey());
            setDefinitionText(formatJson(conversation.aslDefinition || conversation.draftWorkflowPayload?.definition || starterDefinition));
            setValidationIssues([]);
            setError(null);
          })
          .catch((err: Error) => {
            setError(`Could not load chat: ${err.message}`);
          });
        return;
      }

      const snapshot = JSON.parse(rawSnapshot) as Partial<ChatSnapshot>;
      suppressNextSnapshotSaveRef.current = true;

      setMessages(snapshot.messages || []);
      setIsEditorOpen(Boolean(snapshot.isEditorOpen));
      setConversationId(snapshot.conversationId || null);
      setConversationStage(snapshot.conversationStage || 'COLLECTING_WORKFLOW_DETAILS');
      setName(snapshot.name || '');
      setPriority(snapshot.priority || 'MEDIUM');
      setMaxAttempts(snapshot.maxAttempts ?? 3);
      setCronExpression(snapshot.cronExpression || '');
      setTimezone(snapshot.timezone || 'UTC');
      setIdempotencyKey(snapshot.idempotencyKey || newIdempotencyKey());
      setDefinitionText(snapshot.definitionText || formatJson(starterDefinition));
      setValidationIssues(snapshot.validationIssues || []);
      if (snapshot.modelId) {
        selectModel(snapshot.modelId);
      }
      setError(snapshot.error || null);
    } catch {
      window.sessionStorage.removeItem(chatStorageKey(routeChatId));
    }
  }, [routeChatId, messages.length, conversationId]);

  useEffect(() => {
    if (!routeChatId) {
      return;
    }

    if (suppressNextSnapshotSaveRef.current) {
      suppressNextSnapshotSaveRef.current = false;
      return;
    }

    const snapshot: ChatSnapshot = {
      messages,
      isEditorOpen,
      conversationId,
      conversationStage,
      name,
      priority,
      maxAttempts,
      cronExpression,
      timezone,
      idempotencyKey,
      definitionText,
      validationIssues,
      modelId,
      error,
    };

    window.sessionStorage.setItem(chatStorageKey(routeChatId), JSON.stringify(snapshot));
  }, [
    routeChatId,
    messages,
    isEditorOpen,
    conversationId,
    conversationStage,
    name,
    priority,
    maxAttempts,
    cronExpression,
    timezone,
    idempotencyKey,
    definitionText,
    validationIssues,
    modelId,
    error,
  ]);

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
    const targetId = replaceMessageId || assistantMessage.id;
    const fullThinking = assistantMessage.thinkingContent || '';
    const fullContent = assistantMessage.content || '';
    const streamingMessage: ChatMessage = {
      ...assistantMessage,
      id: targetId,
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
        id: targetId,
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
    setConversationId(response.conversationId);
    setConversationStage(response.stage);
    setValidationIssues(response.validationIssues || []);

    if (response.aslDefinition) {
      setDefinitionText(formatJson(response.aslDefinition));
      setIsEditorOpen(response.stage === 'ASL_READY' || response.stage === 'ASL_UNDER_REVIEW' || response.stage === 'COLLECTING_SCHEDULE_DETAILS');
    }

    if (response.draftWorkflowPayload) {
      setName(response.draftWorkflowPayload.name || response.conversationName || name);
      setPriority(response.draftWorkflowPayload.priority || 'MEDIUM');
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

    const assistantMessage = response.assistantMessage
      ? chatMessageFromDto(response.assistantMessage)
      : {
          id: newChatMessageId(),
          role: 'assistant' as const,
          content: response.message || 'Updated workflow conversation.',
          createdAt: Date.now(),
          modelConfigId: modelId || null,
          modelDisplayName: selectedModel?.label || null,
        };

    if (assistantMessage.thinkingContent) {
      setExpandedThinkingMessageIds((current) => {
        const next = new Set(current);
        next.add(options.replaceMessageId || assistantMessage.id);
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
          message.id === options.replaceMessageId
            ? { ...assistantMessage, id: options.replaceMessageId }
            : message
        ));
      }
      return [...prev, assistantMessage];
    });
  };

  const handleGenerate = async () => {
    const selectedModelForSend = models.find((model) => model.id === modelId) || null;
    if (!selectedModelForSend) {
      setModelPickerOpen(true);
      toast.error('Select a model before sending.');
      return;
    }
    if (!canGenerate) return;

    const currentInstruction = instruction;
    const sentAt = Date.now();
    const startingNewConversation = !conversationId;
    const provisionalConversationId = startingNewConversation ? newConversationRouteId() : null;
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
    if (provisionalConversationId && onNavigate) {
      onChatStarted?.(chatSummary(
        provisionalConversationId,
        currentInstruction,
        'COLLECTING_WORKFLOW_DETAILS',
        currentInstruction,
      ));
      onNavigate(`/c/${provisionalConversationId}`);
    }

    setGenerating(true);
    setError(null);
    setValidationIssues([]);

    try {
      const response = conversationId
        ? await continueWorkflowAiConversation({
            conversationId,
            message: currentInstruction,
            modelConfigId: modelId || null,
          })
        : await startWorkflowAiConversation({
            instruction: currentInstruction,
            modelConfigId: modelId || null,
            userDateTime: new Date().toISOString(),
          });
      applyWorkflowAiResponse(response, {
        replaceMessageId: processingMessageId,
        animate: true,
      });
      if (provisionalConversationId && onNavigate && response.conversationId !== provisionalConversationId) {
        onChatUpdated?.(provisionalConversationId, chatSummary(
          response.conversationId,
          response.conversationName,
          response.stage,
          currentInstruction,
        ));
        onNavigate(`/c/${response.conversationId}`, { replace: true });
      } else {
        onChatUpdated?.(conversationId, chatSummary(
          response.conversationId,
          response.conversationName,
          response.stage,
          currentInstruction,
        ));
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

  const handleReviewAsl = async () => {
    if (!conversationId || generating || saving) return;

    setGenerating(true);
    setError(null);

    try {
      const definition = parseDefinition(definitionText);
      setMessages((prev) => [...prev, {
        id: newChatMessageId(),
        role: 'user',
        content: 'Check the ASL in the editor against my original request.',
        createdAt: Date.now(),
        modelConfigId: modelId || null,
        modelDisplayName: selectedModel?.label || null,
      }]);
      const response = await reviewWorkflowAiAsl({ conversationId, definition });
      applyWorkflowAiResponse(response);
    } catch (err: any) {
      setError(err.message || 'Failed to review ASL.');
    } finally {
      setGenerating(false);
    }
  };

  const handleAcceptPlan = async () => {
    if (!conversationId || accepting) return;

    setAccepting(true);
    setError(null);

    try {
      const response = await acceptWorkflowAiPlan({ conversationId });
      applyWorkflowAiResponse(response);
      if (response.workflow) {
        onWorkflowCreated(response.workflow);
      }
    } catch (err: any) {
      setError(err.message || 'Failed to accept final plan.');
    } finally {
      setAccepting(false);
    }
  };

  const startEditingMessage = (message: ChatMessage) => {
    setEditingMessageId(message.id);
    setEditingMessageContent(message.content);
  };

  const saveEditedMessage = () => {
    const nextContent = editingMessageContent.trim();
    if (!editingMessageId || !nextContent) return;

    setMessages((current) => current.map((message) => (
      message.id === editingMessageId ? { ...message, content: nextContent } : message
    )));
    setEditingMessageId(null);
    setEditingMessageContent('');
  };

  const cancelEditingMessage = () => {
    setEditingMessageId(null);
    setEditingMessageContent('');
  };

  const copyMessage = async (message: ChatMessage) => {
    try {
      await navigator.clipboard.writeText(message.content);
      setCopiedMessageId(message.id);
      window.setTimeout(() => setCopiedMessageId((current) => current === message.id ? null : current), 1200);
    } catch {
      setCopiedMessageId(null);
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
    if (!conversationId || message.role !== 'assistant' || regeneratingMessageId) return;

    setRegeneratingMessageId(message.id);
    setError(null);
    try {
      const response = await regenerateWorkflowAiMessage(message.id, {
        modelConfigId: modelId || null,
      });
      applyWorkflowAiResponse(response);
    } catch (err: any) {
      setError(err.message || 'Failed to regenerate message.');
    } finally {
      setRegeneratingMessageId(null);
    }
  };

  const handleSave = async () => {
    setSaving(true);
    setError(null);

    try {
      const definition = parseDefinition(definitionText);
      const workflow = await createWorkflow({
        name: name.trim(),
        priority,
        cronExpression: cronExpression.trim() || null,
        timezone: timezone.trim() || 'UTC',
        maxAttempts,
        idempotencyKey: idempotencyKey.trim(),
        definition,
      });
      onWorkflowCreated(workflow);
    } catch (err: any) {
      setError(err.message || 'Failed to create workflow.');
    } finally {
      setSaving(false);
    }
  };

  const toLocalModel = (model: {
    id: string;
    displayName: string;
    baseUrl: string;
    modelName: string;
    enabled?: boolean;
    defaultModel?: boolean;
  }): AiModel => ({
    ...aiModelFromDto(model),
  });

  const localAuth = () => localApiKey.trim() || null;

  const mergeDiscoveredModels = (nextModels: AiModel[]) => {
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

  const testLocalEndpoint = async () => {
    const endpoint = discoverEndpoint.trim();
    if (!endpoint) {
      setModelActionMessage('Enter an endpoint before testing.');
      setModelActionSuccess(false);
      return;
    }

    setTestingModel(true);
    setLocalActionsOpen(false);
    setModelActionMessage(null);
    setModelActionSuccess(null);
    setDiscoveredModelNames([]);

    try {
      const response = await testAiModel({ baseUrl: endpoint, apiKey: localAuth() });
      setModelActionMessage(response.message);
      setModelActionSuccess(response.success);
    } catch (err: any) {
      setModelActionMessage(err.message || 'Failed to test endpoint.');
      setModelActionSuccess(false);
    } finally {
      setTestingModel(false);
    }
  };

  const discoverModels = async (endpointOverride?: string) => {
    const endpoint = (endpointOverride || discoverEndpoint).trim();
    if (!endpoint) return;

    setAddingModel(true);
    setLocalActionsOpen(false);
    setModelActionMessage(null);
    setModelActionSuccess(null);
    setDiscoveredModelNames([]);

    try {
      const discovered = await discoverAiModels({ baseUrl: endpoint, apiKey: localAuth() });
      const nextModels = discovered.map(toLocalModel);
      mergeDiscoveredModels(nextModels);
      setDiscoveredModelNames(nextModels.map((model) => model.label));
      setModelActionMessage(`Added ${discovered.length} model${discovered.length === 1 ? '' : 's'}.`);
      setModelActionSuccess(true);
    } catch (err: any) {
      setModelActionMessage(err.message || 'Failed to discover models.');
      setModelActionSuccess(false);
      setDiscoveredModelNames([]);
    } finally {
      setAddingModel(false);
    }
  };

  const scanForServers = async () => {
    const candidates = [
      'http://host.docker.internal:11434/v1',
      'http://host.docker.internal:8000/v1',
    ];
    const existingKeys = new Set(models.map((model) => `${model.endpoint}|${model.modelName || model.label}`));
    const foundServerLabels: string[] = [];
    const allModels: AiModel[] = [];
    let totalModels = 0;
    let alreadyAdded = 0;

    setDiscoveringModels(true);
    setLocalActionsOpen(false);
    setModelActionMessage(null);
    setModelActionSuccess(null);
    setDiscoveredModelNames([]);

    for (const endpoint of candidates) {
      try {
        const discovered = await discoverAiModels({ baseUrl: endpoint, apiKey: localAuth() });
        if (discovered.length === 0) continue;
        const nextModels = discovered.map(toLocalModel);
        foundServerLabels.push(new URL(endpoint).host);
        totalModels += nextModels.length;
        alreadyAdded += nextModels.filter((model) => existingKeys.has(`${model.endpoint}|${model.modelName || model.label}`)).length;
        allModels.push(...nextModels);
      } catch {
        // Keep scanning other Docker-reachable local endpoints.
      }
    }

    if (allModels.length === 0) {
      setModelActionMessage('No local model servers found.');
      setModelActionSuccess(false);
      setDiscoveringModels(false);
      return;
    }

    mergeDiscoveredModels(allModels);
    setDiscoveredModelNames(allModels.map((model) => model.label));
    setDiscoverEndpoint(allModels[0].endpoint);
    setModelActionMessage(
      `Found ${foundServerLabels.length} server${foundServerLabels.length === 1 ? '' : 's'} (${foundServerLabels.join(', ')}) with ${totalModels} model${totalModels === 1 ? '' : 's'}${alreadyAdded > 0 ? ` - ${alreadyAdded} already added` : ''}.`,
    );
    setModelActionSuccess(true);
    setDiscoveringModels(false);
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

  const refreshEndpointModels = async (endpoint: string) => {
    setManagingModels(true);
    try {
      const discovered = await discoverAiModels({ baseUrl: endpoint, apiKey: localAuth() });
      mergeDiscoveredModels(discovered.map(toLocalModel));
      await refreshModelLists();
      toast.success('Endpoint refreshed');
    } catch (err: any) {
      toast.error(err.message || 'Failed to refresh endpoint models.');
    } finally {
      setManagingModels(false);
    }
  };

  const probeAllEndpoints = async () => {
    const endpoints = [...new Set(allModels.map((model) => model.endpoint))];
    if (endpoints.length === 0) return;

    setManagingModels(true);
    try {
      const results = await Promise.allSettled(
        endpoints.map((endpoint) => discoverAiModels({ baseUrl: endpoint, apiKey: localAuth() })),
      );
      const discoveredModels = results
        .filter((result): result is PromiseFulfilledResult<Awaited<ReturnType<typeof discoverAiModels>>> => result.status === 'fulfilled')
        .flatMap((result) => result.value.map(toLocalModel));
      if (discoveredModels.length > 0) {
        mergeDiscoveredModels(discoveredModels);
      }
      await refreshModelLists();
      const failedCount = results.filter((result) => result.status === 'rejected').length;
      if (failedCount > 0) {
        toast.warning('Probe finished with unreachable endpoints');
      } else {
        toast.success('Probe complete');
      }
    } catch (err: any) {
      toast.error(err.message || 'Failed to probe endpoints.');
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

  const addApiModel = () => {
    const endpoint = apiEndpoint.trim();
    const label = apiModelName.trim() || apiProvider;
    if (!endpoint || !label) return;

    const model = {
      id: `api-${Date.now()}`,
      label,
      endpoint,
      provider: 'api' as const,
    };
    setModels((current) => [model, ...current]);
    selectModel(model.id);
    setApiModelName('');
    setAddModelOpen(false);
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
    if (!localActionsOpen) return;

    const handlePointerDown = (event: PointerEvent) => {
      if (localActionsRef.current?.contains(event.target as Node)) return;
      setLocalActionsOpen(false);
    };

    document.addEventListener('pointerdown', handlePointerDown);

    return () => {
      document.removeEventListener('pointerdown', handlePointerDown);
    };
  }, [localActionsOpen]);

  useEffect(() => {
    const textarea = instructionTextareaRef.current;
    if (!textarea) return;

    textarea.style.height = 'auto';
    const maxHeight = 220;
    const nextHeight = Math.min(textarea.scrollHeight, maxHeight);
    textarea.style.height = `${nextHeight}px`;
    textarea.style.overflowY = textarea.scrollHeight > maxHeight ? 'auto' : 'hidden';
  }, [instruction]);

  useEffect(() => {
    if (messages.length === 0) {
      setChatEntered(false);
      return;
    }

    const frame = window.requestAnimationFrame(() => setChatEntered(true));
    return () => window.cancelAnimationFrame(frame);
  }, [messages.length]);

  useEffect(() => {
    if (messages.length === 0) return;

    const timeout = window.setTimeout(() => {
      chatEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
    }, 40);

    return () => window.clearTimeout(timeout);
  }, [messages.length, generating, error]);

  const streamingActivityKey = useMemo(() => messages
    .filter((message) => message.streamingStatus)
    .map((message) => `${message.id}:${message.content.length}:${message.thinkingContent?.length || 0}:${message.streamingPhase || ''}`)
    .join('|'), [messages]);

  useEffect(() => {
    if (!streamingActivityKey) return;

    const timeout = window.setTimeout(() => {
      chatEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
    }, 40);

    return () => window.clearTimeout(timeout);
  }, [streamingActivityKey]);

  const handleSelectPrompt = (prompt: string) => {
    setInstruction(prompt);
    window.requestAnimationFrame(() => instructionTextareaRef.current?.focus());
  };

  const chatInputNode = (
    <ChatComposer
      instruction={instruction}
      onInstructionChange={setInstruction}
      onSubmit={handleGenerate}
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
      generating={generating}
      canGenerate={canGenerate}
    />
  );

  const latestAssistantModelLabel = [...messages]
    .reverse()
    .find((message) => message.role === 'assistant' && message.modelDisplayName)?.modelDisplayName;
  const chatSessionModelLabel = latestAssistantModelLabel || selectedModel?.label || 'AI model';
  const chatSessionTimeLabel = messages.length > 0 ? formatMessageTime(messages[messages.length - 1].createdAt) : '';

  return (
    <div className="relative flex h-full min-h-0 flex-col text-on-surface">
      {messages.length === 0 && (
        <header className="flex min-h-16 shrink-0 items-center justify-end px-10 pt-6">
          {modeSwitch}
        </header>
      )}
      {mode === 'ai' ? (
        <div className="flex min-h-0 flex-1 overflow-hidden bg-transparent">
          <div className="relative flex flex-1 flex-col overflow-hidden">
            {messages.length === 0 ? (
              <WorkflowAiEmptyState
                chatInputNode={chatInputNode}
                onSelectPrompt={handleSelectPrompt}
              />
            ) : (
              <>
                <ChatMessageList
                  messages={messages}
                  chatEntered={chatEntered}
                  chatScrollRef={chatScrollRef}
                  chatEndRef={chatEndRef}
                  chatSessionModelLabel={chatSessionModelLabel}
                  chatSessionTimeLabel={chatSessionTimeLabel}
                  selectedModel={selectedModel}
                  editingMessageId={editingMessageId}
                  editingMessageContent={editingMessageContent}
                  onEditingMessageContentChange={setEditingMessageContent}
                  copiedMessageId={copiedMessageId}
                  expandedThinkingMessageIds={expandedThinkingMessageIds}
                  regeneratingMessageId={regeneratingMessageId}
                  error={error}
                  headerNode={(
                    <WorkflowStageStrip
                      stage={conversationStage}
                      definitionStatus={definitionStatus}
                      generating={generating}
                    />
                  )}
                  onCancelEditingMessage={cancelEditingMessage}
                  onSaveEditedMessage={saveEditedMessage}
                  onStartEditingMessage={startEditingMessage}
                  onCopyMessage={copyMessage}
                  onToggleThinking={toggleThinking}
                  onRegenerateMessage={handleRegenerateMessage}
                />
                <div className="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-surface-base via-surface-base/90 to-transparent pt-12 pb-6 px-8 pointer-events-none">
                  <div className={`mx-auto w-full max-w-[900px] pointer-events-auto transition-all duration-500 ease-out ${chatEntered ? 'translate-y-0 opacity-100' : 'translate-y-10 opacity-0'}`}>
                    {chatInputNode}
                  </div>
                </div>
              </>
            )}
          </div>

          {isEditorOpen && (
            <AslReviewPanel
              conversationStage={conversationStage}
              conversationId={conversationId}
              generating={generating}
              accepting={accepting}
              definitionStatus={definitionStatus}
              workflowPreview={definitionStats}
              definitionText={definitionText}
              onDefinitionTextChange={setDefinitionText}
              onReviewAsl={handleReviewAsl}
              onAcceptPlan={handleAcceptPlan}
              onClose={() => setIsEditorOpen(false)}
            />
          )}

          {addModelOpen && (
            <ModelSettingsModal
              settingsTab={settingsTab}
              onSettingsTabChange={setSettingsTab}
              onClose={() => setAddModelOpen(false)}
              fieldClass={fieldClass}
              localActionsRef={localActionsRef}
              localActionsOpen={localActionsOpen}
              setLocalActionsOpen={setLocalActionsOpen}
              discoverEndpoint={discoverEndpoint}
              onDiscoverEndpointChange={setDiscoverEndpoint}
              onDiscoverModels={discoverModels}
              onScanForServers={scanForServers}
              onTestLocalEndpoint={testLocalEndpoint}
              addingModel={addingModel}
              testingModel={testingModel}
              discoveringModels={discoveringModels}
              showLocalApiKey={showLocalApiKey}
              setShowLocalApiKey={setShowLocalApiKey}
              localApiKey={localApiKey}
              onLocalApiKeyChange={setLocalApiKey}
              localEndpointNeedsDockerHint={localEndpointNeedsDockerHint}
              modelActionMessage={modelActionMessage}
              modelActionSuccess={modelActionSuccess}
              discoveredModelNames={discoveredModelNames}
              apiProvider={apiProvider}
              onApiProviderChange={setApiProvider}
              apiEndpoint={apiEndpoint}
              onApiEndpointChange={setApiEndpoint}
              apiModelName={apiModelName}
              onApiModelNameChange={setApiModelName}
              onAddApiModel={addApiModel}
              endpointGroups={endpointGroups}
              expandedEndpoint={expandedEndpoint}
              setExpandedEndpoint={setExpandedEndpoint}
              managingModels={managingModels}
              onProbeAllEndpoints={probeAllEndpoints}
              onCopyEndpoint={copyEndpoint}
              onUpdateEndpointEnabled={updateEndpointEnabled}
              onDeleteEndpointModels={deleteEndpointModels}
              onRefreshEndpointModels={refreshEndpointModels}
              onUpdateSingleModelEnabled={updateSingleModelEnabled}
            />
          )}
        </div>
      ) : (
        <ManualWorkflowEditor
          definitionText={definitionText}
          onDefinitionTextChange={setDefinitionText}
          definitionStatus={definitionStatus}
          definitionStats={definitionStats}
          error={error}
          validationIssues={validationIssues}
          saving={saving}
          canSave={canSave}
          onSave={handleSave}
          name={name}
          onNameChange={setName}
          priority={priority}
          onPriorityChange={setPriority}
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
        />
      )}
    </div>
  );
}
