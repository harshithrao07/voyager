import { useEffect, useMemo, useRef, useState } from 'react';
import Editor from '@monaco-editor/react';
import ReactMarkdown from 'react-markdown';
import { AlertCircle, Bot, Braces, Check, ChevronDown, Copy, Globe2, KeyRound, Link, Loader2, Monitor, MoreHorizontal, Play, Plus, Power, RefreshCw, Save, Search, Sparkles, Trash2, X } from 'lucide-react';
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

const starterDefinition = {
  StartAt: 'Done',
  States: {
    Done: {
      Type: 'Succeed',
    },
  },
};

type DefinitionMode = 'manual' | 'ai';
type AiModel = {
  id: string;
  label: string;
  endpoint: string;
  modelName?: string;
  provider: 'local' | 'api';
  enabled?: boolean;
  defaultModel?: boolean;
};

type ChatMessage = {
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

type ChatSnapshot = {
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

function formatDuration(value?: number | null) {
  if (!value || value < 0) return null;
  if (value < 1000) return `${value}ms`;
  return `${(value / 1000).toFixed(value < 10000 ? 1 : 0)}s`;
}

function formatTokenCount(value?: number | null) {
  if (!value || value < 0) return null;
  if (value >= 1000) return `${(value / 1000).toFixed(value < 10000 ? 1 : 0)}k tokens`;
  return `${value} tokens`;
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

const chatMarkdownComponents = {
  p: ({ children }: any) => <p className="mb-3 last:mb-0">{children}</p>,
  ul: ({ children }: any) => <ul className="my-3 list-disc space-y-1 pl-5">{children}</ul>,
  ol: ({ children }: any) => <ol className="my-3 list-decimal space-y-1 pl-5">{children}</ol>,
  li: ({ children }: any) => <li className="pl-1">{children}</li>,
  h1: ({ children }: any) => <h3 className="mb-2 mt-4 font-mono-sm text-[15px] font-semibold leading-6 text-primary first:mt-0">{children}</h3>,
  h2: ({ children }: any) => <h3 className="mb-2 mt-4 font-mono-sm text-[14px] font-semibold leading-6 text-primary first:mt-0">{children}</h3>,
  h3: ({ children }: any) => <h3 className="mb-2 mt-3 font-mono-sm text-[13px] font-semibold leading-6 text-primary first:mt-0">{children}</h3>,
  strong: ({ children }: any) => <strong className="font-semibold text-primary">{children}</strong>,
  em: ({ children }: any) => <em className="text-secondary">{children}</em>,
  a: ({ href, children }: any) => (
    <a href={href} className="text-primary underline underline-offset-2 hover:text-primary-fixed" target="_blank" rel="noreferrer">
      {children}
    </a>
  ),
  blockquote: ({ children }: any) => (
    <blockquote className="my-3 border-l border-primary/30 pl-3 text-on-surface-variant">
      {children}
    </blockquote>
  ),
  pre: ({ children }: any) => (
    <pre className="my-3 overflow-x-auto rounded-DEFAULT border border-border-subtle bg-surface-lowest p-3 text-[12px] leading-5">
      {children}
    </pre>
  ),
  code: ({ className, children, ...props }: any) => {
    const isBlock = Boolean(className);
    return (
      <code
        className={isBlock
          ? `${className || ''} bg-transparent p-0 font-mono-sm text-primary`
          : 'rounded-DEFAULT border border-border-subtle bg-surface-container px-1 py-0.5 font-mono-sm text-[12px] text-primary'}
        {...props}
      >
        {children}
      </code>
    );
  },
};

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
  const [settingsTab, setSettingsTab] = useState<'add' | 'added' | 'defaults' | 'search'>('add');
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

  const canGenerate = instruction.trim().length > 0 && !generating && !saving && !accepting && (models.length === 0 || !!modelId);
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

  const definitionStats = useMemo(() => {
    try {
      const definition = parseDefinition(definitionText);
      const states = definition.States && typeof definition.States === 'object' ? Object.keys(definition.States) : [];
      return {
        startAt: typeof definition.StartAt === 'string' ? definition.StartAt : '-',
        stateCount: states.length,
        terminalCount: states.filter((stateName) => {
          const state = definition.States[stateName];
          return state?.End || state?.Type === 'Succeed' || state?.Type === 'Fail';
        }).length,
      };
    } catch {
      return { startAt: '-', stateCount: 0, terminalCount: 0 };
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
    if (!canGenerate) return;

    const currentInstruction = instruction;
    const sentAt = Date.now();
    const startingNewConversation = !conversationId;
    const provisionalConversationId = startingNewConversation ? newConversationRouteId() : null;
    const selectedModelForSend = models.find((model) => model.id === modelId) || null;
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
  const chatMessageBodyClass = 'font-mono-sm text-[13px] leading-6 text-on-surface';
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
  const settingsNavItems: Array<{
    id: typeof settingsTab;
    label: string;
    icon: typeof Plus;
  }> = [
    { id: 'add', label: 'Add Models', icon: Plus },
    { id: 'added', label: 'Added Models', icon: Check },
    { id: 'defaults', label: 'AI Defaults', icon: Monitor },
    { id: 'search', label: 'Search', icon: Search },
  ];
  const modeSwitch = (
    <div className="mode-switch grid w-[280px] grid-cols-2 rounded-lg p-1" data-mode={mode}>
      <button
        type="button"
        onClick={() => setMode('ai')}
        className={`relative z-10 flex h-9 items-center justify-center gap-2 rounded-DEFAULT font-mono-sm text-label-mono transition-colors ${mode === 'ai' ? 'text-on-surface' : 'text-on-surface-variant hover:text-on-surface'}`}
      >
        <Sparkles size={15} />
        AI Generator
      </button>
      <button
        type="button"
        onClick={() => setMode('manual')}
        className={`relative z-10 flex h-9 items-center justify-center gap-2 rounded-DEFAULT font-mono-sm text-label-mono transition-colors ${mode === 'manual' ? 'text-primary' : 'text-on-surface-variant hover:text-on-surface'}`}
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

  const chatInputNode = (
                  <div className="relative rounded-lg border border-border-subtle bg-surface-container-low p-4 pb-16 transition-colors focus-within:border-secondary shadow-lg">
                    <textarea
                      ref={instructionTextareaRef}
                      value={instruction}
                      onChange={(event) => setInstruction(event.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' && !e.shiftKey) {
                          e.preventDefault();
                          handleGenerate();
                        }
                      }}
                      rows={1}
                      className="max-h-[220px] min-h-[56px] w-full resize-none overflow-hidden border-0 bg-transparent pb-8 font-mono-sm text-body-lg text-secondary shadow-none outline-none placeholder:text-secondary/45 focus:border-0 focus:outline-none focus:ring-0 focus-visible:outline-none"
                      placeholder="Message Voyager..."
                      disabled={generating}
                    />
  
                    <div ref={modelPickerRef} className="absolute bottom-4 left-4 w-fit">
                      <button
                        type="button"
                        onClick={() => {
                          if (!modelPickerOpen) {
                            const rect = modelPickerRef.current?.getBoundingClientRect();
                            setModelPickerPlacement(
                              rect && window.innerHeight - rect.bottom < 220 ? 'up' : 'down',
                            );
                          }
                          setModelPickerOpen((open) => !open);
                        }}
                        disabled={generating}
                        className="inline-flex h-9 max-w-[220px] items-center justify-start gap-1.5 rounded-DEFAULT pl-2 pr-1 text-left text-body-md text-on-surface transition-colors hover:bg-surface-container-high disabled:cursor-not-allowed disabled:opacity-60"
                      >
                        <span className="flex min-w-0 items-center gap-2">
                          <Bot size={14} className="shrink-0 text-primary" />
                          <span className={`truncate font-mono-sm text-label-mono ${selectedModel ? 'text-on-surface' : 'text-on-surface-variant'}`}>
                            {selectedModel?.label || 'Select model'}
                          </span>
                        </span>
                        <ChevronDown size={14} className="shrink-0 text-on-surface-variant transition-transform" />
                      </button>
  
                      {modelPickerOpen && (
                        <div className={`absolute left-0 z-50 w-[min(448px,calc(100vw-32px))] rounded-DEFAULT border border-border-subtle bg-surface-container-lowest p-2 shadow-[0_18px_60px_rgba(0,0,0,0.55)] ${
                          modelPickerPlacement === 'up' ? 'bottom-[48px]' : 'top-[48px]'
                        }`}>
                          <div className="flex gap-2">
                            <input
                              value={modelSearch}
                              onChange={(event) => setModelSearch(event.target.value)}
                              className="h-10 min-w-0 flex-1 rounded-DEFAULT border border-primary/25 bg-surface-container px-3 font-mono-sm text-label-mono text-primary outline-none placeholder:text-on-surface-variant/55 focus:border-primary/60"
                              placeholder="Search models..."
                            />
                            <button
                              type="button"
                              onClick={() => {
                                setModelPickerOpen(false);
                                setAddModelOpen(true);
                              }}
                              className="flex h-10 w-10 items-center justify-center rounded-DEFAULT border border-primary/25 bg-surface-container text-primary transition-colors hover:border-primary/60 hover:bg-surface-container-high"
                              title="Add model"
                            >
                              <Plus size={16} />
                            </button>
                          </div>
  
                          <div className="mt-2 max-h-[104px] space-y-1 overflow-y-auto pr-1">
                            {filteredModels.length === 0 ? (
                              <div className="px-2 py-5 text-center text-body-sm text-on-surface-variant">
                                No models added.
                              </div>
                            ) : filteredModels.map((model) => (
                              <button
                                key={model.id}
                                type="button"
                                onClick={() => {
                                  selectModel(model.id);
                                  setModelPickerOpen(false);
                                }}
                                className="grid h-8 w-full grid-cols-[minmax(0,1fr)_minmax(140px,1fr)_16px] items-center gap-3 rounded-DEFAULT px-2 text-left transition-colors hover:bg-surface-container"
                              >
                                <span className="flex min-w-0 items-center gap-2">
                                  <Bot size={14} className="shrink-0 text-primary" />
                                  <span className="truncate font-mono-sm text-[12px] font-semibold text-primary">{model.label}</span>
                                </span>
                                <span className="truncate font-mono-sm text-[11px] text-on-surface-variant">{model.endpoint}</span>
                                <span className={`h-2 w-2 rounded-full ${model.id === modelId ? 'bg-primary' : 'bg-border-muted'}`} />
                              </button>
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
  
                    <button
                      type="button"
                      onClick={handleGenerate}
                      disabled={!canGenerate}
                      className="absolute bottom-4 right-4 flex h-11 w-11 items-center justify-center rounded-DEFAULT border border-primary/25 bg-primary/35 text-on-surface transition-colors hover:bg-primary hover:text-on-primary disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {generating ? <Loader2 className="animate-spin" size={18} /> : <span className="material-symbols-outlined text-[20px]">arrow_upward</span>}
                    </button>
                  </div>
  );

  const latestAssistantModelLabel = [...messages]
    .reverse()
    .find((message) => message.role === 'assistant' && message.modelDisplayName)?.modelDisplayName;
  const chatSessionModelLabel = latestAssistantModelLabel || selectedModel?.label || 'AI model';
  const chatSessionTimeLabel = messages.length > 0 ? formatMessageTime(messages[messages.length - 1].createdAt) : '';

  return (
    <div className="voyager-main-bg flex h-full min-h-0 flex-col text-on-surface">
      {messages.length === 0 && (
        <header className="grid h-16 shrink-0 grid-cols-[1fr_auto] items-center px-8">
          <div>
            <div className="font-mono-sm text-label-mono uppercase text-on-surface-variant">Create workflow</div>
          </div>
          <div className="justify-self-end">
            {modeSwitch}
          </div>
        </header>
      )}
      {mode === 'ai' ? (
        <div className="flex flex-1 min-h-0 bg-transparent overflow-hidden">
          <div className="flex flex-1 flex-col overflow-hidden relative">
            {messages.length === 0 ? (
              <div className="flex flex-1 flex-col items-center justify-center p-8 pb-[10vh]">
                <div className="mb-12 flex flex-col items-center text-center">
                  <div className="inline-flex items-center justify-center gap-1.5">
                    <img src="/voyager-logo.svg" alt="" className="h-24 w-24 shrink-0 md:h-28 md:w-28" />
                    <div className="font-mono-sm text-[46px] font-semibold leading-none tracking-normal text-primary md:text-[58px]">Voyager</div>
                  </div>
                  <p className="mt-2 w-full max-w-[430px] font-mono-sm text-label-mono uppercase text-secondary/70">Smooth sailing for complex workflows</p>
                </div>
                <div className="w-full max-w-[900px] pointer-events-auto">
                  {chatInputNode}
                </div>
              </div>
            ) : (
              <>
                <div className="pointer-events-none absolute left-0 right-0 top-3 z-10 flex justify-center">
                  <div className="flex items-center gap-2 font-mono-sm text-[12px] text-on-surface-variant">
                    <span className="text-secondary">{chatSessionModelLabel}</span>
                    {chatSessionTimeLabel && <span>{chatSessionTimeLabel}</span>}
                    <span>&middot;</span>
                    <span>{messages.length} msgs</span>
                    <ChevronDown size={13} />
                  </div>
                </div>
                <div ref={chatScrollRef} className="flex-1 overflow-y-auto p-8 pb-[320px] scroll-smooth">
                  <div className={`mx-auto w-full max-w-[900px] space-y-6 transition-all duration-500 ease-out ${chatEntered ? 'translate-y-0 opacity-100' : 'translate-y-6 opacity-0'}`}>
                    {messages.map((msg) => {
                      const isEditing = editingMessageId === msg.id;
                      const durationLabel = formatDuration(msg.durationMs);
                      const tokenLabel = formatTokenCount(msg.totalTokens);
                      const assistantModelLabel = msg.modelDisplayName || selectedModel?.label || 'AI model';
                      const thinkingExpanded = expandedThinkingMessageIds.has(msg.id);

                      return (
                      <div key={msg.id} className={`group flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                        {msg.role === 'user' ? (
                          <div className="w-fit max-w-[min(420px,78%)] rounded-[24px] border border-secondary/25 bg-surface-container-low p-4 text-body-md text-on-surface shadow-[0_12px_32px_rgba(0,0,0,0.18)]">
                            <div className="mb-3 flex items-center justify-end gap-2 font-mono-sm text-[12px] text-on-surface-variant">
                              <span className="h-2 w-2 rounded-full bg-secondary/55" />
                              <span className="font-semibold text-secondary">You</span>
                              <span>{formatMessageTime(msg.createdAt)}</span>
                            </div>

                            {isEditing ? (
                              <div className="space-y-2">
                                <textarea
                                  value={editingMessageContent}
                                  onChange={(event) => setEditingMessageContent(event.target.value)}
                                  className="min-h-[96px] w-full resize-y rounded-DEFAULT border border-secondary/40 bg-surface-base p-3 font-mono-sm text-body-md text-secondary outline-none focus:border-secondary"
                                  autoFocus
                                />
                                <div className="flex justify-end gap-2">
                                  <button
                                    type="button"
                                    onClick={cancelEditingMessage}
                                    className="h-8 rounded-DEFAULT px-3 font-body-sm text-body-sm text-on-surface-variant transition-colors hover:bg-surface-container hover:text-on-surface"
                                  >
                                    Cancel
                                  </button>
                                  <button
                                    type="button"
                                    onClick={saveEditedMessage}
                                    className="h-8 rounded-DEFAULT bg-primary px-3 font-body-sm text-body-sm font-medium text-on-primary transition-colors hover:bg-primary-fixed-dim"
                                  >
                                    Save
                                  </button>
                                </div>
                              </div>
                            ) : (
                              <>
                                <div className={`whitespace-pre-wrap ${chatMessageBodyClass}`}>{msg.content}</div>
                                <div className="mt-4 flex items-center justify-end gap-2 text-on-surface-variant">
                                  <button
                                    type="button"
                                    onClick={() => startEditingMessage(msg)}
                                    className="flex h-8 w-8 items-center justify-center rounded-DEFAULT transition-colors hover:bg-surface-container hover:text-secondary"
                                    title="Edit message"
                                    aria-label="Edit message"
                                  >
                                    <span className="material-symbols-outlined text-[17px]">edit</span>
                                  </button>
                                  <button
                                    type="button"
                                    onClick={() => copyMessage(msg)}
                                    className="flex h-8 w-8 items-center justify-center rounded-DEFAULT transition-colors hover:bg-surface-container hover:text-secondary"
                                    title="Copy message"
                                    aria-label="Copy message"
                                  >
                                    {copiedMessageId === msg.id ? <Check size={15} /> : <Copy size={15} />}
                                  </button>
                                  <button
                                    type="button"
                                    className="flex h-8 w-8 items-center justify-center rounded-DEFAULT transition-colors hover:bg-surface-container hover:text-secondary"
                                    title="More actions"
                                    aria-label="More actions"
                                  >
                                    <MoreHorizontal size={16} />
                                  </button>
                                </div>
                              </>
                            )}
                          </div>
                        ) : (
                          <div className="w-full max-w-[840px] rounded-[22px] border border-secondary/25 bg-surface-lowest p-4 text-body-md text-on-surface shadow-[0_18px_50px_rgba(0,0,0,0.28)]">
                            <div className="mb-5 flex items-center gap-2 font-mono-sm">
                              <Bot size={15} className="text-primary" />
                              <span className="text-[18px] font-semibold text-primary">{assistantModelLabel}</span>
                              <span className="text-[12px] text-on-surface-variant">{formatMessageTime(msg.createdAt)}</span>
                            </div>

                            {isEditing ? (
                              <div className="space-y-2">
                                <textarea
                                  value={editingMessageContent}
                                  onChange={(event) => setEditingMessageContent(event.target.value)}
                                  className="min-h-[120px] w-full resize-y rounded-DEFAULT border border-secondary/40 bg-surface-base p-3 font-mono-sm text-body-md text-secondary outline-none focus:border-secondary"
                                  autoFocus
                                />
                                <div className="flex justify-end gap-2">
                                  <button
                                    type="button"
                                    onClick={cancelEditingMessage}
                                    className="h-8 rounded-DEFAULT px-3 font-body-sm text-body-sm text-on-surface-variant transition-colors hover:bg-surface-container hover:text-on-surface"
                                  >
                                    Cancel
                                  </button>
                                  <button
                                    type="button"
                                    onClick={saveEditedMessage}
                                    className="h-8 rounded-DEFAULT bg-primary px-3 font-body-sm text-body-sm font-medium text-on-primary transition-colors hover:bg-primary-fixed-dim"
                                  >
                                    Save
                                  </button>
                                </div>
                              </div>
                            ) : msg.streamingStatus === 'processing' ? (
                              <div className="flex min-h-[42px] items-center gap-3 font-mono-sm text-[13px] text-primary">
                                <span>Processing request</span>
                                <span className="voyager-processing-bars" aria-hidden="true">
                                  <span />
                                  <span />
                                  <span />
                                </span>
                              </div>
                            ) : (
                              <>
                                {(msg.thinkingContent || msg.streamingPhase === 'thinking') && (
                                  <div className="mb-5 overflow-hidden rounded-DEFAULT border border-primary/25 bg-primary/10 font-mono-sm">
                                    <button
                                      type="button"
                                      onClick={() => toggleThinking(msg.id)}
                                      className="flex w-full items-center justify-between gap-3 border-b border-primary/20 px-4 py-3 text-left text-primary transition-colors hover:bg-primary/10"
                                    >
                                      <span>{thinkingExpanded ? 'Hide' : 'Show'} thinking process</span>
                                      <span className="flex items-center gap-2 text-[12px] text-on-surface-variant">
                                        {durationLabel && <span>{durationLabel}</span>}
                                        {durationLabel && tokenLabel && <span>&middot;</span>}
                                        {tokenLabel && <span>{tokenLabel}</span>}
                                        <ChevronDown size={14} className={`transition-transform ${thinkingExpanded ? 'rotate-180' : ''}`} />
                                      </span>
                                    </button>
                                    {thinkingExpanded && (
                                      <div className="max-h-[220px] overflow-y-auto px-4 py-4 text-[13px] leading-6 text-secondary">
                                        <div className="whitespace-pre-wrap">
                                          {msg.thinkingContent}
                                          {msg.streamingStatus === 'streaming' && msg.streamingPhase === 'thinking' && (
                                            <span className="voyager-stream-cursor" aria-hidden="true" />
                                          )}
                                        </div>
                                      </div>
                                    )}
                                  </div>
                                )}
                                <div className={chatMessageBodyClass}>
                                  <ReactMarkdown components={chatMarkdownComponents}>{msg.content}</ReactMarkdown>
                                  {msg.streamingStatus === 'streaming' && msg.streamingPhase === 'answer' && (
                                    <span className="voyager-stream-cursor" aria-hidden="true" />
                                  )}
                                </div>
                                {msg.streamingStatus !== 'streaming' && (
                                  <div className="mt-6 flex items-center gap-3 font-mono-sm text-[12px] text-on-surface-variant">
                                  {tokenLabel && <span>{tokenLabel}</span>}
                                  {durationLabel && <span>&middot; {durationLabel}</span>}
                                  {(tokenLabel || durationLabel) && <span className="h-4 w-px bg-border-subtle" />}
                                  <button
                                    type="button"
                                    onClick={() => handleRegenerateMessage(msg)}
                                    disabled={Boolean(regeneratingMessageId)}
                                    className="flex h-8 w-8 items-center justify-center rounded-DEFAULT transition-colors hover:bg-surface-container hover:text-secondary disabled:cursor-not-allowed disabled:opacity-40"
                                    title="Regenerate message"
                                    aria-label="Regenerate message"
                                  >
                                    {regeneratingMessageId === msg.id
                                      ? <Loader2 className="animate-spin" size={14} />
                                      : <RefreshCw size={14} />}
                                  </button>
                                  <button
                                    type="button"
                                    onClick={() => copyMessage(msg)}
                                    className="flex h-8 w-8 items-center justify-center rounded-DEFAULT transition-colors hover:bg-surface-container hover:text-secondary"
                                    title="Copy message"
                                    aria-label="Copy message"
                                  >
                                    {copiedMessageId === msg.id ? <Check size={14} /> : <Copy size={14} />}
                                  </button>
                                  <button
                                    type="button"
                                    onClick={() => startEditingMessage(msg)}
                                    className="flex h-8 w-8 items-center justify-center rounded-DEFAULT transition-colors hover:bg-surface-container hover:text-secondary"
                                    title="Edit message"
                                    aria-label="Edit message"
                                  >
                                    <span className="material-symbols-outlined text-[16px]">edit</span>
                                  </button>
                                  <button
                                    type="button"
                                    className="flex h-8 w-8 items-center justify-center rounded-DEFAULT transition-colors hover:bg-surface-container hover:text-secondary"
                                    title="More actions"
                                    aria-label="More actions"
                                  >
                                    <MoreHorizontal size={16} />
                                  </button>
                                  </div>
                                )}
                              </>
                            )}
                          </div>
                        )}
                      </div>
                    )})}
                    {error && (
                      <div className="mt-4 rounded-DEFAULT border border-status-error/25 bg-status-error/10 p-3 text-body-sm text-status-error">
                        <div className="flex items-start gap-2">
                          <AlertCircle className="mt-0.5 shrink-0" size={16} />
                          <div>{error}</div>
                        </div>
                      </div>
                    )}
                    <div ref={chatEndRef} className="h-[220px]" aria-hidden="true" />
                  </div>
                </div>
                <div className="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-surface-base via-surface-base/90 to-transparent pt-12 pb-6 px-8 pointer-events-none">
                  <div className={`mx-auto w-full max-w-[900px] pointer-events-auto transition-all duration-500 ease-out ${chatEntered ? 'translate-y-0 opacity-100' : 'translate-y-10 opacity-0'}`}>
                    {chatInputNode}
                  </div>
                </div>
              </>
            )}
          </div>

          {isEditorOpen && (
            <div className="flex w-[400px] xl:w-[500px] shrink-0 flex-col border-l border-border-subtle bg-surface-base relative">
              <div className="flex h-12 items-center justify-between border-b border-border-subtle px-4">
                <div className="flex items-center gap-2 font-mono-sm text-[12px] text-on-surface-variant">
                  <Braces size={14} />
                  {conversationStage === 'ASL_UNDER_REVIEW' ? 'ASL review' : 'Generated ASL'}
                </div>
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={handleReviewAsl}
                    disabled={!conversationId || generating || !definitionStatus.valid}
                    className="flex h-8 items-center gap-1.5 rounded-DEFAULT border border-secondary/30 px-2.5 font-body-sm text-body-sm text-secondary transition-colors hover:bg-secondary-container/25 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {generating ? <Loader2 className="animate-spin" size={14} /> : <Braces size={14} />}
                    Check ASL
                  </button>
                  {conversationStage === 'PLAN_READY' && (
                    <button
                      type="button"
                      onClick={handleAcceptPlan}
                      disabled={accepting}
                      className="flex h-8 items-center gap-1.5 rounded-DEFAULT bg-primary px-2.5 font-body-sm text-body-sm font-medium text-on-primary transition-colors hover:bg-primary-fixed-dim disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {accepting ? <Loader2 className="animate-spin" size={14} /> : <Save size={14} />}
                      Accept
                    </button>
                  )}
                  <button
                    onClick={() => setIsEditorOpen(false)}
                    className="text-on-surface-variant hover:text-on-surface p-1 rounded-DEFAULT hover:bg-surface-container transition-colors"
                  >
                    <X size={16} />
                  </button>
                </div>
              </div>
              <div className="flex-1 overflow-hidden relative">
                <Editor
                  height="100%"
                  defaultLanguage="json"
                  theme="vs-dark"
                  value={definitionText}
                  onChange={(value) => setDefinitionText(value || '')}
                  options={{
                    minimap: { enabled: false },
                    scrollBeyondLastLine: false,
                    fontSize: 13,
                    fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
                    wordWrap: 'on',
                    tabSize: 2,
                    lineNumbersMinChars: 3,
                    padding: { top: 16, bottom: 16 },
                  }}
                />
              </div>
            </div>
          )}

          {addModelOpen && (
            <div className="fixed inset-0 z-[70] flex items-center justify-center bg-black/55 p-6 pointer-events-auto">
              <div className="flex max-h-[86vh] w-full max-w-4xl flex-col overflow-hidden rounded-lg border border-primary/20 bg-surface-lowest shadow-[0_24px_90px_rgba(0,0,0,0.65)]">
                <div className="flex h-16 shrink-0 items-center justify-between px-6 shadow-[inset_0_-1px_rgba(255,255,255,0.08)]">
                  <div className="flex items-center gap-2 font-display text-[20px] font-semibold text-primary">
                    <Sparkles size={18} />
                    Settings
                  </div>
                  <button
                    type="button"
                    onClick={() => setAddModelOpen(false)}
                    className="flex h-8 w-8 items-center justify-center rounded-DEFAULT border border-primary/30 text-primary transition-colors hover:bg-surface-container"
                    aria-label="Close add model"
                  >
                    <X size={16} />
                  </button>
                </div>

                <div className="grid min-h-0 flex-1 grid-cols-[200px_1fr] overflow-hidden">
                  <aside className="bg-surface-container-lowest p-3 shadow-[inset_-1px_0_rgba(255,255,255,0.08)]">
                    <div className="space-y-1 text-body-sm">
                      {settingsNavItems.map((item) => {
                        const Icon = item.icon;
                        const selected = settingsTab === item.id;
                        return (
                          <button
                            key={item.id}
                            type="button"
                            onClick={() => setSettingsTab(item.id)}
                            className={`flex h-10 w-full items-center gap-3 rounded-DEFAULT px-3 text-left font-medium transition-colors ${
                              selected
                                ? 'bg-primary/15 text-primary'
                                : 'text-on-surface-variant hover:bg-surface-container hover:text-primary'
                            }`}
                          >
                            <Icon size={16} />
                            {item.label}
                          </button>
                        );
                      })}
                    </div>
                  </aside>

                  <main className="space-y-3 overflow-y-auto p-6">
                    {settingsTab === 'add' && (
                      <>
                    <section className="relative rounded-lg border border-primary/20 bg-surface-base p-4">
                      <div className="flex items-start justify-between gap-4 border-b border-border-subtle/40 pb-3">
                        <div className="flex items-center gap-3">
                          <Monitor size={18} className="text-primary" />
                          <div>
                            <div className="flex items-baseline gap-2">
                              <h3 className="font-headline-md text-headline-md font-semibold text-primary">Add Local Models</h3>
                              <span className="font-mono-sm text-[12px] text-on-surface-variant">(Endpoint)</span>
                            </div>
                            <p className="mt-1 text-body-sm text-on-surface-variant">Add a local model server (Ollama, llama.cpp, vLLM).</p>
                          </div>
                        </div>
                        <div ref={localActionsRef} className="relative flex shrink-0 gap-2">
                          <button
                            type="button"
                            onClick={testLocalEndpoint}
                            disabled={testingModel || discoveringModels || !discoverEndpoint.trim()}
                            className="flex h-10 items-center gap-2 rounded-DEFAULT border border-primary/30 px-3 text-body-sm text-primary transition-colors hover:bg-surface-container disabled:cursor-not-allowed disabled:opacity-50"
                          >
                            {testingModel ? <Loader2 className="animate-spin" size={14} /> : <Play size={14} />}
                            Test
                          </button>
                          <button
                            type="button"
                            onClick={() => setLocalActionsOpen((open) => !open)}
                            className="flex h-10 w-10 items-center justify-center rounded-DEFAULT border border-primary/30 text-primary transition-colors hover:bg-surface-container"
                            aria-label="Local model actions"
                          >
                            <MoreHorizontal size={16} />
                          </button>

                          {localActionsOpen && (
                            <div className="absolute right-0 top-[48px] z-20 w-[212px] rounded-lg border border-primary/30 bg-surface-lowest p-2 shadow-[0_18px_50px_rgba(0,0,0,0.45)]">
                              <button
                                type="button"
                                onClick={scanForServers}
                                disabled={discoveringModels}
                                className="flex h-10 w-full items-center gap-3 rounded-DEFAULT px-3 text-left text-body-sm font-medium text-primary transition-colors hover:bg-surface-container disabled:cursor-not-allowed disabled:opacity-50"
                              >
                                {discoveringModels ? <Loader2 className="animate-spin" size={15} /> : <Search size={15} />}
                                Scan for Servers
                              </button>
                              <button
                                type="button"
                                onClick={() => {
                                  setLocalActionsOpen(false);
                                  setDiscoverEndpoint('http://host.docker.internal:11434/v1');
                                  discoverModels('http://host.docker.internal:11434/v1');
                                }}
                                disabled={addingModel}
                                className="flex h-10 w-full items-center gap-3 rounded-DEFAULT px-3 text-left text-body-sm font-medium text-primary transition-colors hover:bg-surface-container"
                              >
                                {addingModel ? <Loader2 className="animate-spin" size={15} /> : <Bot size={15} />}
                                Add Ollama
                              </button>
                              <button
                                type="button"
                                onClick={() => {
                                  setShowLocalApiKey((visible) => !visible);
                                  setLocalActionsOpen(false);
                                }}
                                className="flex h-10 w-full items-center gap-3 rounded-DEFAULT px-3 text-left text-body-sm font-medium text-primary transition-colors hover:bg-surface-container"
                              >
                                <KeyRound size={15} />
                                API key
                              </button>
                            </div>
                          )}
                        </div>
                      </div>

                      <div className="mt-3 grid grid-cols-[84px_minmax(0,1fr)_72px] gap-2">
                        <div className="flex h-10 items-center rounded-DEFAULT border border-primary/30 bg-surface-container px-3 text-body-sm font-medium text-primary">
                          LLM
                        </div>
                        <input
                          id="discover-endpoint-input"
                          value={discoverEndpoint}
                          onChange={(event) => setDiscoverEndpoint(event.target.value)}
                          onKeyDown={(event) => { if (event.key === 'Enter') discoverModels(); }}
                          className="h-10 rounded-DEFAULT border border-primary/30 bg-surface-container px-3 font-mono-sm text-body-sm text-on-surface outline-none placeholder:text-on-surface-variant/55 focus:border-primary/60"
                          placeholder="Paste endpoint URL, e.g. http://host.docker.internal:11434/v1"
                          disabled={addingModel || discoveringModels}
                        />
                        <button
                          id="discover-models-btn"
                          type="button"
                          onClick={() => discoverModels()}
                          disabled={addingModel || discoveringModels || !discoverEndpoint.trim()}
                          className="flex h-10 items-center justify-center gap-1.5 rounded-DEFAULT bg-primary px-3 text-body-sm font-medium text-surface-lowest transition-colors hover:bg-primary-fixed disabled:cursor-not-allowed disabled:opacity-50"
                        >
                          {addingModel && <Loader2 className="animate-spin" size={14} />}
                          Add
                        </button>
                      </div>
                      {localEndpointNeedsDockerHint && (
                        <p className="mt-2 text-[12px] leading-5 text-status-warning">
                          If the backend is running in Docker, localhost may point inside the backend container. Try host.docker.internal if this fails.
                        </p>
                      )}

                      {(showLocalApiKey || localApiKey) && (
                        <input
                          value={localApiKey}
                          onChange={(event) => setLocalApiKey(event.target.value)}
                          className="mt-2 h-9 w-full rounded-DEFAULT border border-primary/30 bg-surface-container px-3 font-mono-sm text-body-sm text-on-surface outline-none placeholder:text-on-surface-variant/55 focus:border-primary/60"
                          placeholder="API key (optional - for protected local endpoints)"
                          type="password"
                        />
                      )}

                      {modelActionMessage && (
                        <div className={`mt-3 text-body-sm ${
                          modelActionSuccess ? 'text-status-success' : 'text-status-error'
                        }`}>
                          {modelActionMessage}
                          {modelActionSuccess && discoveredModelNames.length > 0 && (
                            <span className="ml-2 font-mono-sm text-[11px] opacity-75">
                              {discoveredModelNames.join(', ')}
                            </span>
                          )}
                        </div>
                      )}
                    </section>

                    <section className="rounded-lg border border-primary/20 bg-surface-base p-4">
                      <div className="flex items-center justify-between gap-4">
                        <div className="flex items-center gap-3">
                          <Globe2 size={18} className="text-primary" />
                          <div>
                            <h3 className="font-headline-md text-headline-md font-semibold text-primary">Add API Models</h3>
                            <p className="mt-1 text-body-sm text-on-surface-variant">Connect a cloud provider endpoint.</p>
                          </div>
                        </div>
                        <div className="flex gap-2">
                          <button type="button" className="flex h-9 items-center gap-2 rounded-DEFAULT border border-primary/30 px-3 text-body-sm text-primary">
                            <Play size={14} />
                            Test
                          </button>
                          <button type="button" className="flex h-9 w-9 items-center justify-center rounded-DEFAULT border border-primary/30 text-primary">
                            <MoreHorizontal size={16} />
                          </button>
                        </div>
                      </div>

                      <div className="mt-4 grid grid-cols-[160px_1fr_72px] gap-2">
                        <select
                          value={apiProvider}
                          onChange={(event) => setApiProvider(event.target.value)}
                          className={fieldClass}
                        >
                          <option>DeepSeek</option>
                          <option>OpenAI</option>
                          <option>Anthropic</option>
                          <option>OpenRouter</option>
                        </select>
                        <input
                          value={apiEndpoint}
                          onChange={(event) => setApiEndpoint(event.target.value)}
                          className={fieldClass}
                          placeholder="https://api.deepseek.com/v1"
                        />
                        <button
                          type="button"
                          onClick={addApiModel}
                          className="mt-1 h-9 rounded-DEFAULT bg-primary px-3 text-body-sm font-medium text-surface-lowest transition-colors hover:bg-primary-fixed"
                        >
                          Add
                        </button>
                      </div>
                      <div className="mt-2 grid grid-cols-1 gap-2">
                        <input
                          value={apiModelName}
                          onChange={(event) => setApiModelName(event.target.value)}
                          className={fieldClass}
                          placeholder="Model name, e.g. deepseek-chat"
                        />
                      </div>
                    </section>
                      </>
                    )}

                    {settingsTab === 'added' && (
                      <section className="rounded-lg border border-primary/20 bg-surface-base p-4">
                        <div className="flex items-start justify-between gap-4 border-b border-border-subtle/40 pb-3">
                          <div className="flex items-center gap-3">
                            <Check size={18} className="text-primary" />
                            <div>
                              <div className="flex items-baseline gap-2">
                                <h3 className="font-headline-md text-headline-md font-semibold text-primary">Added Models</h3>
                                <span className="font-mono-sm text-[12px] text-on-surface-variant">(Endpoints)</span>
                              </div>
                              <p className="mt-1 text-body-sm text-on-surface-variant">Endpoints you've connected. Refresh re-checks a server; disabled models stay out of chat.</p>
                            </div>
                          </div>
                          <button
                            type="button"
                            onClick={probeAllEndpoints}
                            disabled={managingModels}
                            className="flex h-10 items-center gap-2 rounded-DEFAULT border border-primary/30 px-3 text-body-sm text-primary transition-colors hover:bg-surface-container disabled:cursor-not-allowed disabled:opacity-50"
                          >
                            {managingModels ? <Loader2 className="animate-spin" size={14} /> : <RefreshCw size={14} />}
                            Probe
                          </button>
                        </div>

                        <div className="mt-4 space-y-3">
                          {endpointGroups.length === 0 ? (
                            <div className="rounded-DEFAULT border border-border-subtle/60 bg-surface-container-lowest p-5 text-body-sm text-on-surface-variant">
                              No model endpoints added yet.
                            </div>
                          ) : endpointGroups.map((group) => {
                            const expanded = expandedEndpoint === group.endpoint;
                            return (
                              <div key={group.endpoint} className="rounded-lg border border-primary/20 bg-surface-container-lowest p-4">
                                <div className="flex items-start justify-between gap-4">
                                  <div
                                    role="button"
                                    tabIndex={0}
                                    onClick={() => setExpandedEndpoint(expanded ? null : group.endpoint)}
                                    onKeyDown={(event) => {
                                      if (event.key === 'Enter' || event.key === ' ') {
                                        event.preventDefault();
                                        setExpandedEndpoint(expanded ? null : group.endpoint);
                                      }
                                    }}
                                    className="min-w-0 flex-1 text-left"
                                  >
                                    <div className="flex min-w-0 flex-wrap items-center gap-2">
                                      <Bot size={16} className="shrink-0 text-primary" />
                                      <span className="truncate font-mono-sm text-[15px] font-semibold text-primary">{group.host}</span>
                                      <span className="rounded-DEFAULT bg-primary/15 px-2 py-0.5 font-mono-sm text-[10px] font-semibold uppercase text-primary">Local</span>
                                      <span className="rounded-DEFAULT bg-status-error/15 px-2 py-0.5 font-mono-sm text-[10px] font-semibold text-status-error">
                                        {group.enabledCount}/{group.models.length} models enabled
                                      </span>
                                    </div>
                                    <div className="mt-2 flex min-w-0 items-center gap-2 font-mono-sm text-[11px] text-on-surface-variant">
                                      <Link size={13} className="shrink-0" />
                                      <span className="truncate">{group.endpoint}</span>
                                      <button
                                        type="button"
                                        onClick={(event) => {
                                          event.stopPropagation();
                                          copyEndpoint(group.endpoint);
                                        }}
                                        className="flex h-5 w-5 shrink-0 items-center justify-center rounded-DEFAULT text-primary transition-colors hover:bg-surface-container"
                                        aria-label="Copy endpoint URL"
                                        title="Copy endpoint URL"
                                      >
                                        <Copy size={12} />
                                      </button>
                                    </div>
                                  </div>

                                  <div className="flex shrink-0 items-center gap-2">
                                    <button
                                      type="button"
                                      onClick={() => updateEndpointEnabled(group.endpoint, group.enabledCount === 0)}
                                      disabled={managingModels}
                                      className="flex h-9 items-center gap-2 rounded-DEFAULT border border-primary/30 px-3 text-body-sm text-primary transition-colors hover:bg-surface-container disabled:cursor-not-allowed disabled:opacity-50"
                                    >
                                      <Power size={14} />
                                      {group.enabledCount > 0 ? 'Disable' : 'Enable'}
                                    </button>
                                    <button
                                      type="button"
                                      onClick={() => deleteEndpointModels(group.endpoint)}
                                      disabled={managingModels}
                                      className="flex h-9 items-center gap-2 rounded-DEFAULT border border-status-error/30 px-3 text-body-sm text-status-error transition-colors hover:bg-status-error/10 disabled:cursor-not-allowed disabled:opacity-50"
                                    >
                                      <Trash2 size={14} />
                                      Delete
                                    </button>
                                    <button
                                      type="button"
                                      onClick={() => setExpandedEndpoint(expanded ? null : group.endpoint)}
                                      className="flex h-9 w-9 items-center justify-center rounded-DEFAULT text-primary transition-colors hover:bg-surface-container"
                                      aria-label={expanded ? 'Collapse endpoint' : 'Expand endpoint'}
                                    >
                                      <ChevronDown size={15} className={`transition-transform ${expanded ? 'rotate-180' : ''}`} />
                                    </button>
                                  </div>
                                </div>

                                {expanded && (
                                  <div className="mt-4 border-t border-border-subtle/50 pt-3">
                                    <div className="mb-2 flex items-center justify-between gap-2">
                                      <div className="font-mono-sm text-[11px] font-semibold uppercase tracking-normal text-on-surface-variant">Models</div>
                                      <div className="flex items-center gap-2 font-mono-sm text-[11px] text-on-surface-variant">
                                        <span>{group.enabledCount}/{group.models.length} enabled</span>
                                        <button type="button" onClick={() => refreshEndpointModels(group.endpoint)} disabled={managingModels} className="text-primary hover:text-primary-fixed disabled:opacity-50">Refresh</button>
                                        <button type="button" onClick={() => updateEndpointEnabled(group.endpoint, true)} disabled={managingModels} className="text-primary hover:text-primary-fixed disabled:opacity-50">All</button>
                                        <button type="button" onClick={() => updateEndpointEnabled(group.endpoint, false)} disabled={managingModels} className="text-primary hover:text-primary-fixed disabled:opacity-50">None</button>
                                      </div>
                                    </div>

                                    <div className="max-h-[128px] space-y-1 overflow-y-auto rounded-DEFAULT border border-primary/20 bg-surface-base p-2">
                                      {group.models.map((model) => {
                                        const enabled = model.enabled !== false;
                                        return (
                                          <button
                                            key={model.id}
                                            type="button"
                                            onClick={() => updateSingleModelEnabled(model, !enabled)}
                                            disabled={managingModels}
                                            className="flex h-8 w-full items-center gap-2 rounded-DEFAULT px-2 text-left transition-colors hover:bg-surface-container disabled:cursor-not-allowed disabled:opacity-60"
                                          >
                                            <span className={`flex h-4 w-4 shrink-0 items-center justify-center rounded-full border ${enabled ? 'border-primary bg-primary text-surface-lowest' : 'border-border-muted text-transparent'}`}>
                                              <Check size={11} />
                                            </span>
                                            <span className={`min-w-0 flex-1 truncate font-mono-sm text-[12px] ${enabled ? 'text-primary' : 'text-on-surface-variant'}`}>
                                              {model.label}
                                            </span>
                                          </button>
                                        );
                                      })}
                                    </div>
                                  </div>
                                )}
                              </div>
                            );
                          })}
                        </div>
                      </section>
                    )}

                    {settingsTab !== 'add' && settingsTab !== 'added' && (
                      <section className="rounded-lg border border-primary/20 bg-surface-base p-5 text-body-sm text-on-surface-variant">
                        This settings section is not wired yet.
                      </section>
                    )}
                  </main>
                </div>
              </div>
            </div>
          )}
        </div>
      ) : (
        <div className="flex flex-1 min-h-0 flex-col bg-transparent">
        <div className="grid flex-1 min-h-0 grid-cols-1 overflow-hidden xl:grid-cols-[minmax(0,1fr)_360px]">
          <aside className="hidden">
            <div className="px-4 py-3">
              <div className="text-label-caps font-label-caps text-on-surface-variant">Workflow settings</div>
            </div>
            <div className="flex-1 space-y-5 overflow-y-auto p-4">
              <section className="space-y-3">
                <label className="block">
                  <span className="text-body-sm text-on-surface-variant">Name</span>
                  <input
                    value={name}
                    onChange={(event) => setName(event.target.value)}
                    className={fieldClass}
                    placeholder="Invoice approval"
                  />
                </label>
                <div className="grid grid-cols-2 gap-3">
                  <label className="block">
                    <span className="text-body-sm text-on-surface-variant">Priority</span>
                    <select
                      value={priority}
                      onChange={(event) => setPriority(event.target.value as WorkflowPriorityDTO)}
                      className={fieldClass}
                    >
                      <option value="HIGH">High</option>
                      <option value="MEDIUM">Medium</option>
                      <option value="LOW">Low</option>
                    </select>
                  </label>
                  <label className="block">
                    <span className="text-body-sm text-on-surface-variant">Attempts</span>
                    <input
                      type="number"
                      min={0}
                      value={maxAttempts}
                      onChange={(event) => setMaxAttempts(Number(event.target.value))}
                      className={fieldClass}
                    />
                  </label>
                </div>
                <label className="block">
                  <span className="text-body-sm text-on-surface-variant">Idempotency key</span>
                  <input
                    value={idempotencyKey}
                    onChange={(event) => setIdempotencyKey(event.target.value)}
                    className={monoFieldClass}
                  />
                </label>
              </section>

              <section className="space-y-3 pt-2">
                <div className="text-label-caps font-label-caps text-on-surface-variant">Schedule</div>
                <label className="block">
                  <span className="text-body-sm text-on-surface-variant">Cron expression</span>
                  <input
                    value={cronExpression}
                    onChange={(event) => setCronExpression(event.target.value)}
                    className={monoFieldClass}
                    placeholder="Manual trigger"
                  />
                </label>
                <label className="block">
                  <span className="text-body-sm text-on-surface-variant">Timezone</span>
                  <input
                    value={timezone}
                    onChange={(event) => setTimezone(event.target.value)}
                    className={fieldClass}
                    placeholder="UTC"
                  />
                </label>
              </section>
            </div>
          </aside>

          <main className="flex min-h-0 flex-col bg-surface-base">
            <div className="flex h-14 shrink-0 items-center justify-between border-b border-border-subtle bg-surface-base px-6">
              <div className="flex items-center gap-2 font-mono-sm text-[13px] text-on-surface">
                <span className="material-symbols-outlined text-[18px]">description</span>
                definition.json
              </div>
              <div className={`font-mono-sm text-[12px] ${definitionStatus.valid ? 'text-secondary' : 'text-status-error'}`}>
                {definitionStatus.message}
              </div>
            </div>
            <div className="flex-1 min-h-0">
              <Editor
                height="100%"
                defaultLanguage="json"
                theme="vs-dark"
                value={definitionText}
                onChange={(value) => setDefinitionText(value || '')}
                options={{
                  minimap: { enabled: false },
                  scrollBeyondLastLine: false,
                  fontSize: 14,
                  fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
                  wordWrap: 'on',
                  tabSize: 2,
                  lineNumbersMinChars: 3,
                  padding: { top: 16, bottom: 16 },
                }}
              />
            </div>
          </main>

          <aside className="hidden min-h-0 flex-col overflow-y-auto border-l border-border-subtle bg-surface-base xl:flex">
            <div className="border-b border-border-subtle p-8">
              <button
                type="button"
                onClick={handleSave}
                disabled={!canSave}
                className="mb-8 flex h-12 w-full items-center justify-center gap-2 rounded-DEFAULT bg-primary px-5 font-body-sm text-[16px] font-medium text-on-primary shadow-[0_12px_30px_rgba(240,140,140,0.18)] transition-colors hover:bg-primary-fixed-dim disabled:cursor-not-allowed disabled:opacity-50"
              >
                {saving ? <Loader2 className="animate-spin" size={16} /> : <Save size={16} />}
                Save draft
              </button>
              <h2 className="font-headline-lg text-headline-lg text-on-surface">Definition health</h2>
              <div className="mt-6 grid grid-cols-2 gap-4">
                <div className="rounded-DEFAULT border border-border-subtle bg-surface-base p-4">
                  <div className="font-mono-sm text-[12px] text-on-surface-variant">States</div>
                  <div className="mt-3 font-display text-[28px] font-medium text-on-surface">{definitionStats.stateCount}</div>
                </div>
                <div className="rounded-DEFAULT border border-border-subtle bg-surface-base p-4">
                  <div className="font-mono-sm text-[12px] text-on-surface-variant">Ends</div>
                  <div className="mt-3 font-display text-[28px] font-medium text-on-surface">{definitionStats.terminalCount}</div>
                </div>
              </div>

              <div className="mt-5 rounded-DEFAULT border border-border-subtle bg-surface-base p-4">
                <div className="font-mono-sm text-[12px] text-on-surface-variant">Start state</div>
                <div className="mt-3 truncate font-headline-md text-headline-md text-on-surface">{definitionStats.startAt}</div>
              </div>

              {(error || validationIssues.length > 0 || !definitionStatus.valid) ? (
                <div className="mt-5 rounded-DEFAULT border border-status-error/25 bg-status-error/10 p-4 text-body-sm text-status-error">
                  <div className="flex items-start gap-2">
                    <AlertCircle className="mt-0.5 shrink-0" size={16} />
                    <div>
                      {error || definitionStatus.message}
                      {validationIssues.length > 0 && (
                        <ul className="mt-2 list-disc space-y-1 pl-4">
                          {validationIssues.map((issue) => (
                            <li key={issue}>{issue}</li>
                          ))}
                        </ul>
                      )}
                    </div>
                  </div>
                </div>
              ) : (
                <div className="mt-5 rounded-DEFAULT border border-secondary/35 bg-secondary-container/45 p-4 text-body-sm text-secondary-fixed">
                  Ready to save as a draft workflow.
                </div>
              )}
            </div>
            <div className="space-y-6 p-8">
              <h2 className="font-headline-lg text-headline-lg text-on-surface">Workflow settings</h2>
              <section className="space-y-4">
                <label className="block">
                  <span className="text-body-sm text-on-surface">Name</span>
                  <input value={name} onChange={(event) => setName(event.target.value)} className={fieldClass} placeholder="Invoice approval" />
                </label>
                <div className="grid grid-cols-2 gap-4">
                  <label className="block">
                    <span className="text-body-sm text-on-surface">Priority</span>
                    <select value={priority} onChange={(event) => setPriority(event.target.value as WorkflowPriorityDTO)} className={fieldClass}>
                      <option value="HIGH">High</option>
                      <option value="MEDIUM">Medium</option>
                      <option value="LOW">Low</option>
                    </select>
                  </label>
                  <label className="block">
                    <span className="text-body-sm text-on-surface">Attempts</span>
                    <input type="number" min={0} value={maxAttempts} onChange={(event) => setMaxAttempts(Number(event.target.value))} className={fieldClass} />
                  </label>
                </div>
                <label className="block">
                  <span className="text-body-sm text-on-surface">Idempotency key</span>
                  <input value={idempotencyKey} onChange={(event) => setIdempotencyKey(event.target.value)} className={monoFieldClass} />
                </label>
              </section>
              <div className="h-px bg-border-subtle" />
              <section className="space-y-4">
                <h3 className="font-headline-md text-headline-md text-on-surface">Schedule</h3>
                <label className="block">
                  <span className="text-body-sm text-on-surface">Cron expression</span>
                  <input value={cronExpression} onChange={(event) => setCronExpression(event.target.value)} className={monoFieldClass} placeholder="Manual trigger" />
                </label>
                <label className="block">
                  <span className="text-body-sm text-on-surface">Timezone</span>
                  <input value={timezone} onChange={(event) => setTimezone(event.target.value)} className={fieldClass} placeholder="UTC" />
                </label>
              </section>
            </div>
          </aside>
        </div>
        </div>
      )}
    </div>
  );
}
