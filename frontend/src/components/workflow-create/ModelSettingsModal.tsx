import type { Dispatch, RefObject, SetStateAction } from 'react';
import { Bot, Check, ChevronDown, Copy, Globe2, KeyRound, Link, Loader2, Monitor, MoreHorizontal, Play, Plus, Power, RefreshCw, RotateCcw, Search, Sparkles, Trash2, X } from 'lucide-react';
import { cloudProviderPreset, cloudProviderPresets } from './modelProviders';
import type { AiModel, EndpointModelGroup, ModelSettingsTab } from './types';

type Props = {
  settingsTab: ModelSettingsTab;
  onSettingsTabChange: (tab: ModelSettingsTab) => void;
  onClose: () => void;
  fieldClass: string;
  localActionsRef: RefObject<HTMLDivElement | null>;
  localActionsOpen: boolean;
  setLocalActionsOpen: Dispatch<SetStateAction<boolean>>;
  discoverEndpoint: string;
  onDiscoverEndpointChange: (value: string) => void;
  onDiscoverModels: (endpointOverride?: string) => void;
  onScanForServers: () => void;
  onTestLocalEndpoint: () => void;
  addingModel: boolean;
  testingModel: boolean;
  discoveringModels: boolean;
  showLocalCredentialRef: boolean;
  setShowLocalCredentialRef: Dispatch<SetStateAction<boolean>>;
  localCredentialRef: string;
  onLocalCredentialRefChange: (value: string) => void;
  localEndpointNeedsDockerHint: boolean;
  modelActionMessage: string | null;
  modelActionSuccess: boolean | null;
  discoveredModelNames: string[];
  apiActionsRef: RefObject<HTMLDivElement | null>;
  apiActionsOpen: boolean;
  setApiActionsOpen: Dispatch<SetStateAction<boolean>>;
  apiProvider: string;
  onApiProviderChange: (value: string) => void;
  apiEndpoint: string;
  onApiEndpointChange: (value: string) => void;
  apiModelName: string;
  onApiModelNameChange: (value: string) => void;
  apiCredentialRef: string;
  onApiCredentialRefChange: (value: string) => void;
  onTestApiEndpoint: () => void;
  onScanApiModels: () => void;
  onResetApiProvider: () => void;
  onAddApiModel: () => void;
  apiActionMessage: string | null;
  apiActionSuccess: boolean | null;
  apiDiscoveredModelNames: string[];
  endpointGroups: EndpointModelGroup[];
  expandedEndpoint: string | null;
  setExpandedEndpoint: Dispatch<SetStateAction<string | null>>;
  managingModels: boolean;
  onProbeAllEndpoints: () => void;
  onCopyEndpoint: (endpoint: string) => void;
  onUpdateEndpointEnabled: (endpoint: string, enabled: boolean) => void;
  onDeleteEndpointModels: (endpoint: string) => void;
  onRefreshEndpointModels: (endpoint: string) => void;
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
  localActionsRef,
  localActionsOpen,
  setLocalActionsOpen,
  discoverEndpoint,
  onDiscoverEndpointChange,
  onDiscoverModels,
  onScanForServers,
  onTestLocalEndpoint,
  addingModel,
  testingModel,
  discoveringModels,
  showLocalCredentialRef,
  setShowLocalCredentialRef,
  localCredentialRef,
  onLocalCredentialRefChange,
  localEndpointNeedsDockerHint,
  modelActionMessage,
  modelActionSuccess,
  discoveredModelNames,
  apiActionsRef,
  apiActionsOpen,
  setApiActionsOpen,
  apiProvider,
  onApiProviderChange,
  apiEndpoint,
  onApiEndpointChange,
  apiModelName,
  onApiModelNameChange,
  apiCredentialRef,
  onApiCredentialRefChange,
  onTestApiEndpoint,
  onScanApiModels,
  onResetApiProvider,
  onAddApiModel,
  apiActionMessage,
  apiActionSuccess,
  apiDiscoveredModelNames,
  endpointGroups,
  expandedEndpoint,
  setExpandedEndpoint,
  managingModels,
  onProbeAllEndpoints,
  onCopyEndpoint,
  onUpdateEndpointEnabled,
  onDeleteEndpointModels,
  onRefreshEndpointModels,
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
                  localActionsRef={localActionsRef}
                  localActionsOpen={localActionsOpen}
                  setLocalActionsOpen={setLocalActionsOpen}
                  discoverEndpoint={discoverEndpoint}
                  onDiscoverEndpointChange={onDiscoverEndpointChange}
                  onDiscoverModels={onDiscoverModels}
                  onScanForServers={onScanForServers}
                  onTestLocalEndpoint={onTestLocalEndpoint}
                  addingModel={addingModel}
                  testingModel={testingModel}
                  discoveringModels={discoveringModels}
                  showLocalCredentialRef={showLocalCredentialRef}
                  setShowLocalCredentialRef={setShowLocalCredentialRef}
                  localCredentialRef={localCredentialRef}
                  onLocalCredentialRefChange={onLocalCredentialRefChange}
                  localEndpointNeedsDockerHint={localEndpointNeedsDockerHint}
                  modelActionMessage={modelActionMessage}
                  modelActionSuccess={modelActionSuccess}
                  discoveredModelNames={discoveredModelNames}
                />

                <AddApiModelsSection
                  fieldClass={fieldClass}
                  apiActionsRef={apiActionsRef}
                  apiActionsOpen={apiActionsOpen}
                  setApiActionsOpen={setApiActionsOpen}
                  apiProvider={apiProvider}
                  onApiProviderChange={onApiProviderChange}
                  apiEndpoint={apiEndpoint}
                  onApiEndpointChange={onApiEndpointChange}
                  apiModelName={apiModelName}
                  onApiModelNameChange={onApiModelNameChange}
                  apiCredentialRef={apiCredentialRef}
                  onApiCredentialRefChange={onApiCredentialRefChange}
                  onTestApiEndpoint={onTestApiEndpoint}
                  onScanApiModels={onScanApiModels}
                  onResetApiProvider={onResetApiProvider}
                  onAddApiModel={onAddApiModel}
                  apiActionMessage={apiActionMessage}
                  apiActionSuccess={apiActionSuccess}
                  apiDiscoveredModelNames={apiDiscoveredModelNames}
                  addingModel={addingModel}
                  testingModel={testingModel}
                  discoveringModels={discoveringModels}
                />
              </>
            )}

            {settingsTab === 'added' && (
              <AddedModelsSection
                endpointGroups={endpointGroups}
                expandedEndpoint={expandedEndpoint}
                setExpandedEndpoint={setExpandedEndpoint}
                managingModels={managingModels}
                onProbeAllEndpoints={onProbeAllEndpoints}
                onCopyEndpoint={onCopyEndpoint}
                onUpdateEndpointEnabled={onUpdateEndpointEnabled}
                onDeleteEndpointModels={onDeleteEndpointModels}
                onRefreshEndpointModels={onRefreshEndpointModels}
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
  localActionsRef,
  localActionsOpen,
  setLocalActionsOpen,
  discoverEndpoint,
  onDiscoverEndpointChange,
  onDiscoverModels,
  onScanForServers,
  onTestLocalEndpoint,
  addingModel,
  testingModel,
  discoveringModels,
  showLocalCredentialRef,
  setShowLocalCredentialRef,
  localCredentialRef,
  onLocalCredentialRefChange,
  localEndpointNeedsDockerHint,
  modelActionMessage,
  modelActionSuccess,
  discoveredModelNames,
}: Pick<Props,
  | 'localActionsRef'
  | 'localActionsOpen'
  | 'setLocalActionsOpen'
  | 'discoverEndpoint'
  | 'onDiscoverEndpointChange'
  | 'onDiscoverModels'
  | 'onScanForServers'
  | 'onTestLocalEndpoint'
  | 'addingModel'
  | 'testingModel'
  | 'discoveringModels'
  | 'showLocalCredentialRef'
  | 'setShowLocalCredentialRef'
  | 'localCredentialRef'
  | 'onLocalCredentialRefChange'
  | 'localEndpointNeedsDockerHint'
  | 'modelActionMessage'
  | 'modelActionSuccess'
  | 'discoveredModelNames'
>) {
  return (
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
            onClick={onTestLocalEndpoint}
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
                onClick={onScanForServers}
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
                  onDiscoverEndpointChange('http://host.docker.internal:11434/v1');
                  onDiscoverModels('http://host.docker.internal:11434/v1');
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
                  setShowLocalCredentialRef((visible) => !visible);
                  setLocalActionsOpen(false);
                }}
                className="flex h-10 w-full items-center gap-3 rounded-DEFAULT px-3 text-left text-body-sm font-medium text-primary transition-colors hover:bg-surface-container"
              >
                <KeyRound size={15} />
                Credential reference
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
          onChange={(event) => onDiscoverEndpointChange(event.target.value)}
          onKeyDown={(event) => { if (event.key === 'Enter') onDiscoverModels(); }}
          className="h-10 rounded-DEFAULT border border-primary/30 bg-surface-container px-3 font-mono-sm text-body-sm text-on-surface outline-none placeholder:text-on-surface-variant/55 focus:border-primary/60"
          placeholder="Paste endpoint URL, e.g. http://host.docker.internal:11434/v1"
          disabled={addingModel || discoveringModels}
        />
        <button
          id="discover-models-btn"
          type="button"
          onClick={() => onDiscoverModels()}
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

      {(showLocalCredentialRef || localCredentialRef) && (
        <input
          value={localCredentialRef}
          onChange={(event) => onLocalCredentialRefChange(event.target.value.toUpperCase())}
          className="mt-2 h-9 w-full rounded-DEFAULT border border-primary/30 bg-surface-container px-3 font-mono-sm text-body-sm text-on-surface outline-none placeholder:text-on-surface-variant/55 focus:border-primary/60"
          placeholder="Secret reference, e.g. LOCAL_MODEL_TOKEN"
          spellCheck={false}
          aria-label="Local model credential reference"
        />
      )}

      {modelActionMessage && (
        <div className={`mt-3 text-body-sm ${modelActionSuccess ? 'text-status-success' : 'text-status-error'}`}>
          {modelActionMessage}
          {modelActionSuccess && discoveredModelNames.length > 0 && (
            <span className="ml-2 font-mono-sm text-[11px] opacity-75">
              {discoveredModelNames.join(', ')}
            </span>
          )}
        </div>
      )}
    </section>
  );
}

