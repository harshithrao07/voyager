import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  Activity,
  Box,
  Braces,
  ChevronRight,
  CheckCircle2,
  CircleSlash,
  Clock3,
  Copy,
  Cpu,
  Database,
  FileCode2,
  FileText,
  Layers3,
  Loader2,
  Network,
  Plus,
  Power,
  RefreshCw,
  Search,
  Settings,
  Sparkles,
  Terminal,
  Zap,
} from 'lucide-react';
import {
  activateFunctionVersion,
  createFunctionDefinition,
  createFunctionVersion,
  listFunctionInvocations,
  listFunctionLanguages,
  listFunctionVersions,
  listFunctions,
  updateFunctionDefinition,
  type FunctionDefinitionDTO,
  type FunctionInvocationDTO,
  type FunctionLanguageDTO,
  type FunctionVersionDTO,
} from '../api';
import { FunctionInvocationsList } from '../components/functions/FunctionInvocationsList';
import { FunctionTestPanel } from '../components/functions/FunctionTestPanel';
import { FunctionVersionWorkbench, isModernLanguage } from '../components/functions/FunctionVersionWorkbench';

type DetailTab = 'overview' | 'versions' | 'test' | 'invocations' | 'settings';

const slug = /^[a-z0-9][a-z0-9-]*$/;
const fieldClass =
  'h-9 w-full rounded-lg border border-border-subtle bg-surface-container-lowest px-3 text-[12px] text-on-surface outline-none transition-colors placeholder:text-on-surface-variant/45 focus:border-primary/60';
const selectFieldClass = `${fieldClass} py-0 leading-[34px]`;
const labelClass = 'mb-1.5 flex items-center gap-1.5 text-[11px] text-on-surface-variant';

function FunctionMetric({ label, value, tone }: { label: string; value: string | number; tone: string }) {
  return (
    <div className="border-b border-border-subtle px-4 py-3 md:border-b-0 md:border-r">
      <div className="text-label-caps font-label-caps text-on-surface-variant">{label}</div>
      <div className={`mt-1 font-mono-sm text-[14px] font-semibold ${tone}`}>{value}</div>
    </div>
  );
}

function functionResource(fn: Pick<FunctionDefinitionDTO, 'namespace' | 'name'>, version?: number | null) {
  return `function://${fn.namespace || 'namespace'}/${fn.name || 'name'}${version ? `@v${version}` : '@latest'}`;
}

function formatUpdated(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Updated recently';
  const diffMs = Date.now() - date.getTime();
  const minute = 60 * 1000;
  const hour = 60 * minute;
  const day = 24 * hour;
  if (diffMs < minute) return 'Updated just now';
  if (diffMs < hour) return `Updated ${Math.floor(diffMs / minute)}m ago`;
  if (diffMs < day) return `Updated ${Math.floor(diffMs / hour)}h ago`;
  return `Updated ${Math.floor(diffMs / day)}d ago`;
}

function formatDateTime(value?: string | null) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

