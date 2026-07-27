import { useCallback, useEffect, useMemo, useState, type Dispatch, type SetStateAction } from 'react';
import { Activity, BarChart3, Bot, Check, ChevronDown, Copy, Gauge, Globe2, KeyRound, Link, Loader2, Monitor, Play, Plus, Power, RefreshCw, Scale, ShieldCheck, Sparkles, Square, Trash2, X } from 'lucide-react';
import {
  cancelAiModelEvaluation,
  listLatestAiModelEvaluations,
  startAiModelEvaluation,
  type AiModelEvaluationDTO,
  type AiModelEvaluationJudgeSummary,
  type AiModelEvaluationMode,
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
};

const settingsNavItems: Array<{
  id: ModelSettingsTab;
  label: string;
  icon: typeof Plus;
}> = [
  { id: 'add', label: 'Add Models', icon: Plus },
  { id: 'added', label: 'Added Models', icon: Check },
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
}: Props) {
  const [evaluations, setEvaluations] = useState<Record<string, AiModelEvaluationDTO>>({});
  const [benchmarkAction, setBenchmarkAction] = useState<string | null>(null);
  const [batchMode, setBatchMode] = useState<AiModelEvaluationMode | null>(null);
  const [benchmarkError, setBenchmarkError] = useState<string | null>(null);
  const [judgeModelId, setJudgeModelId] = useState<string>('');
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
    if (settingsTab !== 'added') return;
    void refreshEvaluations().catch((error) => {
      setBenchmarkError(error instanceof Error ? error.message : 'Could not load model evaluations.');
    });
  }, [refreshEvaluations, settingsTab, endpointGroups.length]);

  const hasRunningEvaluation = useMemo(
    () => Object.values(evaluations).some((evaluation) => evaluation.status === 'RUNNING'),
    [evaluations],
  );

  useEffect(() => {
    if (settingsTab !== 'added' || !hasRunningEvaluation) return;
    const timer = window.setInterval(() => {
      void refreshEvaluations().catch(() => undefined);
    }, 2000);
    return () => window.clearInterval(timer);
  }, [hasRunningEvaluation, refreshEvaluations, settingsTab]);

  const runEvaluation = async (model: AiModel, mode: AiModelEvaluationMode) => {
    if (model.provider === 'api' || judgeModel?.provider === 'api') {
      const turns = mode === 'RELIABILITY' ? 21 : 7;
      const judgeNote = judgeModel
        ? ` Each case adds one LLM-judge call to ${judgeModel.label}.`
        : '';
      const confirmed = window.confirm(
        `Run ${turns} benchmark cases against ${model.label}?${judgeNote} Your model provider may charge for every generation and repair call.`,
      );
      if (!confirmed) return;
    }
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
      const confirmed = window.confirm(
        `Run ${casesPerModel * candidates.length} benchmark cases across ${candidates.length} models, including ${cloudModels.length} cloud ${cloudModels.length === 1 ? 'model' : 'models'}?${judgeNote} Cloud providers may charge for every generation and repair call.`,
      );
      if (!confirmed) return;
    }

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

  return (
    <div className="pointer-events-auto fixed inset-0 z-[70] flex items-center justify-center bg-black/55 p-6">
      <div className="flex max-h-[88vh] w-full max-w-6xl flex-col overflow-hidden rounded-lg border border-primary/20 bg-surface-lowest shadow-[0_24px_90px_rgba(0,0,0,0.65)]">
        <div className="flex h-16 shrink-0 items-center justify-between px-6 shadow-[inset_0_-1px_rgba(255,255,255,0.08)]">
          <div className="flex items-center gap-2 font-display text-[15px] font-semibold text-primary">
            <Sparkles size={18} />
            Settings
          </div>
          <button
            type="button"
            onClick={onClose}
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
  | 'expandedEndpoint'
  | 'setExpandedEndpoint'
  | 'managingModels'
  | 'onCopyEndpoint'
  | 'onUpdateEndpointEnabled'
  | 'onDeleteEndpointModels'
  | 'onUpdateSingleModelEnabled'
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
          <Check size={18} className="text-primary" />
          <div>
            <div className="flex items-baseline gap-2">
              <h3 className="font-headline-md text-headline-md font-semibold text-primary">Added Models</h3>
              <span className="font-mono-sm text-[12px] text-on-surface-variant">(Endpoints)</span>
            </div>
            <p className="mt-1 text-body-sm text-on-surface-variant">Run the same capability tape against every enabled model before choosing one for workflow generation.</p>
          </div>
        </div>
      </div>

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
          className="h-8 min-w-[220px] rounded-DEFAULT border border-primary/30 bg-surface-container px-2 font-mono-sm text-[11px] text-on-surface outline-none focus:border-primary/60"
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

      {models.length > 0 && (
        <ModelComparisonBoard
          models={models}
          evaluations={evaluations}
          batchMode={batchMode}
          disabled={managingModels || hasRunningEvaluation}
          onRunAllEvaluations={onRunAllEvaluations}
        />
      )}

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
                      const evaluation = evaluations[model.id];
                      const actionPending = benchmarkAction === model.id;
                      const batchPending = batchMode !== null;
                      return (
                        <div
                          key={model.id}
                          className="rounded-DEFAULT border border-border-subtle/50 bg-surface-container-lowest p-3"
                        >
                          <div className="flex items-center justify-between gap-3">
                            <button
                              type="button"
                              onClick={() => onUpdateSingleModelEnabled(model, !enabled)}
                              disabled={managingModels || evaluation?.status === 'RUNNING'}
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
                            <EvaluationBadge evaluation={evaluation} />
                          </div>

                          {evaluation?.status === 'RUNNING' && (
                            <EvaluationProgress evaluation={evaluation} />
                          )}

                          {evaluation?.result && (
                            <CapabilityTape evaluation={evaluation} />
                          )}

                          {evaluation?.result?.judge && (
                            <JudgeSummaryPanel judge={evaluation.result.judge} />
                          )}

                          {evaluation?.status === 'FAILED' && evaluation.errorMessage && (
                            <p className="mt-2 text-[11px] leading-4 text-status-error">
                              {evaluation.errorMessage}
                            </p>
                          )}

                          <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-border-subtle/40 pt-2">
                            <div className="flex items-center gap-1.5 text-[10px] text-on-surface-variant">
                              <Gauge size={12} />
                              <span>Quick: 7 cases</span>
                              <span className="text-border-muted">•</span>
                              <span>Reliability: 21 cases</span>
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
                                    title={enabled ? 'Run one pass of workflow-ai-v1' : 'Enable this model before testing it'}
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
                                    title={enabled ? 'Run three passes of workflow-ai-v1' : 'Enable this model before testing it'}
                                    className="flex h-7 items-center gap-1.5 rounded-DEFAULT bg-primary px-2.5 font-mono-sm text-[10px] font-semibold uppercase text-surface-lowest transition-colors hover:bg-primary-fixed disabled:cursor-not-allowed disabled:opacity-40"
                                  >
                                    <ShieldCheck size={11} />
                                    Reliability
                                  </button>
                                </>
                              )}
                            </div>
                          </div>
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

