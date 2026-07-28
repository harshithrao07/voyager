import { useCallback, useEffect, useMemo, useState, type Dispatch, type SetStateAction } from 'react';
import { Activity, BarChart3, Bot, Check, ChevronDown, CircleDollarSign, Code2, Copy, Gauge, Globe2, Info, KeyRound, Link, ListChecks, Loader2, Monitor, Play, Plus, Power, RefreshCw, Scale, ShieldCheck, Sparkles, Square, Trash2, X } from 'lucide-react';
import {
  cancelAiModelEvaluation,
  listLatestAiModelEvaluations,
  startAiModelEvaluation,
  type AiModelEvaluationDTO,
  type AiModelEvaluationJudgeSummary,
  type AiModelEvaluationMode,
  type AiModelEvaluationObservation,
} from '../../api';
import { cloudProviderPreset, cloudProviderPresets } from './modelProviders';
import type { AiModel, EndpointModelGroup, ModelSettingsTab } from './types';

type Props = {
  settingsTab: ModelSettingsTab;
  onSettingsTabChange: (tab: ModelSettingsTab) => void;
  onClose: () => void;
  fieldClass: string;
  discoverEndpoint: string;
  onDiscoverEndpointChange: (value: string) => void;
  localModelName: string;
  onLocalModelNameChange: (value: string) => void;
  onAddLocalModel: () => void;
  addingModel: 'local' | 'api' | null;
  localCredentialRef: string;
  onLocalCredentialRefChange: (value: string) => void;
  localEndpointNeedsDockerHint: boolean;
  modelActionMessage: string | null;
  modelActionSuccess: boolean | null;
  apiProvider: string;
  onApiProviderChange: (value: string) => void;
  apiEndpoint: string;
  onApiEndpointChange: (value: string) => void;
  apiModelName: string;
  onApiModelNameChange: (value: string) => void;
  apiCredentialRef: string;
  onApiCredentialRefChange: (value: string) => void;
  onAddApiModel: () => void;
  apiActionMessage: string | null;
  apiActionSuccess: boolean | null;
  endpointGroups: EndpointModelGroup[];
  expandedEndpoint: string | null;
  setExpandedEndpoint: Dispatch<SetStateAction<string | null>>;
  managingModels: boolean;
  onCopyEndpoint: (endpoint: string) => void;
  onUpdateEndpointEnabled: (endpoint: string, enabled: boolean) => void;
  onDeleteEndpointModels: (endpoint: string) => void;
  onUpdateSingleModelEnabled: (model: AiModel, enabled: boolean) => void;
  /** Render as an inline page panel (no overlay, no close button) instead of a modal dialog. */
  embedded?: boolean;
};

const settingsNavItems: Array<{
  id: ModelSettingsTab;
  label: string;
  icon: typeof Plus;
}> = [
  { id: 'add', label: 'Add Models', icon: Plus },
  { id: 'added', label: 'Added Models', icon: Check },
  { id: 'ranking', label: 'Model Ranking', icon: BarChart3 },
];