function formatLimitKb(value: number) {
  if (value >= 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(1)} GB`;
  if (value >= 1024) return `${(value / 1024).toFixed(1)} MB`;
  return `${value} KB`;
}

function formatOutputBytes(value: number) {
  if (value >= 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(1)} MB`;
  if (value >= 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${value} B`;
}

function valueOrDefault(value?: string | null) {
  const trimmed = value?.trim();
  return trimmed ? trimmed : 'Default';
}

type FunctionsPageProps = {
  onWorkbenchModeChange?: (active: boolean) => void;
};

export function FunctionsPage({ onWorkbenchModeChange }: FunctionsPageProps) {
  const [functions, setFunctions] = useState<FunctionDefinitionDTO[]>([]);
  const [languages, setLanguages] = useState<FunctionLanguageDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [search, setSearch] = useState('');
  const [versions, setVersions] = useState<FunctionVersionDTO[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [recentInvocations, setRecentInvocations] = useState<FunctionInvocationDTO[]>([]);
  const [recentInvocationsLoading, setRecentInvocationsLoading] = useState(false);
  const [activeTab, setActiveTab] = useState<DetailTab>('overview');
  const [showVersionWorkbench, setShowVersionWorkbench] = useState(false);
  const [invocationsRefreshKey, setInvocationsRefreshKey] = useState(0);
  const [busy, setBusy] = useState(false);

  const languageName = useMemo(() => {
    const map = new Map(languages.map((language) => [language.id, language.name]));
    return (id: number) => map.get(id) || `Language ${id}`;
  }, [languages]);

  const selected = functions.find((fn) => fn.id === selectedId) || null;
  const activeFunctionCount = functions.filter((fn) => fn.status === 'ENABLED').length;
  const pausedFunctionCount = functions.filter((fn) => fn.status === 'DISABLED').length;
  const draftFunctionCount = functions.filter((fn) => fn.activeVersion == null).length;
  const filteredFunctions = functions.filter((fn) => {
    const query = search.trim().toLowerCase();
    if (!query) return true;
    return [fn.namespace, fn.name, fn.description || '']
      .some((value) => value.toLowerCase().includes(query));
  });

  useEffect(() => {
    onWorkbenchModeChange?.(creating || showVersionWorkbench);
  }, [creating, onWorkbenchModeChange, showVersionWorkbench]);

  useEffect(() => () => onWorkbenchModeChange?.(false), [onWorkbenchModeChange]);

  const reloadFunctions = useCallback(() => (
    listFunctions().then((data) => {
      setFunctions(data);
      return data;
    })
  ), []);

  useEffect(() => {
    let active = true;
    Promise.all([listFunctions(), listFunctionLanguages().catch(() => [])])
      .then(([fns, langs]) => {
        if (!active) return;
        setFunctions(fns);
        setLanguages(langs);
      })
      .catch((err: Error) => { if (active) setError(err.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  const reloadVersions = useCallback((functionId: string) => {
    setVersionsLoading(true);
    return listFunctionVersions(functionId)
      .then((data) => setVersions(data))
      .catch(() => setVersions([]))
      .finally(() => setVersionsLoading(false));
  }, []);

  const reloadRecentInvocations = useCallback((functionId: string) => {
    setRecentInvocationsLoading(true);
    return listFunctionInvocations(functionId)
      .then((data) => setRecentInvocations(data))
      .catch(() => setRecentInvocations([]))
      .finally(() => setRecentInvocationsLoading(false));
  }, []);

  const openFunction = (id: string) => {
    setActiveTab('overview');
    setShowVersionWorkbench(false);
    reloadVersions(id);
    reloadRecentInvocations(id);
  };

  const selectFunction = (id: string) => {
    setCreating(false);
    setSelectedId(id);
    openFunction(id);
  };

  const handleFunctionCreated = async (functionId: string) => {
    await reloadFunctions();
    setCreating(false);
    setSelectedId(functionId);
    openFunction(functionId);
  };

  const handleVersionCreated = async () => {
    setShowVersionWorkbench(false);
    setActiveTab('overview');
    if (selectedId) {
      await Promise.all([reloadVersions(selectedId), reloadFunctions()]);
    }
  };

  const activate = async (version: number) => {
    if (!selectedId || busy) return;
    setBusy(true);
    try {
      await activateFunctionVersion(selectedId, version);
      await Promise.all([reloadVersions(selectedId), reloadFunctions()]);
    } finally {
      setBusy(false);
    }
  };

  const toggleStatus = async (fn: FunctionDefinitionDTO) => {
    if (busy) return;
    setBusy(true);
    try {
      await updateFunctionDefinition(fn.id, {
        namespace: fn.namespace,
        name: fn.name,
        description: fn.description,
        status: fn.status === 'ENABLED' ? 'DISABLED' : 'ENABLED',
      });
      await reloadFunctions();
    } finally {
      setBusy(false);
    }
  };

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center gap-2 bg-[#06101b] text-[12px] text-on-surface-variant">
        <Loader2 size={16} className="animate-spin" /> Loading functions...
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex h-full items-center justify-center bg-[#06101b] px-6 text-center text-[12px] text-status-error">
        {error}
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col bg-surface-lowest text-on-surface">
      {!creating && (
      <div className="glass-shell grid shrink-0 grid-cols-1 border-b border-border-subtle bg-surface-base md:grid-cols-4">
        <FunctionMetric label="Total functions" value={functions.length} tone="text-primary" />
        <FunctionMetric label="Active functions" value={activeFunctionCount} tone="text-status-success" />
        <FunctionMetric label="Paused functions" value={pausedFunctionCount} tone="text-on-surface-variant" />
        <FunctionMetric label="Draft functions" value={draftFunctionCount} tone="text-status-info" />
      </div>
      )}

      <div className="flex min-h-0 flex-1 bg-[linear-gradient(180deg,#081421,#050b13)]">
        {!creating && (
        <aside className="flex w-[320px] shrink-0 flex-col border-r border-border-subtle bg-[linear-gradient(180deg,rgba(10,23,37,0.98),rgba(4,10,18,0.98))]">
        <div className="border-b border-border-subtle p-3">
          <button
            type="button"
            onClick={() => {
              setCreating(true);
              setSelectedId(null);
              setVersions([]);
              setRecentInvocations([]);
              setShowVersionWorkbench(false);
            }}
            className="flex h-10 w-full items-center justify-center gap-2 rounded-lg border border-primary/45 bg-primary text-[13px] font-semibold text-on-primary shadow-[0_16px_42px_rgba(242,121,90,0.24)] transition-colors hover:bg-primary-fixed-dim"
          >
            <Plus size={16} />
            New Function
          </button>

          <div className="mt-3 flex gap-2">
            <label className="flex h-9 min-w-0 flex-1 items-center gap-2 rounded-lg border border-border-subtle bg-surface-container-lowest px-3">
              <Search size={14} className="text-on-surface-variant" />
              <input
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder="Search functions..."
                className="min-w-0 flex-1 border-none bg-transparent text-[12px] text-on-surface outline-none placeholder:text-on-surface-variant/55"
              />
            </label>
            <button
              type="button"
              onClick={() => reloadFunctions()}
              className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-border-subtle text-on-surface-variant transition-colors hover:border-primary/45 hover:text-on-surface"
              title="Refresh functions"
            >
              <RefreshCw size={14} />
            </button>
          </div>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto p-3">
          {filteredFunctions.length === 0 ? (
            <div className="rounded-lg border border-dashed border-border-subtle px-3 py-8 text-center text-[12px] text-on-surface-variant">
              No functions yet.
            </div>
          ) : (
            <div className="space-y-2">
              {filteredFunctions.map((fn) => {
                const active = fn.id === selectedId && !creating;
                return (
                  <button
                    key={fn.id}
                    type="button"
                    onClick={() => selectFunction(fn.id)}
                    className={`group w-full rounded-lg border p-3 text-left transition-colors ${
                      active
                        ? 'border-primary/60 bg-[linear-gradient(145deg,rgba(242,121,90,0.14),rgba(14,23,34,0.78))]'
                        : 'border-transparent hover:border-border-subtle hover:bg-surface-container-lowest/70'
                    }`}
                  >
                    <div className="flex items-center gap-2">
                      <span className={`h-2 w-2 rounded-full ${fn.status === 'ENABLED' ? 'bg-secondary' : 'bg-on-surface-variant/45'}`} />
                      <span className="min-w-0 flex-1 truncate text-[13px] font-semibold text-on-surface">{fn.name}</span>
                      <span className="rounded-md border border-border-subtle bg-surface-container-low px-1.5 py-0.5 font-mono-sm text-[10px] text-primary">
                        {fn.activeVersion ? `v${fn.activeVersion}` : '-'}
                      </span>
                    </div>
                    <div className="mt-1 pl-4 font-mono-sm text-[11px] text-on-surface-variant">
                      <div className="truncate">{fn.namespace} / {fn.name}</div>
                      <div className="mt-1">{formatUpdated(fn.updatedAt)}</div>
                    </div>
                  </button>
                );
              })}
            </div>
          )}
        </div>
        </aside>
        )}

        <main className="flex min-h-0 min-w-0 flex-1 w-full">
        {creating ? (
          <FunctionCreateWorkbench
            languages={languages}
            onCancel={() => setCreating(false)}
            onDone={handleFunctionCreated}
          />
        ) : !selected ? (
          <EmptyFunctionState onCreate={() => setCreating(true)} />
        ) : showVersionWorkbench ? (
          <FunctionVersionWorkbench
            resourceLabel={functionResource(selected, (selected.activeVersion || 0) + 1)}
            languages={languages}
            metadata={<SelectedMetadata selected={selected} nextVersion={(selected.activeVersion || 0) + 1} />}
            onCancel={() => setShowVersionWorkbench(false)}
            onSaveDraft={async (request) => {
              await createFunctionVersion(selected.id, request);
              await handleVersionCreated();
            }}
            publishLabel="Publish version"
            onPublish={async (request) => {
              await createFunctionVersion(selected.id, request);
              await handleVersionCreated();
            }}
          />
        ) : (
          <FunctionDetail
            selected={selected}
            versions={versions}
            versionsLoading={versionsLoading}
            recentInvocations={recentInvocations}
            recentInvocationsLoading={recentInvocationsLoading}
            activeTab={activeTab}
            setActiveTab={setActiveTab}
            languageName={languageName}
            busy={busy}
            onNewVersion={() => setShowVersionWorkbench(true)}
            onActivate={activate}
            onToggleStatus={() => toggleStatus(selected)}
            testPanel={(
              versions.length === 0 ? (
                <EmptyPanel message="Publish a version first, then test it here." />
              ) : (
                <FunctionTestPanel
                  functionId={selected.id}
                  versions={versions}
                  activeVersion={selected.activeVersion}
                  onInvoked={() => {
                    setInvocationsRefreshKey((key) => key + 1);
                    void reloadRecentInvocations(selected.id);
                  }}
                />
              )
            )}
            invocationsPanel={<FunctionInvocationsList functionId={selected.id} refreshKey={invocationsRefreshKey} />}
          />
        )}
        </main>
      </div>
    </div>
  );
}

function FunctionCreateWorkbench({
  languages,
  onCancel,
  onDone,
}: {
  languages: FunctionLanguageDTO[];
  onCancel: () => void;
  onDone: (functionId: string) => void;
}) {
  const [namespace, setNamespace] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [languageId, setLanguageId] = useState('');

  const modernLanguages = languages.filter((language) => isModernLanguage(language.name));
  const sortedLanguages = [...(modernLanguages.length > 0 ? modernLanguages : languages)]
    .sort((a, b) => a.name.localeCompare(b.name));
  const validDefinition = slug.test(namespace) && slug.test(name);
  const metadata = (
    <div className="grid gap-3 lg:grid-cols-3">
      <MetaInput label="Namespace" value={namespace} onChange={setNamespace} placeholder="notifications" />
      <MetaInput label="Name" value={name} onChange={setName} placeholder="send-email" />
      <label>
        <span className={labelClass}>Language</span>
        <select
          value={languageId}
          onChange={(event) => setLanguageId(event.target.value)}
          className={selectFieldClass}
        >
          <option value="">Select language</option>
          {sortedLanguages.map((language) => (
            <option key={language.id} value={language.id}>{language.name}</option>
          ))}
        </select>
      </label>
    </div>
  );

  const createShell = async () => {
    if (!validDefinition) {
      throw new Error('Enter a valid namespace and name.');
    }
    return createFunctionDefinition({
      namespace,
      name,
      description: description.trim() || undefined,
    });
  };

  return (
    <FunctionVersionWorkbench
      resourceLabel={`function://${namespace || 'namespace'}/${name || 'name'}@v1`}
      languages={languages}
      languageId={languageId}
      onLanguageChange={setLanguageId}
      description={description}
      onDescriptionChange={setDescription}
      metadata={metadata}
      onCancel={onCancel}
      saveDraftLabel="Save draft"
      saveDraftDisabled={!validDefinition || !languageId}
      onSaveDraft={async (request) => {
        const created = await createShell();
        await createFunctionVersion(created.id, request);
        onDone(created.id);
      }}
      publishDisabled={!validDefinition}
      publishLabel="Publish version"
      onPublish={async (request) => {
        const created = await createShell();
        await createFunctionVersion(created.id, request);
        onDone(created.id);
      }}
    />
  );
}

