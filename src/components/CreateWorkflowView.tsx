import { useEffect, useMemo, useRef, useState } from 'react';
import Editor from '@monaco-editor/react';
import { AlertCircle, Bot, Braces, ChevronDown, Globe2, Loader2, Monitor, MoreHorizontal, Play, Plus, Save, Sparkles, X } from 'lucide-react';
import {
  createWorkflow,
  generateWorkflow,
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
  provider: 'local' | 'api';
};

type Props = {
  onWorkflowCreated: (workflow: WorkflowResponseDTO) => void;
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

export function CreateWorkflowView({ onWorkflowCreated }: Props) {
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
  const [apiKey, setApiKey] = useState('');
  const [definitionText, setDefinitionText] = useState(formatJson(starterDefinition));
  const [validationIssues, setValidationIssues] = useState<string[]>([]);
  const [generating, setGenerating] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const modelPickerRef = useRef<HTMLDivElement | null>(null);
  const instructionTextareaRef = useRef<HTMLTextAreaElement | null>(null);

  const canGenerate = instruction.trim().length > 0 && !generating && !saving;
  const canSave = name.trim().length > 0 && idempotencyKey.trim().length > 0 && !saving && !generating;
  const definitionStatus = useMemo(() => {
    try {
      parseDefinition(definitionText);
      return { valid: true, message: 'ASL JSON looks ready' };
    } catch (err: any) {
      return { valid: false, message: err.message || 'Definition JSON is invalid' };
    }
  }, [definitionText]);
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

  const handleGenerate = async () => {
    if (!canGenerate) return;

    setGenerating(true);
    setError(null);
    setValidationIssues([]);

    try {
      const response = await generateWorkflow({
        instruction,
        modelId: modelId || undefined,
      });
      setDefinitionText(formatJson(response.definition));
      setValidationIssues(response.validationIssues || []);
      setMode('manual');
    } catch (err: any) {
      setError(err.message || 'Failed to generate workflow definition.');
    } finally {
      setGenerating(false);
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
    setApiKey('');
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

  return (
    <div className="voyager-main-bg flex h-full min-h-0 flex-col text-on-surface">
      <header className="grid h-16 shrink-0 grid-cols-[1fr_auto] items-center px-8">
        <div>
          <div className="font-mono-sm text-label-mono uppercase text-on-surface-variant">Create workflow</div>
        </div>
        <div className="justify-self-end">
          {modeSwitch}
        </div>
      </header>
      {mode === 'ai' ? (
        <div className="flex flex-1 min-h-0 flex-col bg-transparent">
          <div className="flex flex-1 min-h-0 items-center justify-center overflow-y-auto p-8">
          <section className="flex min-h-[640px] w-full max-w-[900px] flex-col items-center justify-center">
            <div className="mb-20 flex flex-col items-center text-center">
              <div className="inline-flex items-center justify-center gap-1.5">
                <img src="/voyager-logo.svg" alt="" className="h-24 w-24 shrink-0 md:h-28 md:w-28" />
                <div className="font-mono-sm text-[46px] font-semibold leading-none tracking-normal text-primary md:text-[58px]">Voyager</div>
              </div>
              <p className="mt-2 w-full max-w-[430px] font-mono-sm text-label-mono uppercase text-secondary/70">Smooth sailing for complex workflows</p>
            </div>

            <div className="w-full">
              <div className="relative rounded-lg border border-border-subtle bg-surface-container-low p-4 pb-16 transition-colors focus-within:border-secondary">
                <textarea
                  ref={instructionTextareaRef}
                  value={instruction}
                  onChange={(event) => setInstruction(event.target.value)}
                  rows={1}
                  className="max-h-[220px] min-h-[112px] w-full resize-none overflow-hidden border-0 bg-transparent pb-8 font-mono-sm text-body-lg text-secondary shadow-none outline-none placeholder:text-secondary/45 focus:border-0 focus:outline-none focus:ring-0 focus-visible:outline-none"
                  placeholder="Message Voyager..."
                  disabled={generating}
                />

                <div ref={modelPickerRef} className="absolute bottom-4 left-4 w-48">
                  <button
                    type="button"
                    onClick={() => setModelPickerOpen((open) => !open)}
                    disabled={generating}
                    className="flex h-9 w-full items-center justify-start gap-2 rounded-DEFAULT px-2 text-left text-body-md text-on-surface transition-colors hover:bg-surface-container-high disabled:cursor-not-allowed disabled:opacity-60"
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

                      <div className="mt-2 space-y-1">
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
            </div>

            {addModelOpen && (
              <div className="fixed inset-0 z-[70] flex items-center justify-center bg-black/55 p-6">
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
                        <div className="mt-2 grid grid-cols-2 gap-2">
                          <input
                            value={apiModelName}
                            onChange={(event) => setApiModelName(event.target.value)}
                            className={fieldClass}
                            placeholder="Model name, e.g. deepseek-chat"
                          />
                          <input
                            value={apiKey}
                            onChange={(event) => setApiKey(event.target.value)}
                            className={fieldClass}
                            placeholder="API key"
                            type="password"
                          />
                        </div>
                      </section>
                    </main>
                  </div>
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
          </section>
          </div>
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