export function ModelSettingsModal({
  settingsTab,
  onSettingsTabChange,
  onClose,
  fieldClass,
  discoverEndpoint,
  onDiscoverEndpointChange,
  localModelName,
  onLocalModelNameChange,
  onAddLocalModel,
  addingModel,
  localCredentialRef,
  onLocalCredentialRefChange,
  localEndpointNeedsDockerHint,
  modelActionMessage,
  modelActionSuccess,
  apiProvider,
  onApiProviderChange,
  apiEndpoint,
  onApiEndpointChange,
  apiModelName,
  onApiModelNameChange,
  apiCredentialRef,
  onApiCredentialRefChange,
  onAddApiModel,
  apiActionMessage,
  apiActionSuccess,
  endpointGroups,
  expandedEndpoint,
  setExpandedEndpoint,
  managingModels,
  onCopyEndpoint,
  onUpdateEndpointEnabled,
  onDeleteEndpointModels,
  onUpdateSingleModelEnabled,
  embedded = false,
}: Props) {
  const [evaluations, setEvaluations] = useState<Record<string, AiModelEvaluationDTO>>({});
  const [benchmarkAction, setBenchmarkAction] = useState<string | null>(null);
  const [batchMode, setBatchMode] = useState<AiModelEvaluationMode | null>(null);
  const [benchmarkError, setBenchmarkError] = useState<string | null>(null);
  const [judgeModelId, setJudgeModelId] = useState<string>('');
  const [confirmDialog, setConfirmDialog] = useState<BenchmarkConfirmState | null>(null);
  const enabledModels = useMemo(
    () => endpointGroups.flatMap((group) => group.models).filter((model) => model.enabled !== false),
    [endpointGroups],
  );
  const judgeModel = useMemo(
    () => enabledModels.find((model) => model.id === judgeModelId) ?? null,
    [enabledModels, judgeModelId],
  );

  useEffect(() => {
    if (judgeModelId && !enabledModels.some((model) => model.id === judgeModelId)) {
      setJudgeModelId('');
    }
  }, [enabledModels, judgeModelId]);

  const refreshEvaluations = useCallback(async () => {
    const latest = await listLatestAiModelEvaluations();
    setEvaluations(Object.fromEntries(latest.map((evaluation) => [
      evaluation.modelConfigId,
      evaluation,
    ])));
  }, []);

  useEffect(() => {
    if (settingsTab !== 'added' && settingsTab !== 'ranking') return;
    void refreshEvaluations().catch((error) => {
      setBenchmarkError(error instanceof Error ? error.message : 'Could not load model evaluations.');
    });
  }, [refreshEvaluations, settingsTab, endpointGroups.length]);

  const hasRunningEvaluation = useMemo(
    () => Object.values(evaluations).some((evaluation) => evaluation.status === 'RUNNING'),
    [evaluations],
  );

  useEffect(() => {
    if ((settingsTab !== 'added' && settingsTab !== 'ranking') || !hasRunningEvaluation) return;
    const timer = window.setInterval(() => {
      void refreshEvaluations().catch(() => undefined);
    }, 2000);
    return () => window.clearInterval(timer);
  }, [hasRunningEvaluation, refreshEvaluations, settingsTab]);

  const startEvaluation = async (model: AiModel, mode: AiModelEvaluationMode) => {
    setBenchmarkAction(model.id);
    setBenchmarkError(null);
    try {
      const evaluation = await startAiModelEvaluation(model.id, mode, judgeModel?.id ?? null);
      setEvaluations((current) => ({ ...current, [model.id]: evaluation }));
    } catch (error) {
      setBenchmarkError(error instanceof Error ? error.message : 'Could not start the benchmark.');
    } finally {
      setBenchmarkAction(null);
    }
  };

  const runEvaluation = async (model: AiModel, mode: AiModelEvaluationMode) => {
    if (model.provider === 'api' || judgeModel?.provider === 'api') {
      const turns = mode === 'RELIABILITY' ? 21 : 7;
      const judgeNote = judgeModel
        ? ` Each case adds one LLM-judge call to ${judgeModel.label}.`
        : '';
      setConfirmDialog({
        title: 'Run cloud benchmark?',
        message: `This runs ${turns} benchmark cases against ${model.label}.${judgeNote} Your model provider may charge for every generation and repair call.`,
        confirmLabel: mode === 'RELIABILITY' ? 'Run reliability test' : 'Run quick test',
        onConfirm: () => startEvaluation(model, mode),
      });
      return;
    }
    await startEvaluation(model, mode);
  };

  const cancelEvaluation = async (model: AiModel, evaluation: AiModelEvaluationDTO) => {
    setBenchmarkAction(model.id);
    setBenchmarkError(null);
    try {
      const next = await cancelAiModelEvaluation(model.id, evaluation.runId);
      setEvaluations((current) => ({ ...current, [model.id]: next }));
    } catch (error) {
      setBenchmarkError(error instanceof Error ? error.message : 'Could not cancel the benchmark.');
    } finally {
      setBenchmarkAction(null);
    }
  };

  const startAllEvaluations = async (candidates: AiModel[], mode: AiModelEvaluationMode) => {
    setBatchMode(mode);
    setBenchmarkError(null);
    const failures: string[] = [];
    try {
      for (const model of candidates) {
        try {
          const evaluation = await startAiModelEvaluation(model.id, mode, judgeModel?.id ?? null);
          setEvaluations((current) => ({ ...current, [model.id]: evaluation }));
        } catch (error) {
          failures.push(`${model.label}: ${error instanceof Error ? error.message : 'could not start'}`);
        }
      }
      if (failures.length > 0) {
        setBenchmarkError(`Some benchmarks could not start. ${failures.join(' · ')}`);
      }
    } finally {
      setBatchMode(null);
    }
  };

  const runAllEvaluations = async (mode: AiModelEvaluationMode) => {
    const candidates = enabledModels.filter(
      (model) => evaluations[model.id]?.status !== 'RUNNING',
    );
    if (candidates.length === 0) {
      setBenchmarkError('No enabled models are available to test.');
      return;
    }
    const cloudModels = candidates.filter((model) => model.provider === 'api');
    const casesPerModel = mode === 'RELIABILITY' ? 21 : 7;
    if (cloudModels.length > 0 || judgeModel?.provider === 'api') {
      const judgeNote = judgeModel
        ? ` Each case also adds one LLM-judge call to ${judgeModel.label}.`
        : '';
      setConfirmDialog({
        title: 'Run cloud benchmarks?',
        message: `This runs ${casesPerModel * candidates.length} benchmark cases across ${candidates.length} models, including ${cloudModels.length} cloud ${cloudModels.length === 1 ? 'model' : 'models'}.${judgeNote} Cloud providers may charge for every generation and repair call.`,
        confirmLabel: mode === 'RELIABILITY' ? 'Run reliability tests' : 'Run quick tests',
        onConfirm: () => startAllEvaluations(candidates, mode),
      });
      return;
    }
    await startAllEvaluations(candidates, mode);
  };

  return (
    <>
    <div className={embedded
      ? 'flex h-full min-h-0 w-full flex-col'
      : 'pointer-events-auto fixed inset-0 z-[70] flex items-center justify-center bg-black/55 p-6'}>
      <div className={embedded
        ? 'flex h-full min-h-0 w-full flex-col overflow-hidden rounded-lg border border-primary/20 bg-surface-lowest'
        : 'flex max-h-[88vh] w-full max-w-6xl flex-col overflow-hidden rounded-lg border border-primary/20 bg-surface-lowest shadow-[0_24px_90px_rgba(0,0,0,0.65)]'}>
        <div className="flex h-16 shrink-0 items-center justify-between px-6 shadow-[inset_0_-1px_rgba(255,255,255,0.08)]">
          <div className="flex items-center gap-2 font-display text-[15px] font-semibold text-primary">
            <Sparkles size={18} />
            {embedded ? 'AI Settings' : 'Settings'}
          </div>
          {!embedded && (
            <button
              type="button"
              onClick={onClose}
              className="flex h-8 w-8 items-center justify-center rounded-DEFAULT border border-primary/30 text-primary transition-colors hover:bg-surface-container"
              aria-label="Close settings"
            >
              <X size={16} />
            </button>
          )}
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
                    onClick={() => onSettingsTabChange(item.id)}
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
                <AddLocalModelsSection
                  discoverEndpoint={discoverEndpoint}
                  onDiscoverEndpointChange={onDiscoverEndpointChange}
                  localModelName={localModelName}
                  onLocalModelNameChange={onLocalModelNameChange}
                  onAddLocalModel={onAddLocalModel}
                  addingModel={addingModel}
                  localCredentialRef={localCredentialRef}
                  onLocalCredentialRefChange={onLocalCredentialRefChange}
                  localEndpointNeedsDockerHint={localEndpointNeedsDockerHint}
                  modelActionMessage={modelActionMessage}
                  modelActionSuccess={modelActionSuccess}
                />

                <AddApiModelsSection
                  fieldClass={fieldClass}
                  apiProvider={apiProvider}
                  onApiProviderChange={onApiProviderChange}
                  apiEndpoint={apiEndpoint}
                  onApiEndpointChange={onApiEndpointChange}
                  apiModelName={apiModelName}
                  onApiModelNameChange={onApiModelNameChange}
                  apiCredentialRef={apiCredentialRef}
                  onApiCredentialRefChange={onApiCredentialRefChange}
                  onAddApiModel={onAddApiModel}
                  apiActionMessage={apiActionMessage}
                  apiActionSuccess={apiActionSuccess}
                  addingModel={addingModel}
                />
              </>
            )}

            {settingsTab === 'added' && (
              <AddedModelsSection
                endpointGroups={endpointGroups}
                expandedEndpoint={expandedEndpoint}
                setExpandedEndpoint={setExpandedEndpoint}
                managingModels={managingModels}
                onCopyEndpoint={onCopyEndpoint}
                onUpdateEndpointEnabled={onUpdateEndpointEnabled}
                onDeleteEndpointModels={onDeleteEndpointModels}
                onUpdateSingleModelEnabled={onUpdateSingleModelEnabled}
              />
            )}

            {settingsTab === 'ranking' && (
              <ModelRankingSection
                endpointGroups={endpointGroups}
                managingModels={managingModels}
                evaluations={evaluations}
                benchmarkAction={benchmarkAction}
                benchmarkError={benchmarkError}
                batchMode={batchMode}
                judgeModelId={judgeModelId}
                onJudgeModelIdChange={setJudgeModelId}
                judgeCandidates={enabledModels}
                onRunEvaluation={runEvaluation}
                onRunAllEvaluations={runAllEvaluations}
                onCancelEvaluation={cancelEvaluation}
              />
            )}
          </main>
        </div>
      </div>
    </div>

    {confirmDialog && (
      <BenchmarkConfirmDialog
        title={confirmDialog.title}
        message={confirmDialog.message}
        confirmLabel={confirmDialog.confirmLabel}
        onConfirm={() => {
          const run = confirmDialog.onConfirm;
          setConfirmDialog(null);
          void run();
        }}
        onCancel={() => setConfirmDialog(null)}
      />
    )}
    </>
  );
}

type BenchmarkConfirmState = {
  title: string;
  message: string;
  confirmLabel: string;
  onConfirm: () => void | Promise<void>;
};