function FunctionDetail({
  selected,
  versions,
  versionsLoading,
  recentInvocations,
  recentInvocationsLoading,
  activeTab,
  setActiveTab,
  languageName,
  busy,
  onNewVersion,
  onActivate,
  onToggleStatus,
  testPanel,
  invocationsPanel,
}: {
  selected: FunctionDefinitionDTO;
  versions: FunctionVersionDTO[];
  versionsLoading: boolean;
  recentInvocations: FunctionInvocationDTO[];
  recentInvocationsLoading: boolean;
  activeTab: DetailTab;
  setActiveTab: (tab: DetailTab) => void;
  languageName: (id: number) => string;
  busy: boolean;
  onNewVersion: () => void;
  onActivate: (version: number) => void;
  onToggleStatus: () => void;
  testPanel: ReactNode;
  invocationsPanel: ReactNode;
}) {
  const sortedVersions = [...versions].sort((left, right) => right.version - left.version);
  const activeVersion = sortedVersions.find((version) => version.version === selected.activeVersion) || null;
  const latestVersion = sortedVersions[0] || null;
  const availableCount = versions.filter((version) => version.status === 'AVAILABLE').length;
  const draftCount = versions.filter((version) => version.status === 'DRAFT').length;
  const archivedCount = versions.filter((version) => version.status === 'ARCHIVED').length;
  const displayVersion = activeVersion || latestVersion;
  const resourceUri = functionResource(selected, selected.activeVersion);
  const sourceModeLabel = displayVersion
    ? displayVersion.sourceMode === 'MULTI_FILE' ? 'Multi-file' : 'Single file'
    : 'No source';
  const statusLabel = selected.status === 'ENABLED' ? 'Enabled' : 'Disabled';
  const networkLabel = displayVersion?.enableNetwork ? 'Network enabled' : 'Network disabled';
  const tabs: Array<{ id: DetailTab; label: string; icon: ReactNode }> = [
    { id: 'overview', label: 'Overview', icon: <FileText size={14} /> },
    { id: 'versions', label: 'Versions', icon: <Layers3 size={14} /> },
    { id: 'test', label: 'Tests', icon: <Sparkles size={14} /> },
    { id: 'invocations', label: 'Invocations', icon: <Activity size={14} /> },
    { id: 'settings', label: 'Settings', icon: <Settings size={14} /> },
  ];
  const copyResource = async () => {
    try {
      await navigator.clipboard.writeText(resourceUri);
    } catch {
      // Clipboard failure should not block the detail view.
    }
  };

  return (
    <div className="flex h-full min-h-0 w-full flex-1 flex-col bg-[radial-gradient(ellipse_at_58%_0%,rgba(242,121,90,0.09),transparent_32%),linear-gradient(180deg,#081421,#050b13_64%,#040912)]">
      <div className="flex h-16 shrink-0 items-center justify-between gap-3 border-b border-border-subtle/80 px-5">
        <div className="flex min-w-0 items-center gap-3">
          <span className="text-[14px] font-semibold text-on-surface-variant">Functions</span>
          <span className="text-on-surface-variant">/</span>
          <span className="min-w-0 truncate text-[14px] font-semibold text-on-surface">{selected.name}</span>
          <span className={`rounded-md border px-2 py-1 text-[11px] font-medium ${
            selected.status === 'ENABLED'
              ? 'border-secondary/35 bg-secondary/10 text-secondary'
              : 'border-border-subtle bg-surface-container-low text-on-surface-variant'
          }`}>
            {statusLabel}
          </span>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <button
            type="button"
            onClick={() => setActiveTab('test')}
            className="flex h-9 items-center gap-2 rounded-lg border border-border-subtle bg-surface-container-lowest/70 px-3 text-[12px] text-on-surface transition-colors hover:border-primary/45 hover:text-primary"
          >
            <Zap size={14} />
            Invoke function
          </button>
          <button
            type="button"
            onClick={onNewVersion}
            className="flex h-9 items-center gap-2 rounded-lg border border-primary bg-primary px-3 text-[12px] font-semibold text-on-primary shadow-[0_16px_42px_rgba(242,121,90,0.24)] transition-colors hover:bg-primary-fixed-dim"
          >
            <Plus size={14} />
            New version
          </button>
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">
        <section className="grid gap-3 xl:grid-cols-4">
          <FunctionSummaryCard
            icon={<Box size={22} />}
            label="Active version"
            value={selected.activeVersion ? `v${selected.activeVersion}` : '-'}
          />
          <FunctionSummaryCard
            icon={<FileText size={22} />}
            label="Source mode"
            value={sourceModeLabel}
          />
          <FunctionSummaryCard
            icon={<Clock3 size={22} />}
            label="Last updated"
            value={formatUpdated(selected.updatedAt).replace('Updated ', '')}
          />
          <FunctionSummaryCard
            icon={<Layers3 size={22} />}
            label="Total versions"
            value={versions.length}
          />
        </section>

        <section className="mt-4 rounded-lg border border-primary/55 bg-[radial-gradient(circle_at_7%_30%,rgba(242,121,90,0.2),transparent_18%),linear-gradient(135deg,rgba(36,28,32,0.84),rgba(8,17,29,0.88)_42%,rgba(5,12,21,0.94))] p-5 shadow-[0_22px_70px_rgba(0,0,0,0.26)]">
          <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_390px]">
            <div className="flex min-w-0 gap-4">
              <div className="flex h-20 w-20 shrink-0 items-center justify-center rounded-full border border-primary/25 bg-primary/10 text-primary shadow-[0_0_40px_rgba(242,121,90,0.18)]">
                <Box size={38} strokeWidth={1.8} />
              </div>
              <div className="min-w-0 pt-1">
                <h2 className="truncate text-[22px] font-semibold tracking-[-0.01em] text-on-surface">{selected.name}</h2>
                <div className="mt-1 font-mono-sm text-[13px] text-on-surface">
                  {selected.namespace} <span className="text-on-surface-variant">/</span> {selected.name}
                </div>
                <p className="mt-2 max-w-3xl text-[13px] leading-5 text-on-surface-variant">
                  {selected.description || 'Reads JSON from stdin and returns JSON through stdout for the next workflow state.'}
                </p>
                <div className="mt-4 flex flex-wrap gap-2">
                  <HeroChip icon={<Terminal size={13} />} label={displayVersion ? languageName(displayVersion.languageId) : 'No runtime'} />
                  <HeroChip icon={<FileCode2 size={13} />} label={sourceModeLabel} />
                  <HeroChip icon={displayVersion?.enableNetwork ? <Network size={13} /> : <CircleSlash size={13} />} label={networkLabel} />
                  <HeroChip icon={<CheckCircle2 size={13} />} label={statusLabel} tone={selected.status === 'ENABLED' ? 'ok' : 'muted'} />
                </div>
              </div>
            </div>
            <div className="flex flex-col justify-center lg:items-end">
              <div className="w-full max-w-[380px]">
                <div className="mb-2 text-[12px] text-on-surface-variant">Function URI</div>
                <button
                  type="button"
                  onClick={copyResource}
                  className="flex h-10 w-full min-w-0 items-center gap-2 rounded-lg border border-border-subtle bg-surface-container-lowest/85 px-3 font-mono-sm text-[12px] text-on-surface transition-colors hover:border-primary/45"
                  title="Copy function URI"
                >
                  <span className="min-w-0 flex-1 truncate text-left">{resourceUri}</span>
                  <Copy size={14} className="shrink-0 text-on-surface-variant" />
                </button>
              </div>
            </div>
          </div>
        </section>

        <section className="mt-4">
          <div className="flex h-12 items-center gap-6 border-b border-border-subtle">
            {tabs.map((tab) => (
                <button
                  key={tab.id}
                  type="button"
                  onClick={() => setActiveTab(tab.id)}
                  className={`flex h-full items-center gap-2 border-b-2 text-[13px] transition-colors ${
                    activeTab === tab.id
                      ? 'border-primary text-primary'
                      : 'border-transparent text-on-surface-variant hover:text-on-surface'
                  }`}
                >
                  {tab.icon}
                  {tab.label}
                </button>
              ))}
          </div>

          <div className="py-4">
            {activeTab === 'overview' && (
              <div className="space-y-4">
                <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
                  <FunctionOverviewDetails
                    selected={selected}
                    draftCount={draftCount}
                    availableCount={availableCount}
                    archivedCount={archivedCount}
                  />
                  <ExecutionProfileCard
                    version={displayVersion}
                    languageName={languageName}
                  />
                </div>
                <VersionsOverviewTable
                  versions={sortedVersions}
                  activeVersion={selected.activeVersion}
                  languageName={languageName}
                  loading={versionsLoading}
                  busy={busy}
                  onActivate={onActivate}
                  onNewVersion={onNewVersion}
                />
                <RecentActivityCard
                  invocations={recentInvocations}
                  loading={recentInvocationsLoading}
                  onViewAll={() => setActiveTab('invocations')}
                />
              </div>
            )}
            {activeTab === 'versions' && (
              <VersionListPanel
                versions={sortedVersions}
                activeVersion={selected.activeVersion}
                languageName={languageName}
                loading={versionsLoading}
                busy={busy}
                onActivate={onActivate}
                onNewVersion={onNewVersion}
              />
            )}
            {activeTab === 'test' && testPanel}
            {activeTab === 'invocations' && invocationsPanel}
            {activeTab === 'settings' && (
              <FunctionSettingsPanel
                selected={selected}
                activeVersion={displayVersion}
                resourceUri={resourceUri}
                busy={busy}
                onToggleStatus={onToggleStatus}
                onCopyResource={copyResource}
                languageName={languageName}
              />
            )}
          </div>
        </section>
      </div>
    </div>
  );
}

