import { useEffect, useMemo, useRef, useState } from 'react';
import Editor from '@monaco-editor/react';
import ReactMarkdown from 'react-markdown';
import { AlertCircle, Bot, Braces, ChevronDown, Globe2, Loader2, Monitor, MoreHorizontal, Play, Plus, Save, Sparkles, X } from 'lucide-react';
import {
  acceptWorkflowAiPlan,
  continueWorkflowAiConversation,
  createWorkflow,
  listAiModels,
  reviewWorkflowAiAsl,
  startWorkflowAiConversation,
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
};

type ChatMessage = {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  createdAt: number;
};

type Props = {
  onWorkflowCreated: (workflow: WorkflowResponseDTO) => void;
  onNavigate?: (path: string) => void;
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

function formatMessageTime(value: number) {
  return new Intl.DateTimeFormat(undefined, {
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

export function CreateWorkflowView({ onWorkflowCreated, onNavigate }: Props) {
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
  const [modelId, setModelId] = useState('');
  const [modelSearch, setModelSearch] = useState('');
  const [modelPickerOpen, setModelPickerOpen] = useState(false);
  const [addModelOpen, setAddModelOpen] = useState(false);
  const [localModelName, setLocalModelName] = useState('');
  const [localEndpoint, setLocalEndpoint] = useState('');
  const [apiProvider, setApiProvider] = useState('DeepSeek');
  const [apiModelName, setApiModelName] = useState('');
  const [apiEndpoint, setApiEndpoint] = useState('https://api.deepseek.com/v1');
  const [definitionText, setDefinitionText] = useState(formatJson(starterDefinition));
  const [validationIssues, setValidationIssues] = useState<string[]>([]);
  const [generating, setGenerating] = useState(false);
  const [saving, setSaving] = useState(false);
  const [accepting, setAccepting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const modelPickerRef = useRef<HTMLDivElement | null>(null);
  const instructionTextareaRef = useRef<HTMLTextAreaElement | null>(null);
  const chatScrollRef = useRef<HTMLDivElement | null>(null);
  const chatEndRef = useRef<HTMLDivElement | null>(null);

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

  useEffect(() => {
    let cancelled = false;

    listAiModels()
      .then((modelDtos) => {
        if (cancelled) return;
        const nextModels: AiModel[] = modelDtos.map((model) => ({
          id: model.id,
          label: model.displayName || model.modelName,
          endpoint: model.baseUrl,
          modelName: model.modelName,
          provider: 'local',
        }));
        setModels(nextModels);
        setModelId((current) => current || nextModels[0]?.id || '');
      })
      .catch((err: Error) => {
        if (cancelled) return;
        setError(`Could not load configured AI models: ${err.message}`);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const applyWorkflowAiResponse = (response: WorkflowAiResponse) => {
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

    setMessages((prev) => [
      ...prev,
      {
        id: newChatMessageId(),
        role: 'assistant',
        content: response.message || 'Updated workflow conversation.',
        createdAt: Date.now(),
      },
    ]);
  };

  const handleGenerate = async () => {
    if (!canGenerate) return;

    const currentInstruction = instruction;
    const sentAt = Date.now();
    
    setMessages((prev) => [...prev, { id: newChatMessageId(), role: 'user', content: currentInstruction, createdAt: sentAt }]);
    setInstruction('');

    setGenerating(true);
    setError(null);
    setValidationIssues([]);

    try {
      const response = conversationId
        ? await continueWorkflowAiConversation({
            conversationId,
            message: currentInstruction,
          })
        : await startWorkflowAiConversation({
            instruction: currentInstruction,
            modelConfigId: modelId || null,
            userDateTime: new Date().toISOString(),
          });
      applyWorkflowAiResponse(response);
      if (!conversationId && onNavigate) {
        onNavigate(`/c/${response.conversationId}`);
      }
    } catch (err: any) {
      setError(err.message || 'Failed to generate workflow definition.');
      setMessages((prev) => [...prev, { id: newChatMessageId(), role: 'assistant', content: `**Error**: ${err.message || 'Failed to generate workflow.'}`, createdAt: Date.now() }]);
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

  const addLocalModel = () => {
    const endpoint = localEndpoint.trim();
    if (!endpoint) return;

    const label = localModelName.trim() || 'local-llm';
    const model = {
      id: `local-${Date.now()}`,
      label,
      endpoint,
      provider: 'local' as const,
    };
    setModels((current) => [model, ...current]);
    setModelId(model.id);
    setLocalModelName('');
    setLocalEndpoint('');
    setAddModelOpen(false);
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
    setModelId(model.id);
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
                        onClick={() => setModelPickerOpen((open) => !open)}
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
                        <div className="absolute left-0 top-[48px] z-50 w-[448px] rounded-DEFAULT border border-border-subtle bg-surface-container-lowest p-2 shadow-[0_18px_60px_rgba(0,0,0,0.55)]">
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
  
                          <div className="mt-2 space-y-1 max-h-[300px] overflow-y-auto">
                            {filteredModels.length === 0 ? (
                              <div className="px-2 py-5 text-center text-body-sm text-on-surface-variant">
                                No models added.
                              </div>
                            ) : filteredModels.map((model) => (
                              <button
                                key={model.id}
                                type="button"
                                onClick={() => {
                                  setModelId(model.id);
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
                <div ref={chatScrollRef} className="flex-1 overflow-y-auto p-8 pb-[320px] scroll-smooth">
                  <div className={`mx-auto w-full max-w-[900px] space-y-6 transition-all duration-500 ease-out ${chatEntered ? 'translate-y-0 opacity-100' : 'translate-y-6 opacity-0'}`}>
                    {messages.map((msg) => {
                      const isEditing = editingMessageId === msg.id;

                      return (
                      <div key={msg.id} className={`group flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                        <div className={`max-w-[80%] rounded-lg border p-4 text-body-md shadow-[0_12px_32px_rgba(0,0,0,0.18)] ${msg.role === 'user' ? 'border-secondary/20 bg-secondary-container/15 text-on-surface' : 'border-primary/20 bg-primary/10 text-on-surface'}`}>
                          <div className={`mb-2 flex items-center gap-2 font-mono-sm text-[11px] text-on-surface-variant ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                            <span>{msg.role === 'user' ? 'You' : 'Voyager'}</span>
                            <span>{formatMessageTime(msg.createdAt)}</span>
                            <button
                              type="button"
                              onClick={() => startEditingMessage(msg)}
                              className="flex h-6 w-6 items-center justify-center rounded-DEFAULT opacity-70 transition-colors hover:bg-surface-container hover:text-on-surface group-hover:opacity-100"
                              title="Edit message"
                              aria-label="Edit message"
                            >
                              <span className="material-symbols-outlined text-[15px]">edit</span>
                            </button>
                            <button
                              type="button"
                              onClick={() => copyMessage(msg)}
                              className="flex h-6 w-6 items-center justify-center rounded-DEFAULT opacity-70 transition-colors hover:bg-surface-container hover:text-on-surface group-hover:opacity-100"
                              title="Copy message"
                              aria-label="Copy message"
                            >
                              <span className="material-symbols-outlined text-[15px]">{copiedMessageId === msg.id ? 'check' : 'content_copy'}</span>
                            </button>
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
                          ) : msg.role === 'user' ? (
                            <div className="whitespace-pre-wrap font-mono-sm text-on-surface">{msg.content}</div>
                          ) : (
                            <div className="prose prose-invert prose-sm max-w-none">
                              <ReactMarkdown>{msg.content}</ReactMarkdown>
                            </div>
                          )}
                        </div>
                      </div>
                    )})}
                    {generating && (
                      <div className="flex justify-start">
                        <div className="flex max-w-[80%] items-center gap-2 rounded-lg border border-primary/20 bg-primary/10 p-4 text-body-md text-on-surface-variant shadow-[0_12px_32px_rgba(0,0,0,0.18)]">
                          <Loader2 className="animate-spin" size={16} />
                          Generating ASL...
                        </div>
                      </div>
                    )}
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
                    <div className="rounded-DEFAULT bg-surface-container px-3 py-2 text-body-sm font-medium text-primary">
                      Add Models
                    </div>
                    <div className="mt-3 space-y-1 text-body-sm text-on-surface-variant">
                      <div className="px-3 py-2">Added Models</div>
                      <div className="px-3 py-2">AI Defaults</div>
                      <div className="px-3 py-2">Search</div>
                    </div>
                  </aside>

                  <main className="space-y-3 overflow-y-auto p-6">
                    <section className="rounded-lg border border-primary/20 bg-surface-base p-4">
                      <div className="flex items-center justify-between gap-4">
                        <div className="flex items-center gap-3">
                          <Monitor size={18} className="text-primary" />
                          <div>
                            <h3 className="font-headline-md text-headline-md font-semibold text-primary">Add Local Models</h3>
                            <p className="mt-1 text-body-sm text-on-surface-variant">Add a local model server endpoint.</p>
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

                      <div className="mt-4 grid grid-cols-[140px_1fr_72px] gap-2">
                        <input
                          value={localModelName}
                          onChange={(event) => setLocalModelName(event.target.value)}
                          className={fieldClass}
                          placeholder="Model name"
                        />
                        <input
                          value={localEndpoint}
                          onChange={(event) => setLocalEndpoint(event.target.value)}
                          className={fieldClass}
                          placeholder="Endpoint URL, e.g. http://localhost:11434/v1"
                        />
                        <button
                          type="button"
                          onClick={addLocalModel}
                          className="mt-1 h-9 rounded-DEFAULT bg-primary px-3 text-body-sm font-medium text-surface-lowest transition-colors hover:bg-primary-fixed"
                        >
                          Add
                        </button>
                      </div>
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