function AddApiModelsSection({
  fieldClass,
  apiActionsRef,
  apiActionsOpen,
  setApiActionsOpen,
  apiProvider,
  onApiProviderChange,
  apiEndpoint,
  onApiEndpointChange,
  apiModelName,
  onApiModelNameChange,
  apiCredentialRef,
  onApiCredentialRefChange,
  onTestApiEndpoint,
  onScanApiModels,
  onResetApiProvider,
  onAddApiModel,
  apiActionMessage,
  apiActionSuccess,
  apiDiscoveredModelNames,
  addingModel,
  testingModel,
  discoveringModels,
}: Pick<Props,
  | 'fieldClass'
  | 'apiActionsRef'
  | 'apiActionsOpen'
  | 'setApiActionsOpen'
  | 'apiProvider'
  | 'onApiProviderChange'
  | 'apiEndpoint'
  | 'onApiEndpointChange'
  | 'apiModelName'
  | 'onApiModelNameChange'
  | 'apiCredentialRef'
  | 'onApiCredentialRefChange'
  | 'onTestApiEndpoint'
  | 'onScanApiModels'
  | 'onResetApiProvider'
  | 'onAddApiModel'
  | 'apiActionMessage'
  | 'apiActionSuccess'
  | 'apiDiscoveredModelNames'
  | 'addingModel'
  | 'testingModel'
  | 'discoveringModels'
>) {
  const providerPreset = cloudProviderPreset(apiProvider);
  const busy = addingModel || testingModel || discoveringModels;

  return (
    <section className="relative rounded-lg border border-primary/20 bg-surface-base p-4">
      <div className="flex items-start justify-between gap-4 border-b border-border-subtle/40 pb-3">
        <div className="flex items-center gap-3">
          <Globe2 size={18} className="text-primary" />
          <div>
            <h3 className="font-headline-md text-headline-md font-semibold text-primary">Add API Models</h3>
            <p className="mt-1 text-body-sm text-on-surface-variant">Connect a cloud provider endpoint.</p>
          </div>
        </div>
        <div ref={apiActionsRef} className="relative flex shrink-0 gap-2">
          <button
            type="button"
            onClick={onTestApiEndpoint}
            disabled={busy || !apiEndpoint.trim()}
            className="flex h-10 items-center gap-2 rounded-DEFAULT border border-primary/30 px-3 text-body-sm text-primary transition-colors hover:bg-surface-container disabled:cursor-not-allowed disabled:opacity-50"
          >
            {testingModel ? <Loader2 className="animate-spin" size={14} /> : <Play size={14} />}
            Test
          </button>
          <button
            type="button"
            onClick={() => setApiActionsOpen((open) => !open)}
            className="flex h-10 w-10 items-center justify-center rounded-DEFAULT border border-primary/30 text-primary transition-colors hover:bg-surface-container"
            aria-label="Cloud model actions"
            aria-expanded={apiActionsOpen}
          >
            <MoreHorizontal size={16} />
          </button>

          {apiActionsOpen && (
            <div className="absolute right-0 top-[48px] z-20 w-[224px] rounded-lg border border-primary/30 bg-surface-lowest p-2 shadow-[0_18px_50px_rgba(0,0,0,0.45)]">
              <button
                type="button"
                onClick={onScanApiModels}
                disabled={busy || !apiEndpoint.trim()}
                className="flex h-10 w-full items-center gap-3 rounded-DEFAULT px-3 text-left text-body-sm font-medium text-primary transition-colors hover:bg-surface-container disabled:cursor-not-allowed disabled:opacity-50"
              >
                {discoveringModels ? <Loader2 className="animate-spin" size={15} /> : <Search size={15} />}
                Scan models
              </button>
              <button
                type="button"
                onClick={onResetApiProvider}
                disabled={busy}
                className="flex h-10 w-full items-center gap-3 rounded-DEFAULT px-3 text-left text-body-sm font-medium text-primary transition-colors hover:bg-surface-container disabled:cursor-not-allowed disabled:opacity-50"
              >
                <RotateCcw size={15} />
                Reset provider defaults
              </button>
              <button
                type="button"
                onClick={() => {
                  onApiCredentialRefChange('');
                  setApiActionsOpen(false);
                }}
                disabled={!apiCredentialRef || busy}
                className="flex h-10 w-full items-center gap-3 rounded-DEFAULT px-3 text-left text-body-sm font-medium text-primary transition-colors hover:bg-surface-container disabled:cursor-not-allowed disabled:opacity-50"
              >
                <KeyRound size={15} />
                Clear credential reference
              </button>
            </div>
          )}
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
          {addingModel && <Loader2 className="animate-spin" size={14} />}
          Add
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
          value={apiCredentialRef}
          onChange={(event) => onApiCredentialRefChange(event.target.value.toUpperCase())}
          className={`${fieldClass} mt-0 font-mono-sm text-[12px]`}
          placeholder="Secret reference, e.g. OPENAI_API_KEY"
          spellCheck={false}
          disabled={busy}
          aria-label="Cloud credential reference"
        />
      </div>

      <p className="mt-2 text-[11px] leading-5 text-on-surface-variant">
        Only the reference is stored. The backend resolves its value from a mounted secret file or environment variable.
      </p>

      {apiActionMessage && (
        <div className={`mt-2 text-body-sm ${apiActionSuccess ? 'text-status-success' : 'text-status-error'}`}>
          {apiActionMessage}
          {apiActionSuccess && apiDiscoveredModelNames.length > 0 && (
            <span className="ml-2 font-mono-sm text-[11px] opacity-75">
              {apiDiscoveredModelNames.join(', ')}
            </span>
          )}
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
  onProbeAllEndpoints,
  onCopyEndpoint,
  onUpdateEndpointEnabled,
  onDeleteEndpointModels,
  onRefreshEndpointModels,
  onUpdateSingleModelEnabled,
}: Pick<Props,
  | 'endpointGroups'
  | 'expandedEndpoint'
  | 'setExpandedEndpoint'
  | 'managingModels'
  | 'onProbeAllEndpoints'
  | 'onCopyEndpoint'
  | 'onUpdateEndpointEnabled'
  | 'onDeleteEndpointModels'
  | 'onRefreshEndpointModels'
  | 'onUpdateSingleModelEnabled'
>) {
  return (
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
          onClick={onProbeAllEndpoints}
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
                        Secret ref
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
                      <button type="button" onClick={() => onRefreshEndpointModels(group.endpoint)} disabled={managingModels} className="text-primary hover:text-primary-fixed disabled:opacity-50">Refresh</button>
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