function FunctionSummaryCard({ icon, label, value }: { icon: ReactNode; label: string; value: string | number }) {
  return (
    <div className="flex min-h-[96px] items-center gap-4 rounded-lg border border-border-subtle bg-[linear-gradient(145deg,rgba(255,255,255,0.045),rgba(255,255,255,0.015))] px-5 shadow-[inset_0_1px_0_rgba(255,255,255,0.04)]">
      <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl border border-primary/20 bg-primary/10 text-primary shadow-[0_0_32px_rgba(242,121,90,0.12)]">
        {icon}
      </div>
      <div className="min-w-0">
        <div className="text-[12px] text-on-surface-variant">{label}</div>
        <div className="mt-1 truncate text-[18px] font-semibold text-on-surface">{value}</div>
      </div>
    </div>
  );
}

function HeroChip({ icon, label, tone = 'muted' }: { icon: ReactNode; label: string; tone?: 'ok' | 'muted' }) {
  return (
    <span className={`inline-flex h-8 items-center gap-1.5 rounded-md border px-2.5 text-[12px] ${
      tone === 'ok'
        ? 'border-secondary/35 bg-secondary/10 text-secondary'
        : 'border-border-subtle bg-surface-container-lowest/65 text-on-surface-variant'
    }`}>
      {icon}
      {label}
    </span>
  );
}