function BenchmarkConfirmDialog({
  title,
  message,
  confirmLabel,
  onConfirm,
  onCancel,
}: BenchmarkConfirmState & { onCancel: () => void }) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCancel();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onCancel]);

  return (
    <div
      className="fixed inset-0 z-[90] flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      onClick={onCancel}
    >
      <div
        className="flex w-full max-w-[460px] flex-col overflow-hidden rounded-xl border border-status-warning/40 bg-surface-container-lowest shadow-[0_30px_80px_rgba(0,0,0,0.5)]"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-start gap-3 border-b border-border-subtle p-5 pb-4">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-status-warning/40 bg-status-warning/10 text-status-warning">
            <CircleDollarSign size={18} />
          </div>
          <div className="min-w-0 flex-1">
            <h3 className="text-[15px] font-semibold text-on-surface">{title}</h3>
            <p className="mt-1.5 text-[12px] leading-5 text-on-surface-variant">{message}</p>
          </div>
          <button
            type="button"
            onClick={onCancel}
            className="shrink-0 rounded-md p-1 text-on-surface-variant transition-colors hover:text-on-surface"
            aria-label="Cancel"
          >
            <X size={16} />
          </button>
        </div>

        <div className="flex shrink-0 justify-end gap-2 border-t border-border-subtle px-5 py-4">
          <button
            type="button"
            onClick={onCancel}
            className="flex h-9 items-center rounded-lg border border-border-subtle px-4 text-[12px] text-on-surface-variant transition-colors hover:text-on-surface"
          >
            Cancel
          </button>
          <button
            type="button"
            autoFocus
            onClick={() => void onConfirm()}
            className="flex h-9 items-center gap-2 rounded-lg border border-status-warning bg-status-warning px-4 text-[12px] font-semibold text-on-primary transition-colors hover:opacity-90"
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

function AddLocalModelsSection({
  discoverEndpoint,
  onDiscoverEndpointChange,
  localModelName,
  onLocalModelNameChange,
  onAddLocalModel,
  addingModel,
  localCredentialRef,
  onLocalCredentialRefChange,
  localEndpointNeedsDockerHint,
  modelActionMessage,
  modelActionSuccess,
}: Pick<Props,
  | 'discoverEndpoint'
  | 'onDiscoverEndpointChange'
  | 'localModelName'
  | 'onLocalModelNameChange'
  | 'onAddLocalModel'
  | 'addingModel'
  | 'localCredentialRef'
  | 'onLocalCredentialRefChange'
  | 'localEndpointNeedsDockerHint'
  | 'modelActionMessage'
  | 'modelActionSuccess'
>) {
  const busy = addingModel !== null;
  const adding = addingModel === 'local';

  return (
    <section className="relative rounded-lg border border-primary/20 bg-surface-base p-4">
      <div className="flex items-start gap-4 border-b border-border-subtle/40 pb-3">
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
      </div>

      <div className="mt-3 grid grid-cols-[minmax(0,1fr)_72px] gap-2">
        <input
          id="discover-endpoint-input"
          value={discoverEndpoint}
          onChange={(event) => onDiscoverEndpointChange(event.target.value)}
          className="h-10 rounded-DEFAULT border border-primary/30 bg-surface-container px-3 font-mono-sm text-body-sm text-on-surface outline-none placeholder:text-on-surface-variant/55 focus:border-primary/60"
          placeholder="Paste endpoint URL, e.g. http://host.docker.internal:11434/v1"
          disabled={busy}
        />
        <button
          id="add-local-model-btn"
          type="button"
          onClick={onAddLocalModel}
          disabled={busy || !discoverEndpoint.trim() || !localModelName.trim()}
          className="flex h-10 items-center justify-center gap-1.5 rounded-DEFAULT bg-primary px-3 text-body-sm font-medium text-surface-lowest transition-colors hover:bg-primary-fixed disabled:cursor-not-allowed disabled:opacity-50"
        >
          {adding && <Loader2 className="animate-spin" size={14} />}
          {adding ? 'Adding…' : 'Add'}
        </button>
      </div>
      {localEndpointNeedsDockerHint && (
        <p className="mt-2 text-[12px] leading-5 text-status-warning">
          If the backend is running in Docker, localhost may point inside the backend container. Try host.docker.internal if this fails.
        </p>
      )}

      <div className="mt-2 grid grid-cols-1 gap-2 sm:grid-cols-2">
        <input
          value={localModelName}
          onChange={(event) => onLocalModelNameChange(event.target.value)}
          onKeyDown={(event) => { if (event.key === 'Enter') onAddLocalModel(); }}
          className="h-9 w-full rounded-DEFAULT border border-primary/30 bg-surface-container px-3 font-mono-sm text-body-sm text-on-surface outline-none placeholder:text-on-surface-variant/55 focus:border-primary/60"
          placeholder="Exact model name, e.g. qwen2.5:7b"
          disabled={busy}
          aria-label="Local model name"
        />
        <input
          type="password"
          value={localCredentialRef}
          onChange={(event) => onLocalCredentialRefChange(event.target.value)}
          className="h-9 w-full rounded-DEFAULT border border-primary/30 bg-surface-container px-3 font-mono-sm text-body-sm text-on-surface outline-none placeholder:text-on-surface-variant/55 focus:border-primary/60"
          placeholder="API key / token (leave blank if none)"
          spellCheck={false}
          autoComplete="off"
          aria-label="Local model API key"
        />
      </div>

      <p className="mt-2 text-[11px] leading-5 text-on-surface-variant">
        Enter the exact model identifier accepted by this endpoint. An optional key is encrypted before database storage. Voyager saves the model directly and does not require a model-list API.
      </p>

      {modelActionMessage && (
        <div className={`mt-3 text-body-sm ${modelActionSuccess ? 'text-status-success' : 'text-status-error'}`}>
          {modelActionMessage}
        </div>
      )}
    </section>
  );
}

function AddApiModelsSection({
  fieldClass,
  apiProvider,
  onApiProviderChange,
  apiEndpoint,
  onApiEndpointChange,
  apiModelName,
  onApiModelNameChange,
  apiCredentialRef,
  onApiCredentialRefChange,
  onAddApiModel,
  apiActionMessage,
  apiActionSuccess,
  addingModel,
}: Pick<Props,
  | 'fieldClass'
  | 'apiProvider'
  | 'onApiProviderChange'
  | 'apiEndpoint'
  | 'onApiEndpointChange'
  | 'apiModelName'
  | 'onApiModelNameChange'
  | 'apiCredentialRef'
  | 'onApiCredentialRefChange'
  | 'onAddApiModel'
  | 'apiActionMessage'
  | 'apiActionSuccess'
  | 'addingModel'
>) {
  const providerPreset = cloudProviderPreset(apiProvider);
  const busy = addingModel !== null;
  const adding = addingModel === 'api';

  return (
    <section className="relative rounded-lg border border-primary/20 bg-surface-base p-4">
      <div className="flex items-start gap-4 border-b border-border-subtle/40 pb-3">
        <div className="flex items-center gap-3">
          <Globe2 size={18} className="text-primary" />
          <div>
            <h3 className="font-headline-md text-headline-md font-semibold text-primary">Add API Models</h3>
            <p className="mt-1 text-body-sm text-on-surface-variant">Connect a cloud provider endpoint.</p>
          </div>
        </div>
      </div>

      <div className="mt-3 grid grid-cols-[160px_minmax(0,1fr)_72px] gap-2">
        <select
          value={apiProvider}
          onChange={(event) => onApiProviderChange(event.target.value)}
          className={`${fieldClass} mt-0`}
          disabled={busy}
          aria-label="Cloud provider"
        >
          {cloudProviderPresets.map((provider) => (
            <option key={provider.name} value={provider.name}>{provider.name}</option>
          ))}
        </select>
        <input
          value={apiEndpoint}
          onChange={(event) => onApiEndpointChange(event.target.value)}
          className={`${fieldClass} mt-0 font-mono-sm text-[12px]`}
          placeholder={providerPreset.endpoint || 'https://provider.example/v1'}
          disabled={busy}
          aria-label="Cloud endpoint URL"
        />
        <button
          type="button"
          onClick={onAddApiModel}
          disabled={busy || !apiEndpoint.trim() || !apiModelName.trim()}
          className="flex h-10 items-center justify-center gap-1.5 rounded-DEFAULT bg-primary px-3 text-body-sm font-medium text-surface-lowest transition-colors hover:bg-primary-fixed disabled:cursor-not-allowed disabled:opacity-50"
        >
          {adding && <Loader2 className="animate-spin" size={14} />}
          {adding ? 'Adding…' : 'Add'}
        </button>
      </div>
      <div className="mt-2 grid grid-cols-1 gap-2 sm:grid-cols-2">
        <input
          value={apiModelName}
          onChange={(event) => onApiModelNameChange(event.target.value)}
          onKeyDown={(event) => { if (event.key === 'Enter') onAddApiModel(); }}
          className={`${fieldClass} mt-0`}
          placeholder={providerPreset.modelPlaceholder}
          disabled={busy}
          aria-label="Cloud model name"
        />
        <input
          type="password"
          value={apiCredentialRef}
          onChange={(event) => onApiCredentialRefChange(event.target.value)}
          className={`${fieldClass} mt-0 font-mono-sm text-[12px]`}
          placeholder="API key, e.g. sk-..."
          spellCheck={false}
          autoComplete="off"
          disabled={busy}
          aria-label="Cloud API key"
        />
      </div>

      <p className="mt-2 text-[11px] leading-5 text-on-surface-variant">
        The optional key is encrypted before database storage and is never returned to the browser.
      </p>

      {apiActionMessage && (
        <div className={`mt-2 text-body-sm ${apiActionSuccess ? 'text-status-success' : 'text-status-error'}`}>
          {apiActionMessage}
        </div>
      )}
    </section>
  );
}

function AddedModelsSection({
  endpointGroups,
  expandedEndpoint,
  setExpandedEndpoint,
  managingModels,
  onCopyEndpoint,
  onUpdateEndpointEnabled,
  onDeleteEndpointModels,
  onUpdateSingleModelEnabled,
}: Pick<Props,
  | 'endpointGroups'
  | 'expandedEndpoint'
  | 'setExpandedEndpoint'
  | 'managingModels'
  | 'onCopyEndpoint'
  | 'onUpdateEndpointEnabled'
  | 'onDeleteEndpointModels'
  | 'onUpdateSingleModelEnabled'
>) {
  return (
    <section className="rounded-lg border border-primary/20 bg-surface-base p-4">
      <div className="flex items-start gap-4 border-b border-border-subtle/40 pb-3">
        <div className="flex items-center gap-3">
          <Check size={18} className="text-primary" />
          <div>
            <div className="flex items-baseline gap-2">
              <h3 className="font-headline-md text-headline-md font-semibold text-primary">Added Models</h3>
              <span className="font-mono-sm text-[12px] text-on-surface-variant">(Endpoints)</span>
            </div>
            <p className="mt-1 text-body-sm text-on-surface-variant">Enable, disable, or remove model endpoints. Benchmark and rank models on the Model Ranking tab.</p>
          </div>
        </div>
      </div>

      <div className="mt-4 space-y-3">
        {endpointGroups.length === 0 ? (
          <div className="rounded-DEFAULT border border-border-subtle/60 bg-surface-container-lowest p-5 text-body-sm text-on-surface-variant">
            No model endpoints added yet.
          </div>
        ) : endpointGroups.map((group) => {
          const expanded = expandedEndpoint === group.endpoint;
          const ProviderIcon = group.provider === 'api' ? Globe2 : Bot;
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
                    <ProviderIcon size={16} className="shrink-0 text-primary" />
                    <span className="truncate font-mono-sm text-[12px] font-semibold text-primary">{group.host}</span>
                    <span className="rounded-DEFAULT bg-primary/15 px-2 py-0.5 font-mono-sm text-[10px] font-semibold uppercase text-primary">
                      {group.provider === 'api' ? 'Cloud' : 'Local'}
                    </span>
                    {group.hasCredential && (
                      <span className="flex items-center gap-1 rounded-DEFAULT bg-status-success/15 px-2 py-0.5 font-mono-sm text-[10px] font-semibold text-status-success">
                        <KeyRound size={10} />
                        Encrypted key
                      </span>
                    )}
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
                        onCopyEndpoint(group.endpoint);
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
                    onClick={() => onUpdateEndpointEnabled(group.endpoint, group.enabledCount === 0)}
                    disabled={managingModels}
                    className="flex h-9 items-center gap-2 rounded-DEFAULT border border-primary/30 px-3 text-body-sm text-primary transition-colors hover:bg-surface-container disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    <Power size={14} />
                    {group.enabledCount > 0 ? 'Disable' : 'Enable'}
                  </button>
                  <button
                    type="button"
                    onClick={() => onDeleteEndpointModels(group.endpoint)}
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
                      <button type="button" onClick={() => onUpdateEndpointEnabled(group.endpoint, true)} disabled={managingModels} className="text-primary hover:text-primary-fixed disabled:opacity-50">All</button>
                      <button type="button" onClick={() => onUpdateEndpointEnabled(group.endpoint, false)} disabled={managingModels} className="text-primary hover:text-primary-fixed disabled:opacity-50">None</button>
                    </div>
                  </div>

                  <div className="max-h-[440px] space-y-2 overflow-y-auto rounded-DEFAULT border border-primary/20 bg-surface-base p-2">
                    {group.models.map((model) => {
                      const enabled = model.enabled !== false;
                      return (
                        <div
                          key={model.id}
                          className="flex items-center justify-between gap-3 rounded-DEFAULT border border-border-subtle/50 bg-surface-container-lowest px-3 py-2.5"
                        >
                          <button
                            type="button"
                            onClick={() => onUpdateSingleModelEnabled(model, !enabled)}
                            disabled={managingModels}
                            className="flex min-w-0 items-center gap-2 text-left disabled:cursor-not-allowed disabled:opacity-60"
                            aria-label={`${enabled ? 'Disable' : 'Enable'} ${model.label}`}
                          >
                            <span className={`flex h-4 w-4 shrink-0 items-center justify-center rounded-full border ${enabled ? 'border-primary bg-primary text-surface-lowest' : 'border-border-muted text-transparent'}`}>
                              <Check size={11} />
                            </span>
                            <span className={`truncate font-mono-sm text-[12px] ${enabled ? 'text-primary' : 'text-on-surface-variant'}`}>
                              {model.label}
                            </span>
                          </button>
                          <span className="shrink-0 font-mono-sm text-[10px] uppercase text-on-surface-variant">
                            {enabled ? 'Enabled' : 'Disabled'}
                          </span>
                        </div>
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
  );
}

function ModelRankingSection({
  endpointGroups,
  managingModels,
  evaluations,
  benchmarkAction,
  benchmarkError,
  batchMode,
  judgeModelId,
  onJudgeModelIdChange,
  judgeCandidates,
  onRunEvaluation,
  onRunAllEvaluations,
  onCancelEvaluation,
}: Pick<Props,
  | 'endpointGroups'
  | 'managingModels'
> & {
  evaluations: Record<string, AiModelEvaluationDTO>;
  benchmarkAction: string | null;
  benchmarkError: string | null;
  batchMode: AiModelEvaluationMode | null;
  judgeModelId: string;
  onJudgeModelIdChange: (modelId: string) => void;
  judgeCandidates: AiModel[];
  onRunEvaluation: (model: AiModel, mode: AiModelEvaluationMode) => void;
  onRunAllEvaluations: (mode: AiModelEvaluationMode) => void;
  onCancelEvaluation: (model: AiModel, evaluation: AiModelEvaluationDTO) => void;
}) {
  const models = endpointGroups.flatMap((group) => group.models);
  const hasRunningEvaluation = Object.values(evaluations).some(
    (evaluation) => evaluation.status === 'RUNNING',
  );
  return (
    <section className="rounded-lg border border-primary/20 bg-surface-base p-4">
      <div className="flex items-start gap-4 border-b border-border-subtle/40 pb-3">
        <div className="flex items-center gap-3">
          <BarChart3 size={18} className="text-primary" />
          <div>
            <h3 className="font-headline-md text-headline-md font-semibold text-primary">Model Ranking</h3>
            <p className="mt-1 text-body-sm text-on-surface-variant">Run the same capability tape against every enabled model and compare them before choosing one for workflow generation.</p>
          </div>
        </div>
      </div>

      <HowRankingWorks />

      <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-2 rounded-DEFAULT border border-border-subtle/60 bg-surface-container-lowest px-3 py-2">
        <label
          htmlFor="benchmark-judge-model"
          className="flex shrink-0 items-center gap-1.5 font-mono-sm text-[10px] font-semibold uppercase text-on-surface-variant"
        >
          <Scale size={13} className="text-primary" />
          LLM judge
        </label>
        <select
          id="benchmark-judge-model"
          value={judgeModelId}
          onChange={(event) => onJudgeModelIdChange(event.target.value)}
          className="h-9 min-w-[220px] rounded-DEFAULT border border-primary/30 bg-surface-container px-2 font-mono-sm text-[11px] leading-normal text-on-surface outline-none focus:border-primary/60"
        >
          <option value="">None — deterministic metrics only</option>
          {judgeCandidates.map((candidate) => (
            <option key={candidate.id} value={candidate.id}>
              {candidate.label}{candidate.provider === 'api' ? ' (cloud)' : ''}
            </option>
          ))}
        </select>
        <span className="min-w-[200px] flex-1 text-[10px] leading-4 text-on-surface-variant">
          Scores each case 1–5 against the suite rubric with a rationale. Advisory only — it never
          moves quality gates or the recommendation. Prefer a stronger model than the one under test.
        </span>
      </div>

      {benchmarkError && (
        <div className="mt-3 rounded-DEFAULT border border-status-error/30 bg-status-error/10 px-3 py-2 text-body-sm text-status-error">
          {benchmarkError}
        </div>
      )}

      {models.length > 0 ? (
        <ModelComparisonBoard
          models={models}
          evaluations={evaluations}
          batchMode={batchMode}
          managingModels={managingModels}
          benchmarkAction={benchmarkAction}
          runAllDisabled={managingModels || hasRunningEvaluation}
          onRunEvaluation={onRunEvaluation}
          onRunAllEvaluations={onRunAllEvaluations}
          onCancelEvaluation={onCancelEvaluation}
        />
      ) : (
        <div className="mt-4 rounded-DEFAULT border border-border-subtle/60 bg-surface-container-lowest p-5 text-body-sm text-on-surface-variant">
          No models added yet. Add a model to rank it here.
        </div>
      )}
    </section>
  );
}

function ModelTestDetail({
  model,
  evaluation,
  benchmarkAction,
  batchMode,
  managingModels,
  onRunEvaluation,
  onCancelEvaluation,
}: {
  model: AiModel;
  evaluation?: AiModelEvaluationDTO;
  benchmarkAction: string | null;
  batchMode: AiModelEvaluationMode | null;
  managingModels: boolean;
  onRunEvaluation: (model: AiModel, mode: AiModelEvaluationMode) => void;
  onCancelEvaluation: (model: AiModel, evaluation: AiModelEvaluationDTO) => void;
}) {
  const enabled = model.enabled !== false;
  const actionPending = benchmarkAction === model.id;
  const batchPending = batchMode !== null;
  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-1.5 text-[10px] text-on-surface-variant">
          <Gauge size={12} />
          <span>Quick: 7 cases</span>
          <span className="text-border-muted">•</span>
          <span>Reliability: 21 cases</span>
          {!enabled && (
            <span className="ml-1 text-status-warning">· Enable in Added Models to test</span>
          )}
        </div>
        <div className="flex items-center gap-1.5">
          {evaluation?.status === 'RUNNING' ? (
            <button
              type="button"
              onClick={() => onCancelEvaluation(model, evaluation)}
              disabled={actionPending || evaluation.cancelRequested}
              className="flex h-7 items-center gap-1.5 rounded-DEFAULT border border-status-error/30 px-2.5 font-mono-sm text-[10px] font-semibold uppercase text-status-error transition-colors hover:bg-status-error/10 disabled:opacity-50"
            >
              {actionPending
                ? <Loader2 size={11} className="animate-spin" />
                : <Square size={10} />}
              {evaluation.cancelRequested ? 'Stopping' : 'Stop'}
            </button>
          ) : (
            <>
              <button
                type="button"
                onClick={() => onRunEvaluation(model, 'QUICK')}
                disabled={!enabled || actionPending || batchPending || managingModels}
                title={enabled ? 'Run one pass of workflow-ai-v1' : 'Enable this model in Added Models before testing it'}
                className="flex h-7 items-center gap-1.5 rounded-DEFAULT border border-primary/30 px-2.5 font-mono-sm text-[10px] font-semibold uppercase text-primary transition-colors hover:bg-primary/10 disabled:cursor-not-allowed disabled:opacity-40"
              >
                {actionPending
                  ? <Loader2 size={11} className="animate-spin" />
                  : <Play size={11} />}
                Quick test
              </button>
              <button
                type="button"
                onClick={() => onRunEvaluation(model, 'RELIABILITY')}
                disabled={!enabled || actionPending || batchPending || managingModels}
                title={enabled ? 'Run three passes of workflow-ai-v1' : 'Enable this model in Added Models before testing it'}
                className="flex h-7 items-center gap-1.5 rounded-DEFAULT bg-primary px-2.5 font-mono-sm text-[10px] font-semibold uppercase text-surface-lowest transition-colors hover:bg-primary-fixed disabled:cursor-not-allowed disabled:opacity-40"
              >
                <ShieldCheck size={11} />
                Reliability
              </button>
            </>
          )}
        </div>
      </div>

      {evaluation?.status === 'RUNNING' && (
        <EvaluationProgress evaluation={evaluation} />
      )}

      {evaluation?.status === 'RUNNING'
        && evaluation.progressObservations
        && evaluation.progressObservations.length > 0 && (
        <PerCaseResults
          observations={evaluation.progressObservations}
          defaultOpen
          live
        />
      )}

      {evaluation?.result && (
        <CapabilityTape evaluation={evaluation} />
      )}

      {evaluation?.result?.judge && (
        <JudgeSummaryPanel judge={evaluation.result.judge} />
      )}

      {evaluation?.result?.observations && evaluation.result.observations.length > 0 && (
        <PerCaseResults observations={evaluation.result.observations} />
      )}

      {evaluation?.status === 'FAILED' && evaluation.errorMessage && (
        <p className="mt-2 text-[11px] leading-4 text-status-error">
          {evaluation.errorMessage}
        </p>
      )}

      {!evaluation && (
        <p className="mt-2 text-[11px] leading-4 text-on-surface-variant">
          Not tested yet. Run a Quick or Reliability test to score this model.
        </p>
      )}
    </div>
  );
}

function HowRankingWorks() {
  return (
    <details className="group mt-3 overflow-hidden rounded-DEFAULT border border-border-subtle/60 bg-surface-container-lowest">
      <summary className="flex cursor-pointer list-none items-center gap-2 px-3 py-2.5 font-mono-sm text-[11px] font-semibold uppercase tracking-wide text-on-surface-variant transition-colors hover:text-primary [&::-webkit-details-marker]:hidden">
        <Info size={13} className="text-primary" />
        How ranking works
        <ChevronDown size={14} className="ml-auto transition-transform group-open:rotate-180" />
      </summary>
      <div className="space-y-4 border-t border-border-subtle/50 px-4 py-3 text-[11px] leading-5 text-on-surface-variant">
        <section>
          <h4 className="font-mono-sm text-[10px] font-semibold uppercase tracking-wide text-on-surface">What runs</h4>
          <p className="mt-1">
            Every enabled model is sent the same fixed test suite (<span className="font-mono-sm text-on-surface">workflow-ai-v1</span>)
            through Voyager's real chat pipeline — the exact code that builds your workflows. There is no
            special "test prompt"; the model is graded on how it behaves in production. Each case is judged
            with deterministic checks, and results are cached per model.
          </p>
        </section>

        <section>
          <h4 className="font-mono-sm text-[10px] font-semibold uppercase tracking-wide text-on-surface">What is measured</h4>
          <p className="mt-1">
            The five capability columns each score 0–100 across the suite's cases. <span className="font-mono-sm text-on-surface">Cases</span> is
            passed / total; <span className="font-mono-sm text-on-surface">P95</span> is the 95th-percentile response latency.
          </p>
          <div className="mt-2 space-y-1.5">
            {[
              ['Chat', 'Replies naturally to greetings and general questions instead of deflecting into "what\'s the workflow name?"'],
              ['ASL', 'Produces a structurally valid JSONata state machine when asked to build a workflow.'],
              ['MCP', 'Correctly routes needs that touch an external service to an MCP requirement.'],
              ['Fn', 'Correctly proposes self-contained, deterministic local functions.'],
              ['Safety', 'Handles unsafe or out-of-scope requests without inventing resources or credentials.'],
            ].map(([label, description]) => (
              <div key={label} className="flex gap-2">
                <span className="mt-px w-14 shrink-0 font-mono-sm text-[10px] font-semibold uppercase text-primary">{label}</span>
                <span className="min-w-0 flex-1">{description}</span>
              </div>
            ))}
          </div>
        </section>

        <section>
          <h4 className="font-mono-sm text-[10px] font-semibold uppercase tracking-wide text-on-surface">Which prompt each case uses</h4>
          <p className="mt-1">
            Each case sends the model <span className="text-on-surface">one</span> system prompt, chosen per turn
            exactly as in normal use — not all three at once. Chat-style cases use the slim
            <span className="font-mono-sm text-on-surface"> general-chat</span> prompt; build cases (ASL, MCP, Fn,
            Safety) use the full <span className="font-mono-sm text-on-surface">builder</span> prompt, plus a
            <span className="font-mono-sm text-on-surface"> function-authoring</span> contract when a function is
            proposed. The three are combined only to compute the staleness fingerprint below — never sent together.
          </p>
        </section>

        <section>
          <h4 className="font-mono-sm text-[10px] font-semibold uppercase tracking-wide text-on-surface">How each answer is graded</h4>
          <p className="mt-1">
            Grading is deterministic rule-checks on the model's JSON reply — not an opinion about which answer
            "reads" best. Each check is a plain pass/fail, e.g. is a valid ASL machine present, did a chat turn
            avoid deflecting into workflow setup, was an external need routed to MCP rather than a function, was
            the JSON contract kept and no secret leaked. Those pass rates become the capability scores above.
          </p>
          <p className="mt-2">
            Each metric is also compared to a minimum <span className="text-on-surface">quality gate</span> from the
            suite, which sets the recommendation:
          </p>
          <div className="mt-2 space-y-1.5">
            {[
              ['Recommended', 'Every quality gate passed.', 'text-status-success'],
              ['Limited', 'No hard failure, but at least one gate fell below its minimum.', 'text-status-warning'],
              ['Failed', 'A hard failure — broke the JSON contract, leaked a secret, or mishandled retries.', 'text-status-error'],
            ].map(([label, description, tone]) => (
              <div key={label} className="flex gap-2">
                <span className={`mt-px w-24 shrink-0 font-mono-sm text-[10px] font-semibold uppercase ${tone}`}>{label}</span>
                <span className="min-w-0 flex-1">{description}</span>
              </div>
            ))}
          </div>
          <p className="mt-2">
            The recommendation comes only from these gates — the LLM judge never changes it.
          </p>
        </section>

        <section>
          <h4 className="font-mono-sm text-[10px] font-semibold uppercase tracking-wide text-on-surface">How the order is decided</h4>
          <p className="mt-1">
            Current results rank first, then stale results, then running, then untested. Within results, models
            are ordered by recommendation (Recommended → Limited → Failed), then case pass rate, then safety,
            then lower latency.
          </p>
        </section>

        <section>
          <h4 className="font-mono-sm text-[10px] font-semibold uppercase tracking-wide text-on-surface">What "stale" means</h4>
          <p className="mt-1">
            Each result is fingerprinted against the three prompts it was tested with. If Voyager's prompts
            change, past scores no longer reflect current behavior, so they are flagged <span className="text-status-warning">Prompt changed</span> and
            drop below current results. Re-run the benchmark to refresh them. Staleness is not time-based — a
            result only goes stale when the prompts themselves change.
          </p>
        </section>

        <section>
          <h4 className="font-mono-sm text-[10px] font-semibold uppercase tracking-wide text-on-surface">LLM judge &amp; test depth</h4>
          <p className="mt-1">
            An optional LLM judge scores each case 1–5 with a rationale, but it is advisory only — it never
            moves the recommendation or quality gates. <span className="font-mono-sm text-on-surface">Quick</span> runs
            7 cases in one pass; <span className="font-mono-sm text-on-surface">Reliability</span> runs 21 cases
            across three passes to expose run-to-run variance.
          </p>
        </section>
      </div>
    </details>
  );
}

function ModelComparisonBoard({
  models,
  evaluations,
  batchMode,
  managingModels,
  benchmarkAction,
  runAllDisabled,
  onRunEvaluation,
  onRunAllEvaluations,
  onCancelEvaluation,
}: {
  models: AiModel[];
  evaluations: Record<string, AiModelEvaluationDTO>;
  batchMode: AiModelEvaluationMode | null;
  managingModels: boolean;
  benchmarkAction: string | null;
  runAllDisabled: boolean;
  onRunEvaluation: (model: AiModel, mode: AiModelEvaluationMode) => void;
  onRunAllEvaluations: (mode: AiModelEvaluationMode) => void;
  onCancelEvaluation: (model: AiModel, evaluation: AiModelEvaluationDTO) => void;
}) {
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const enabledModels = models.filter((model) => model.enabled !== false);
  const rankedModels = [...models].sort((left, right) => compareModels(
    left,
    right,
    evaluations,
  ));
  const controlsDisabled = runAllDisabled || enabledModels.length === 0 || batchMode !== null;
  const gridCols = 'grid-cols-[28px_minmax(150px,1.3fr)_90px_minmax(235px,1.5fr)_58px_58px_24px]';

  return (
    <div className="mt-4 overflow-hidden rounded-lg border border-primary/20 bg-surface-container-lowest">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border-subtle/50 px-4 py-3">
        <div className="flex items-start gap-3">
          <BarChart3 size={17} className="mt-0.5 text-primary" />
          <div>
            <div className="font-display text-[13px] font-semibold text-primary">Model ranking</div>
            <div className="mt-0.5 text-[10px] text-on-surface-variant">
              Ranked by results. Expand a row to test it, watch it live, and read every case.
            </div>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <span className="mr-1 font-mono-sm text-[9px] uppercase text-on-surface-variant">
            {enabledModels.length} enabled
          </span>
          <button
            type="button"
            onClick={() => onRunAllEvaluations('QUICK')}
            disabled={controlsDisabled}
            className="flex h-8 items-center gap-1.5 rounded-DEFAULT border border-primary/30 px-3 font-mono-sm text-[10px] font-semibold uppercase text-primary transition-colors hover:bg-primary/10 disabled:cursor-not-allowed disabled:opacity-40"
          >
            {batchMode === 'QUICK'
              ? <Loader2 size={11} className="animate-spin" />
              : <Play size={11} />}
            Quick all
          </button>
          <button
            type="button"
            onClick={() => onRunAllEvaluations('RELIABILITY')}
            disabled={controlsDisabled}
            className="flex h-8 items-center gap-1.5 rounded-DEFAULT bg-primary px-3 font-mono-sm text-[10px] font-semibold uppercase text-surface-lowest transition-colors hover:bg-primary-fixed disabled:cursor-not-allowed disabled:opacity-40"
          >
            {batchMode === 'RELIABILITY'
              ? <Loader2 size={11} className="animate-spin" />
              : <ShieldCheck size={11} />}
            Reliability all
          </button>
        </div>
      </div>

      <div className="overflow-x-auto">
        <div className="min-w-[760px]">
          <div className={`grid ${gridCols} items-center gap-3 border-b border-border-subtle/40 px-3 py-2 font-mono-sm text-[8px] font-semibold uppercase tracking-wide text-on-surface-variant`}>
            <span>#</span>
            <span>Model</span>
            <span>Fit</span>
            <div className="grid grid-cols-5">
              <span className="px-1 text-center">Chat</span>
              <span className="px-1 text-center">ASL</span>
              <span className="px-1 text-center">MCP</span>
              <span className="px-1 text-center">Fn</span>
              <span className="px-1 text-center">Safety</span>
            </div>
            <span>Cases</span>
            <span>P95</span>
            <span />
          </div>
          {rankedModels.map((model, index) => {
            const evaluation = evaluations[model.id];
            const result = comparableResult(evaluation);
            const expanded = expandedId === model.id;
            return (
              <div key={model.id} className="border-b border-border-subtle/30 last:border-b-0">
                <div
                  role="button"
                  tabIndex={0}
                  aria-expanded={expanded}
                  onClick={() => setExpandedId(expanded ? null : model.id)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      setExpandedId(expanded ? null : model.id);
                    }
                  }}
                  className={`grid ${gridCols} cursor-pointer items-center gap-3 px-3 py-2.5 transition-colors hover:bg-surface-container/40 ${expanded ? 'bg-surface-container/40' : ''}`}
                >
                  <span className="font-display text-[13px] font-semibold text-primary/70">
                    {String(index + 1).padStart(2, '0')}
                  </span>
                  <div className="min-w-0">
                    <div className="truncate font-mono-sm text-[11px] font-semibold text-on-surface">
                      {model.label}
                    </div>
                    <ComparisonFreshness model={model} evaluation={evaluation} />
                  </div>
                  <EvaluationBadge evaluation={evaluation} />
                  {result ? (
                    <>
                      <MiniCapabilityTape result={result} />
                      <span className="font-mono-sm text-[10px] text-on-surface">
                        {result.summary.passedCases}/{result.summary.totalCases}
                      </span>
                      <span className="font-mono-sm text-[10px] text-on-surface-variant">
                        {formatLatency(result.summary.latencyP95Ms)}
                      </span>
                    </>
                  ) : (
                    <div className="col-span-3 font-mono-sm text-[9px] uppercase text-on-surface-variant/70">
                      {evaluation?.status === 'RUNNING'
                        ? `${evaluation.completedCases}/${evaluation.totalCases} cases complete`
                        : 'Expand to test'}
                    </div>
                  )}
                  <ChevronDown
                    size={14}
                    className={`justify-self-end text-on-surface-variant transition-transform ${expanded ? 'rotate-180' : ''}`}
                  />
                </div>
                {expanded && (
                  <div className="border-t border-border-subtle/30 bg-surface-base/50 px-3 py-3">
                    <ModelTestDetail
                      model={model}
                      evaluation={evaluation}
                      benchmarkAction={benchmarkAction}
                      batchMode={batchMode}
                      managingModels={managingModels}
                      onRunEvaluation={onRunEvaluation}
                      onCancelEvaluation={onCancelEvaluation}
                    />
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

function compareModels(
  left: AiModel,
  right: AiModel,
  evaluations: Record<string, AiModelEvaluationDTO>,
) {
  const leftEvaluation = evaluations[left.id];
  const rightEvaluation = evaluations[right.id];
  const leftResult = comparableResult(leftEvaluation);
  const rightResult = comparableResult(rightEvaluation);
  const leftTier = comparisonTier(leftEvaluation, leftResult !== null);
  const rightTier = comparisonTier(rightEvaluation, rightResult !== null);
  if (leftTier !== rightTier) return leftTier - rightTier;
  if (leftResult && rightResult) {
    const recommendationDifference = recommendationRank(
      rightResult.summary.recommendation,
    ) - recommendationRank(leftResult.summary.recommendation);
    if (recommendationDifference !== 0) return recommendationDifference;
    if (rightResult.summary.casePassRate !== leftResult.summary.casePassRate) {
      return rightResult.summary.casePassRate - leftResult.summary.casePassRate;
    }
    if (rightResult.capabilities.safety !== leftResult.capabilities.safety) {
      return rightResult.capabilities.safety - leftResult.capabilities.safety;
    }
    if (leftResult.summary.latencyP95Ms !== rightResult.summary.latencyP95Ms) {
      return leftResult.summary.latencyP95Ms - rightResult.summary.latencyP95Ms;
    }
  }
  return left.label.localeCompare(right.label);
}

function comparableResult(evaluation?: AiModelEvaluationDTO) {
  return evaluation?.status === 'COMPLETED' && evaluation.result
    ? evaluation.result
    : null;
}

function comparisonTier(evaluation: AiModelEvaluationDTO | undefined, hasResult: boolean) {
  if (hasResult && !evaluation?.stale) return 0;
  if (hasResult) return 1;
  if (evaluation?.status === 'RUNNING') return 2;
  return 3;
}

function recommendationRank(recommendation: string) {
  if (recommendation === 'RECOMMENDED') return 3;
  if (recommendation === 'LIMITED') return 2;
  return 1;
}

function ComparisonFreshness({
  model,
  evaluation,
}: {
  model: AiModel;
  evaluation?: AiModelEvaluationDTO;
}) {
  const provider = model.provider === 'api' ? 'Cloud' : 'Local';
  if (evaluation?.status === 'RUNNING') {
    return (
      <div className="mt-0.5 flex items-center gap-1 font-mono-sm text-[8px] uppercase text-primary">
        <Activity size={9} className="animate-pulse" />
        Testing · {provider}
      </div>
    );
  }
  if (evaluation?.stale) {
    return (
      <div className="mt-0.5 flex items-center gap-1 font-mono-sm text-[8px] uppercase text-status-warning">
        <RefreshCw size={9} />
        Prompt changed · {provider}
      </div>
    );
  }
  return (
    <div className="mt-0.5 font-mono-sm text-[8px] uppercase text-on-surface-variant">
      {evaluation?.status === 'COMPLETED' ? 'Current' : 'Not tested'} · {provider}
    </div>
  );
}

function MiniCapabilityTape({
  result,
}: {
  result: NonNullable<AiModelEvaluationDTO['result']>;
}) {
  const capabilities = [
    result.capabilities.chat,
    result.capabilities.asl,
    result.capabilities.mcp,
    result.capabilities.functions,
    result.capabilities.safety,
  ];
  return (
    <div className="grid grid-cols-5 overflow-hidden rounded-DEFAULT border border-border-subtle/50">
      {capabilities.map((score, index) => (
        <span
          key={index}
          className={`border-r border-border-subtle/40 px-1 py-1.5 text-center font-mono-sm text-[9px] font-semibold last:border-r-0 ${capabilityTone(score)}`}
        >
          {Math.round(score * 100)}
        </span>
      ))}
    </div>
  );
}

function EvaluationBadge({ evaluation }: { evaluation?: AiModelEvaluationDTO }) {
  if (!evaluation) {
    return (
      <span className="rounded-DEFAULT border border-border-subtle/70 px-2 py-0.5 font-mono-sm text-[9px] font-semibold uppercase text-on-surface-variant">
        Not tested
      </span>
    );
  }
  if (evaluation.status === 'RUNNING') {
    return (
      <span className="flex items-center gap-1 rounded-DEFAULT bg-primary/15 px-2 py-0.5 font-mono-sm text-[9px] font-semibold uppercase text-primary">
        <Activity size={10} className="animate-pulse" />
        {evaluation.cancelRequested ? 'Stopping' : 'Testing'}
      </span>
    );
  }
  if (evaluation.status === 'FAILED') {
    return <EvaluationBadgeLabel label="Run failed" tone="error" />;
  }
  if (evaluation.status === 'CANCELLED') {
    return <EvaluationBadgeLabel label="Cancelled" tone="muted" />;
  }
  if (evaluation.stale) {
    return <EvaluationBadgeLabel label="Stale" tone="warning" />;
  }
  const recommendation = evaluation.result?.summary.recommendation;
  if (recommendation === 'RECOMMENDED') {
    return <EvaluationBadgeLabel label="Recommended" tone="success" />;
  }
  if (recommendation === 'FAILED') {
    return <EvaluationBadgeLabel label="Failed" tone="error" />;
  }
  return <EvaluationBadgeLabel label="Limited" tone="warning" />;
}

function EvaluationBadgeLabel({
  label,
  tone,
}: {
  label: string;
  tone: 'success' | 'warning' | 'error' | 'muted';
}) {
  const toneClass = {
    success: 'bg-status-success/15 text-status-success',
    warning: 'bg-status-warning/15 text-status-warning',
    error: 'bg-status-error/15 text-status-error',
    muted: 'border border-border-subtle/70 text-on-surface-variant',
  }[tone];
  return (
    <span className={`rounded-DEFAULT px-2 py-0.5 font-mono-sm text-[9px] font-semibold uppercase ${toneClass}`}>
      {label}
    </span>
  );
}

function EvaluationProgress({ evaluation }: { evaluation: AiModelEvaluationDTO }) {
  const progress = evaluation.totalCases > 0
    ? Math.min(100, (evaluation.completedCases / evaluation.totalCases) * 100)
    : 0;
  return (
    <div className="mt-3">
      <div className="mb-1 flex items-center justify-between font-mono-sm text-[10px] text-on-surface-variant">
        <span>{evaluation.cancelRequested ? 'Stopping after current model call' : `Running ${evaluation.mode === 'RELIABILITY' ? 'reliability' : 'quick'} test`}</span>
        <span>{evaluation.completedCases}/{evaluation.totalCases}</span>
      </div>
      <div className="h-1 overflow-hidden rounded-full bg-surface-container">
        <div
          className="h-full rounded-full bg-primary transition-[width] duration-500"
          style={{ width: `${progress}%` }}
        />
      </div>
    </div>
  );
}

function CapabilityTape({ evaluation }: { evaluation: AiModelEvaluationDTO }) {
  const result = evaluation.result;
  if (!result) return null;
  const capabilities = [
    ['Chat', result.capabilities.chat],
    ['ASL', result.capabilities.asl],
    ['MCP', result.capabilities.mcp],
    ['Functions', result.capabilities.functions],
    ['Safety', result.capabilities.safety],
  ] as const;
  const testedAt = evaluation.finishedAt || evaluation.startedAt;
  return (
    <div className="mt-3 overflow-hidden rounded-DEFAULT border border-border-subtle/60">
      <div className="grid grid-cols-5 divide-x divide-border-subtle/50 bg-surface-container">
        {capabilities.map(([label, score]) => (
          <div key={label} className={`px-1.5 py-2 text-center ${capabilityTone(score)}`}>
            <div className="font-mono-sm text-[9px] font-semibold uppercase tracking-wide">{label}</div>
            <div className="mt-0.5 font-display text-[13px] font-semibold">{Math.round(score * 100)}%</div>
          </div>
        ))}
      </div>
      <div className="flex flex-wrap items-center justify-between gap-2 bg-surface-container-lowest px-2.5 py-1.5 font-mono-sm text-[9px] text-on-surface-variant">
        <span>{result.summary.passedCases}/{result.summary.totalCases} cases · p95 {formatLatency(result.summary.latencyP95Ms)} · {formatStructuredOutputMode(result.structuredOutputMode)}</span>
        <span>{result.suiteId} · {evaluation.mode === 'RELIABILITY' ? '3 passes' : '1 pass'} · {formatTestedAt(testedAt)}</span>
      </div>
    </div>
  );
}

function JudgeSummaryPanel({ judge }: { judge: AiModelEvaluationJudgeSummary }) {
  const tone = judge.verdict === 'STRONG'
    ? 'text-status-success'
    : judge.verdict === 'MIXED'
      ? 'text-status-warning'
      : judge.verdict === 'WEAK'
        ? 'text-status-error'
        : 'text-on-surface-variant';
  const issues = [
    ...judge.failures,
    ...judge.errors.map((error) => `judge error — ${error}`),
  ];
  return (
    <div className="mt-2 rounded-DEFAULT border border-border-subtle/60 bg-surface-container-lowest px-2.5 py-2">
      <div className="flex flex-wrap items-center justify-between gap-2 font-mono-sm text-[10px]">
        <span className={`flex items-center gap-1.5 font-semibold uppercase ${tone}`}>
          <Scale size={11} />
          Judge {judge.verdict.toLowerCase()}
        </span>
        <span className="text-on-surface-variant">
          {judge.scoredCases === 0
            ? `no scored cases (${judge.erroredCases} judge ${judge.erroredCases === 1 ? 'error' : 'errors'})`
            : `${Math.round(judge.passRate * 100)}% pass · mean ${judge.meanScore.toFixed(1)}/5 · by ${judge.displayName}`}
        </span>
      </div>
      {issues.length > 0 && (
        <ul className="mt-1.5 space-y-1 border-t border-border-subtle/40 pt-1.5 text-[10px] leading-4 text-on-surface-variant">
          {issues.slice(0, 4).map((issue) => (
            <li key={issue} className="truncate" title={issue}>{issue}</li>
          ))}
          {issues.length > 4 && <li>+{issues.length - 4} more</li>}
        </ul>
      )}
    </div>
  );
}

function PerCaseResults({
  observations,
  defaultOpen = false,
  live = false,
}: {
  observations: AiModelEvaluationObservation[];
  defaultOpen?: boolean;
  live?: boolean;
}) {
  const uniqueCases = new Set(observations.map((observation) => observation.caseId)).size;
  const showRepetition = observations.length > uniqueCases;
  const [open, setOpen] = useState(defaultOpen);
  return (
    <details
      open={open}
      onToggle={(event) => setOpen((event.target as HTMLDetailsElement).open)}
      className="group mt-2 overflow-hidden rounded-DEFAULT border border-border-subtle/60 bg-surface-container-lowest"
    >
      <summary className="flex cursor-pointer list-none items-center gap-2 px-2.5 py-1.5 font-mono-sm text-[10px] font-semibold uppercase tracking-wide text-on-surface-variant transition-colors hover:text-primary [&::-webkit-details-marker]:hidden">
        {live
          ? <Activity size={12} className="animate-pulse text-primary" />
          : <ListChecks size={12} className="text-primary" />}
        {live ? 'Live case results' : 'Per-case results'}
        <span className="text-on-surface-variant/70">({observations.length})</span>
        <ChevronDown size={13} className="ml-auto transition-transform group-open:rotate-180" />
      </summary>
      <div className="max-h-[340px] space-y-2 overflow-y-auto border-t border-border-subtle/50 p-2">
        {observations.map((observation, index) => (
          <CaseResult
            key={`${observation.caseId}-${observation.repetition}-${index}`}
            observation={observation}
            showRepetition={showRepetition}
          />
        ))}
      </div>
    </details>
  );
}

function CaseResult({
  observation,
  showRepetition,
}: {
  observation: AiModelEvaluationObservation;
  showRepetition: boolean;
}) {
  const { caseId, category, instruction, repetition, latencyMs, passed, metrics, response, error, judge } = observation;
  const metricEntries = Object.entries(metrics);
  const failedWithDetail = metricEntries.filter(([, metric]) => !metric.passed && metric.detail);
  return (
    <div className="rounded-DEFAULT border border-border-subtle/50 bg-surface-base p-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <span className={`flex h-3.5 w-3.5 shrink-0 items-center justify-center rounded-full ${passed ? 'bg-status-success/20 text-status-success' : 'bg-status-error/20 text-status-error'}`}>
          {passed ? <Check size={9} /> : <X size={9} />}
        </span>
        <span className="font-mono-sm text-[11px] font-semibold text-on-surface">{caseId}</span>
        <span className="rounded-DEFAULT bg-primary/10 px-1.5 py-0.5 font-mono-sm text-[9px] uppercase text-primary">{category}</span>
        {showRepetition && (
          <span className="font-mono-sm text-[9px] text-on-surface-variant">rep {repetition}</span>
        )}
        <span className="ml-auto font-mono-sm text-[9px] text-on-surface-variant">{formatLatency(latencyMs)}</span>
        {judge?.score != null && (
          <span className="font-mono-sm text-[9px] text-on-surface-variant">judge {judge.score}/5</span>
        )}
      </div>

      <div className="mt-2 rounded-DEFAULT border border-border-subtle/40 bg-surface-container-lowest px-2 py-1.5">
        <div className="font-mono-sm text-[8px] font-semibold uppercase tracking-wide text-on-surface-variant/70">Prompt sent</div>
        <div className="mt-0.5 text-[11px] leading-4 text-on-surface">{instruction}</div>
      </div>

      {metricEntries.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1">
          {metricEntries.map(([name, metric]) => (
            <span
              key={name}
              title={metric.detail || undefined}
              className={`rounded-DEFAULT px-1.5 py-0.5 font-mono-sm text-[9px] ${metric.passed ? 'bg-status-success/10 text-status-success' : 'bg-status-error/10 text-status-error'}`}
            >
              {name}
            </span>
          ))}
        </div>
      )}

      {failedWithDetail.length > 0 && (
        <ul className="mt-1.5 space-y-0.5 text-[10px] leading-4 text-status-error/90">
          {failedWithDetail.map(([name, metric]) => (
            <li key={name} className="truncate" title={metric.detail}>{name}: {metric.detail}</li>
          ))}
        </ul>
      )}

      {response && (
        <div className="mt-2 rounded-DEFAULT border border-border-subtle/40 bg-surface-container-lowest px-2 py-1.5">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 font-mono-sm text-[9px] text-on-surface-variant">
            <span>stage: <span className="text-on-surface">{response.stage}</span></span>
            {response.hasAsl && <span className="text-status-success">has ASL</span>}
            {response.proposedFunctionCount > 0 && <span>fn: {response.proposedFunctionCount}</span>}
            {response.proposedMcpCount > 0 && <span>mcp: {response.proposedMcpCount}</span>}
            {response.validationIssueCount > 0 && (
              <span className="text-status-warning">{response.validationIssueCount} validation issue(s)</span>
            )}
          </div>
          {response.message && (
            <div className="mt-1 text-[10px] leading-4 text-on-surface-variant">{response.message}</div>
          )}
          {response.validationIssues && response.validationIssues.length > 0 && (
            <ul className="mt-1 space-y-0.5 text-[9px] leading-4 text-status-warning/90">
              {response.validationIssues.slice(0, 4).map((issue, index) => (
                <li key={index} className="truncate" title={issue}>{issue}</li>
              ))}
              {response.validationIssues.length > 4 && (
                <li>+{response.validationIssues.length - 4} more</li>
              )}
            </ul>
          )}
        </div>
      )}

      {response?.rawModelReply && response.rawModelReply.trim().length > 0 && (
        <details className="group/raw mt-2 overflow-hidden rounded-DEFAULT border border-border-subtle/40 bg-surface-container-lowest">
          <summary className="flex cursor-pointer list-none items-center gap-1.5 px-2 py-1 font-mono-sm text-[8px] font-semibold uppercase tracking-wide text-on-surface-variant/70 transition-colors hover:text-primary [&::-webkit-details-marker]:hidden">
            <Code2 size={11} className="text-primary" />
            Model output
            <ChevronDown size={11} className="ml-auto transition-transform group-open/raw:rotate-180" />
          </summary>
          <pre className="max-h-[240px] overflow-auto whitespace-pre-wrap break-words border-t border-border-subtle/40 px-2 py-1.5 font-mono-sm text-[10px] leading-4 text-on-surface">
            {response.rawModelReply}
          </pre>
        </details>
      )}

      {error && (
        <p className="mt-2 text-[10px] leading-4 text-status-error">{error}</p>
      )}
    </div>
  );
}

function capabilityTone(score: number) {
  if (score >= 0.8) return 'bg-status-success/10 text-status-success';
  if (score >= 0.5) return 'bg-status-warning/10 text-status-warning';
  return 'bg-status-error/10 text-status-error';
}

function formatLatency(milliseconds: number) {
    if (milliseconds < 1000) return `${milliseconds}ms`;
    return `${(milliseconds / 1000).toFixed(milliseconds >= 10000 ? 0 : 1)}s`;
}

function formatStructuredOutputMode(
  mode: NonNullable<AiModelEvaluationDTO['result']>['structuredOutputMode'],
) {
  if (mode === 'STRICT_JSON_SCHEMA') return 'strict schema';
  if (mode === 'JSON_SCHEMA') return 'JSON schema';
  if (mode === 'JSON_OBJECT') return 'JSON object';
  if (mode === 'PROMPT_ONLY') return 'prompt only';
  return 'output unknown';
}

function formatTestedAt(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? 'unknown time'
    : date.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}