function ModelComparisonBoard({
  models,
  evaluations,
  batchMode,
  disabled,
  onRunAllEvaluations,
}: {
  models: AiModel[];
  evaluations: Record<string, AiModelEvaluationDTO>;
  batchMode: AiModelEvaluationMode | null;
  disabled: boolean;
  onRunAllEvaluations: (mode: AiModelEvaluationMode) => void;
}) {
  const enabledModels = models.filter((model) => model.enabled !== false);
  const rankedModels = [...models].sort((left, right) => compareModels(
    left,
    right,
    evaluations,
  ));
  const controlsDisabled = disabled || enabledModels.length === 0 || batchMode !== null;

  return (
    <div className="mt-4 overflow-hidden rounded-lg border border-primary/20 bg-surface-container-lowest">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border-subtle/50 px-4 py-3">
        <div className="flex items-start gap-3">
          <BarChart3 size={17} className="mt-0.5 text-primary" />
          <div>
            <div className="font-display text-[13px] font-semibold text-primary">Model ranking</div>
            <div className="mt-0.5 text-[10px] text-on-surface-variant">
              Current results rank first; stale and untested models stay visible.
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
          <div className="grid grid-cols-[28px_minmax(150px,1.3fr)_90px_minmax(235px,1.5fr)_66px_62px] items-center gap-3 border-b border-border-subtle/40 px-3 py-2 font-mono-sm text-[8px] font-semibold uppercase tracking-wide text-on-surface-variant">
            <span>#</span>
            <span>Model</span>
            <span>Fit</span>
            <span>Chat · ASL · MCP · Fn · Safety</span>
            <span>Cases</span>
            <span>P95</span>
          </div>
          {rankedModels.map((model, index) => {
            const evaluation = evaluations[model.id];
            const result = comparableResult(evaluation);
            return (
              <div
                key={model.id}
                className="grid grid-cols-[28px_minmax(150px,1.3fr)_90px_minmax(235px,1.5fr)_66px_62px] items-center gap-3 border-b border-border-subtle/30 px-3 py-2.5 last:border-b-0"
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
                      : 'Run a benchmark to compare'}
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