function FunctionOverviewDetails({
  selected,
  draftCount,
  availableCount,
  archivedCount,
}: {
  selected: FunctionDefinitionDTO;
  draftCount: number;
  availableCount: number;
  archivedCount: number;
}) {
  return (
    <div className="rounded-lg border border-border-subtle bg-surface-container-lowest/55 p-4">
      <div className="mb-4 flex items-center gap-2">
        <Database size={16} className="text-primary" />
        <h3 className="text-[15px] font-semibold text-on-surface">Function details</h3>
      </div>
      <div className="space-y-3">
        <OverviewLine label="Function ID" value={selected.id} mono copyable />
        <OverviewLine label="Namespace" value={selected.namespace} mono />
        <OverviewLine label="Name" value={selected.name} mono />
        <OverviewLine label="Created" value={formatDateTime(selected.createdAt)} />
        <OverviewLine label="Updated" value={formatDateTime(selected.updatedAt)} />
        <OverviewLine label="Draft versions" value={String(draftCount)} />
        <OverviewLine label="Available versions" value={String(availableCount)} />
        <OverviewLine label="Archived versions" value={String(archivedCount)} />
      </div>
    </div>
  );
}

function ExecutionProfileCard({
  version,
  languageName,
}: {
  version: FunctionVersionDTO | null;
  languageName: (id: number) => string;
}) {
  return (
    <div className="rounded-lg border border-border-subtle bg-surface-container-lowest/55 p-4">
      <div className="mb-4 flex items-center gap-2">
        <Cpu size={16} className="text-primary" />
        <h3 className="text-[15px] font-semibold text-on-surface">Execution profile</h3>
        {version && <span className="text-[12px] text-on-surface-variant">(v{version.version})</span>}
      </div>
      {version ? (
        <div className="grid gap-x-8 gap-y-3 md:grid-cols-2">
          <OverviewLine label="Language" value={languageName(version.languageId)} />
          <OverviewLine label="Max output" value={formatOutputBytes(version.maxOutputBytes)} />
          <OverviewLine label="Source mode" value={version.sourceMode === 'MULTI_FILE' ? 'Multi-file' : 'Single file'} />
          <OverviewLine label="Network access" value={version.enableNetwork ? 'Enabled' : 'Disabled'} />
          <OverviewLine label="CPU time" value={`${version.cpuTimeLimitSeconds}s`} />
          <OverviewLine label="Compiler options" value={valueOrDefault(version.compilerOptions)} mono />
          <OverviewLine label="Wall time" value={`${version.wallTimeLimitSeconds}s`} />
          <OverviewLine label="Command arguments" value={valueOrDefault(version.commandLineArguments)} mono />
          <OverviewLine label="Memory" value={formatLimitKb(version.memoryLimitKb)} />
          <OverviewLine label="Max file size" value={formatLimitKb(version.maxFileSizeKb)} />
        </div>
      ) : (
        <EmptyPanel message="No version exists yet. Publish one to see runtime limits and language details." />
      )}
    </div>
  );
}

