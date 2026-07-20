import type { Dispatch, SetStateAction } from 'react';
import { Bot, Check, ChevronDown, Copy, Globe2, KeyRound, Link, Loader2, Monitor, Plus, Power, Sparkles, Trash2, X } from 'lucide-react';
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
  return (
    <div className="pointer-events-auto fixed inset-0 z-[70] flex items-center justify-center bg-black/55 p-6">
      <div className="flex max-h-[86vh] w-full max-w-4xl flex-col overflow-hidden rounded-lg border border-primary/20 bg-surface-lowest shadow-[0_24px_90px_rgba(0,0,0,0.65)]">
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
            <p className="mt-1 text-body-sm text-on-surface-variant">Endpoints you've connected. Disabled models stay out of chat.</p>
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

                  <div className="max-h-[128px] space-y-1 overflow-y-auto rounded-DEFAULT border border-primary/20 bg-surface-base p-2">
                    {group.models.map((model) => {
                      const enabled = model.enabled !== false;
                      return (
                        <button
                          key={model.id}
                          type="button"
                          onClick={() => onUpdateSingleModelEnabled(model, !enabled)}
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
  );
}