function OverviewLine({
  label,
  value,
  mono,
  copyable,
}: {
  label: string;
  value: string;
  mono?: boolean;
  copyable?: boolean;
}) {
  const copyValue = async () => {
    if (!copyable) return;
    try {
      await navigator.clipboard.writeText(value);
    } catch {
      // Best effort copy action.
    }
  };

  return (
    <div className="grid min-w-0 grid-cols-[145px_minmax(0,1fr)] items-center gap-3 text-[12px]">
      <div className="text-on-surface-variant">{label}</div>
      <button
        type="button"
        onClick={copyValue}
        className={`min-w-0 truncate text-right text-on-surface ${mono ? 'font-mono-sm' : ''} ${copyable ? 'cursor-copy hover:text-primary' : 'cursor-default'}`}
        title={value}
      >
        {value}
        {copyable && <Copy size={12} className="ml-2 inline text-on-surface-variant" />}
      </button>
    </div>
  );
}

function VersionsOverviewTable({
  versions,
  activeVersion,
  languageName,
  loading,
  busy,
  onActivate,
  onNewVersion,
}: {
  versions: FunctionVersionDTO[];
  activeVersion: number | null;
  languageName: (id: number) => string;
  loading: boolean;
  busy: boolean;
  onActivate: (version: number) => void;
  onNewVersion: () => void;
}) {
  return (
    <div className="rounded-lg border border-border-subtle bg-surface-container-lowest/55 p-4">
      <div className="mb-4 flex items-center justify-between gap-3">
        <h3 className="text-[15px] font-semibold text-on-surface">Versions</h3>
        <button
          type="button"
          onClick={onNewVersion}
          className="flex h-8 items-center gap-1.5 rounded-lg border border-primary bg-primary px-3 text-[12px] font-semibold text-on-primary transition-colors hover:bg-primary-fixed-dim"
        >
          <Plus size={13} />
          Add version
        </button>
      </div>
      <VersionTable
        versions={versions}
        activeVersion={activeVersion}
        languageName={languageName}
        loading={loading}
        busy={busy}
        onActivate={onActivate}
        onNewVersion={onNewVersion}
      />
    </div>
  );
}

function VersionListPanel({
  versions,
  activeVersion,
  languageName,
  loading,
  busy,
  onActivate,
  onNewVersion,
}: {
  versions: FunctionVersionDTO[];
  activeVersion: number | null;
  languageName: (id: number) => string;
  loading: boolean;
  busy: boolean;
  onActivate: (version: number) => void;
  onNewVersion: () => void;
}) {
  return (
    <div className="rounded-lg border border-border-subtle bg-surface-container-lowest/55 p-4">
      <div className="mb-4 flex items-center justify-between gap-3">
        <div>
          <h3 className="text-[15px] font-semibold text-on-surface">Version history</h3>
          <p className="mt-1 text-[12px] text-on-surface-variant">Published and draft runtime snapshots for this function.</p>
        </div>
        <button type="button" onClick={onNewVersion} className="flex h-8 items-center gap-1.5 rounded-lg border border-primary/45 px-3 text-[12px] text-primary hover:bg-primary/10">
          <Plus size={13} />
          Add version
        </button>
      </div>
      <VersionTable
        versions={versions}
        activeVersion={activeVersion}
        languageName={languageName}
        loading={loading}
        busy={busy}
        onActivate={onActivate}
        onNewVersion={onNewVersion}
      />
    </div>
  );
}

function VersionTable({
  versions,
  activeVersion,
  languageName,
  loading,
  busy,
  onActivate,
  onNewVersion,
}: {
  versions: FunctionVersionDTO[];
  activeVersion: number | null;
  languageName: (id: number) => string;
  loading: boolean;
  busy: boolean;
  onActivate: (version: number) => void;
  onNewVersion: () => void;
}) {
  if (loading) {
    return (
      <div className="flex items-center gap-2 text-[12px] text-on-surface-variant">
        <Loader2 size={14} className="animate-spin" /> Loading versions...
      </div>
    );
  }

  if (versions.length === 0) {
    return <EmptyPanel message="No versions yet. Publish one to make this function runnable." actionLabel="Publish first version" onAction={onNewVersion} />;
  }

  return (
    <div className="overflow-x-auto">
      <div className="min-w-[860px]">
        <div className="grid grid-cols-[110px_120px_160px_130px_140px_minmax(190px,1fr)_34px] border-b border-border-subtle px-3 pb-2 font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant">
          <div>Version</div>
          <div>Status</div>
          <div>Language</div>
          <div>Source mode</div>
          <div>Updated</div>
          <div>Resource limits</div>
          <div />
        </div>
        <div className="space-y-2 pt-2">
          {versions.map((version) => {
            const active = activeVersion === version.version;
            return (
              <div
                key={version.id}
                className={`grid grid-cols-[110px_120px_160px_130px_140px_minmax(190px,1fr)_34px] items-center rounded-lg border px-3 py-3 text-[12px] ${
                  active
                    ? 'border-primary/55 bg-[linear-gradient(145deg,rgba(242,121,90,0.12),rgba(255,255,255,0.025))]'
                    : 'border-border-subtle bg-surface-container-lowest/50'
                }`}
              >
                <div className="font-mono-sm text-[13px] font-semibold text-on-surface">v{version.version}</div>
                <div>
                  <StatusChip
                    label={active ? 'Active' : version.status.toLowerCase()}
                    tone={active || version.status === 'AVAILABLE' ? 'ok' : version.status === 'DRAFT' ? 'info' : 'muted'}
                  />
                </div>
                <div className="truncate text-on-surface">{languageName(version.languageId)}</div>
                <div className="text-on-surface-variant">{version.sourceMode === 'MULTI_FILE' ? 'Multi-file' : 'Single file'}</div>
                <div className="text-on-surface-variant">{formatUpdated(version.updatedAt).replace('Updated ', '')}</div>
                <div className="truncate text-on-surface-variant" title={`${version.cpuTimeLimitSeconds}s CPU / ${formatLimitKb(version.memoryLimitKb)} RAM / ${formatOutputBytes(version.maxOutputBytes)} output`}>
                  {version.cpuTimeLimitSeconds}s CPU / {formatLimitKb(version.memoryLimitKb)} RAM / {formatOutputBytes(version.maxOutputBytes)} output
                </div>
                <div className="flex justify-end">
                  {!active && version.status === 'AVAILABLE' ? (
                    <button
                      type="button"
                      onClick={() => onActivate(version.version)}
                      disabled={busy}
                      className="rounded-md border border-border-subtle px-2 py-1 text-[11px] text-on-surface-variant hover:border-primary/45 hover:text-primary disabled:opacity-50"
                    >
                      Activate
                    </button>
                  ) : (
                    <ChevronRight size={15} className="text-on-surface-variant" />
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

function RecentActivityCard({
  invocations,
  loading,
  onViewAll,
}: {
  invocations: FunctionInvocationDTO[];
  loading: boolean;
  onViewAll: () => void;
}) {
  const recent = invocations.slice(0, 3);
  return (
    <div className="rounded-lg border border-border-subtle bg-surface-container-lowest/55 p-4">
      <div className="mb-4 flex items-center justify-between gap-3">
        <h3 className="text-[15px] font-semibold text-on-surface">Recent activity</h3>
        <button
          type="button"
          onClick={onViewAll}
          className="flex h-8 items-center gap-1.5 rounded-lg border border-border-subtle px-3 text-[12px] text-on-surface-variant hover:border-primary/45 hover:text-on-surface"
        >
          View all invocations
          <ChevronRight size={13} />
        </button>
      </div>
      {loading ? (
        <div className="flex items-center gap-2 text-[12px] text-on-surface-variant">
          <Loader2 size={14} className="animate-spin" /> Loading recent activity...
        </div>
      ) : recent.length === 0 ? (
        <div className="rounded-lg border border-dashed border-border-subtle px-3 py-6 text-center text-[12px] text-on-surface-variant">
          No invocations yet. Run a test or trigger this function from a workflow.
        </div>
      ) : (
        <div className="divide-y divide-border-subtle">
          {recent.map((invocation) => (
            <button
              key={invocation.id}
              type="button"
              onClick={onViewAll}
              className="grid w-full grid-cols-[minmax(180px,1fr)_120px_120px_120px_24px] items-center gap-4 py-3 text-left text-[12px] hover:text-primary"
            >
              <div className="flex min-w-0 items-center gap-3">
                <span className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full border ${
                  invocation.status === 'SUCCEEDED'
                    ? 'border-secondary/30 bg-secondary/10 text-secondary'
                    : invocation.status === 'FAILED'
                      ? 'border-status-error/30 bg-status-error/10 text-status-error'
                      : 'border-status-info/30 bg-status-info/10 text-status-info'
                }`}>
                  <CheckCircle2 size={14} />
                </span>
                <div className="min-w-0">
                  <div className="truncate text-on-surface">Invocation {invocation.status.toLowerCase()}</div>
                  <div className="font-mono-sm text-[11px] text-on-surface-variant">v{invocation.version}</div>
                </div>
              </div>
              <TinyInline label="Duration" value={invocation.durationMs != null ? `${invocation.durationMs} ms` : '-'} />
              <TinyInline label="Memory" value={invocation.memoryKb != null ? formatLimitKb(invocation.memoryKb) : '-'} />
              <TinyInline label="Started" value={formatUpdated(invocation.startedAt).replace('Updated ', '')} />
              <ChevronRight size={15} className="justify-self-end text-on-surface-variant" />
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function TinyInline({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <div className="text-[11px] text-on-surface-variant">{label}</div>
      <div className="mt-1 truncate text-[12px] text-on-surface">{value}</div>
    </div>
  );
}

function FunctionSettingsPanel({
  selected,
  activeVersion,
  resourceUri,
  busy,
  onToggleStatus,
  onCopyResource,
  languageName,
}: {
  selected: FunctionDefinitionDTO;
  activeVersion: FunctionVersionDTO | null;
  resourceUri: string;
  busy: boolean;
  onToggleStatus: () => void;
  onCopyResource: () => void;
  languageName: (id: number) => string;
}) {
  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)]">
      <div className="rounded-lg border border-border-subtle bg-surface-container-lowest/55 p-4">
        <div className="mb-4 flex items-center gap-2">
          <Settings size={16} className="text-primary" />
          <h3 className="text-[15px] font-semibold text-on-surface">Function settings</h3>
        </div>
        <div className="space-y-3">
          <OverviewLine label="Status" value={selected.status === 'ENABLED' ? 'Enabled' : 'Disabled'} />
          <OverviewLine label="Resource" value={resourceUri} mono copyable />
          <OverviewLine label="Description" value={selected.description || 'No description'} />
          <button
            type="button"
            onClick={onToggleStatus}
            disabled={busy}
            className={`mt-2 flex h-9 items-center gap-2 rounded-lg border px-3 text-[12px] transition-colors disabled:opacity-50 ${
              selected.status === 'ENABLED'
                ? 'border-secondary/35 bg-secondary/10 text-secondary'
                : 'border-border-subtle text-on-surface-variant hover:text-on-surface'
            }`}
          >
            <Power size={14} />
            {selected.status === 'ENABLED' ? 'Disable function' : 'Enable function'}
          </button>
          <button
            type="button"
            onClick={onCopyResource}
            className="flex h-9 items-center gap-2 rounded-lg border border-border-subtle px-3 text-[12px] text-on-surface-variant hover:border-primary/45 hover:text-primary"
          >
            <Copy size={14} />
            Copy function URI
          </button>
        </div>
      </div>
      <div className="rounded-lg border border-border-subtle bg-surface-container-lowest/55 p-4">
        <div className="mb-4 flex items-center gap-2">
          <Braces size={16} className="text-primary" />
          <h3 className="text-[15px] font-semibold text-on-surface">Active version settings</h3>
        </div>
        {activeVersion ? (
          <VersionConfigGrid version={activeVersion} languageName={languageName} />
        ) : (
          <EmptyPanel message="No active version yet. Add a version, then activate it here." />
        )}
      </div>
    </div>
  );
}

function StatusChip({ label, tone }: { label: string; tone: 'ok' | 'info' | 'muted' }) {
  const toneClass = tone === 'ok'
    ? 'border-secondary/35 bg-secondary/10 text-secondary'
    : tone === 'info'
      ? 'border-status-info/35 bg-status-info/10 text-status-info'
      : 'border-border-subtle bg-surface-container-low text-on-surface-variant';
  return (
    <span className={`rounded-md border px-1.5 py-0.5 font-mono-sm text-[9px] uppercase tracking-[0.06em] ${toneClass}`}>
      {label}
    </span>
  );
}

function VersionConfigGrid({ version, languageName }: { version: FunctionVersionDTO; languageName: (id: number) => string }) {
  return (
    <div className="grid gap-2 md:grid-cols-2 xl:grid-cols-4">
      <TinyDetail label="Version ID" value={version.id} mono />
      <TinyDetail label="Function ID" value={version.functionId} mono />
      <TinyDetail label="Language" value={languageName(version.languageId)} />
      <TinyDetail label="Source mode" value={version.sourceMode === 'MULTI_FILE' ? 'Multi-file' : 'Single file'} />
      <TinyDetail label="Source code" value={version.hasSourceCode ? 'Present' : 'Missing'} />
      <TinyDetail label="Additional files" value={version.hasAdditionalFiles ? 'Present' : 'None'} />
      <TinyDetail label="Compiler options" value={valueOrDefault(version.compilerOptions)} mono />
      <TinyDetail label="Command arguments" value={valueOrDefault(version.commandLineArguments)} mono />
      <TinyDetail label="CPU time" value={`${version.cpuTimeLimitSeconds}s`} />
      <TinyDetail label="Wall time" value={`${version.wallTimeLimitSeconds}s`} />
      <TinyDetail label="Memory" value={formatLimitKb(version.memoryLimitKb)} />
      <TinyDetail label="Max file size" value={formatLimitKb(version.maxFileSizeKb)} />
      <TinyDetail label="Max output" value={formatOutputBytes(version.maxOutputBytes)} />
      <TinyDetail label="Network access" value={version.enableNetwork ? 'Enabled' : 'Disabled'} />
      <TinyDetail label="Created" value={formatDateTime(version.createdAt)} />
      <TinyDetail label="Updated" value={formatDateTime(version.updatedAt)} />
    </div>
  );
}

function TinyDetail({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="min-w-0 rounded-lg border border-border-subtle bg-surface-container-lowest px-3 py-2">
      <div className="font-mono-sm text-[9px] uppercase tracking-[0.07em] text-on-surface-variant">{label}</div>
      <div className={`mt-1 min-w-0 truncate text-[11px] text-on-surface ${mono ? 'font-mono-sm' : ''}`} title={value}>
        {value}
      </div>
    </div>
  );
}

function SelectedMetadata({ selected, nextVersion }: { selected: FunctionDefinitionDTO; nextVersion: number }) {
  return (
    <div className="grid gap-3 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_120px]">
      <ReadOnlyMeta label="Namespace" value={selected.namespace} />
      <ReadOnlyMeta label="Name" value={selected.name} />
      <ReadOnlyMeta label="Version" value={`v${nextVersion}`} />
      <div className="lg:col-span-3">
        <span className={labelClass}>Description</span>
        <div className="min-h-[42px] rounded-lg border border-border-subtle bg-surface-container-lowest px-3 py-2 text-[12px] leading-[18px] text-on-surface-variant">
          {selected.description || 'No description.'}
        </div>
      </div>
    </div>
  );
}

function EmptyFunctionState({ onCreate }: { onCreate: () => void }) {
  return (
    <div className="flex h-full w-full flex-1 items-center justify-center bg-[radial-gradient(ellipse_at_50%_20%,rgba(242,121,90,0.1),transparent_38%),linear-gradient(180deg,#081421,#050b13)] p-6 text-center">
      <div className="max-w-[420px]">
        <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-xl border border-primary/35 bg-primary/10 text-primary">
          <FileCode2 size={22} />
        </div>
        <h2 className="mt-4 text-[18px] font-semibold text-on-surface">Create a function runtime</h2>
        <p className="mt-2 text-[12px] leading-5 text-on-surface-variant">
          Add code that workflows can call with a function resource URI. Functions read JSON from stdin and write JSON to stdout.
        </p>
        <button
          type="button"
          onClick={onCreate}
          className="mt-5 inline-flex h-9 items-center gap-2 rounded-lg border border-primary bg-primary px-4 text-[12px] font-semibold text-on-primary transition-colors hover:bg-primary-fixed-dim"
        >
          <Plus size={14} />
          New Function
        </button>
      </div>
    </div>
  );
}

function EmptyPanel({ message, actionLabel, onAction }: { message: string; actionLabel?: string; onAction?: () => void }) {
  return (
    <div className="flex min-h-[160px] flex-col items-center justify-center rounded-lg border border-dashed border-border-subtle bg-surface-container-lowest/50 text-center text-[12px] text-on-surface-variant">
      <Clock3 size={18} className="mb-2" />
      {message}
      {actionLabel && onAction && (
        <button type="button" onClick={onAction} className="mt-3 rounded-lg border border-primary/45 px-3 py-1.5 text-[12px] text-primary hover:bg-primary/10">
          {actionLabel}
        </button>
      )}
    </div>
  );
}

function MetaInput({
  label,
  value,
  onChange,
  placeholder,
  readOnly,
}: {
  label: string;
  value: string;
  onChange?: (value: string) => void;
  placeholder?: string;
  readOnly?: boolean;
}) {
  return (
    <label>
      <span className={labelClass}>{label}</span>
      <input
        value={value}
        onChange={(event) => onChange?.(event.target.value)}
        placeholder={placeholder}
        readOnly={readOnly}
        className={`${fieldClass} ${readOnly ? 'text-on-surface-variant' : ''}`}
      />
    </label>
  );
}

function ReadOnlyMeta({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <span className={labelClass}>{label}</span>
      <div className="flex h-9 items-center rounded-lg border border-border-subtle bg-surface-container-lowest px-3 font-mono-sm text-[12px] text-on-surface">
        {value}
      </div>
    </div>
  );
}
