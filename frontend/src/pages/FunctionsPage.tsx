import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  Activity,
  AlertTriangle,
  Archive,
  ArrowLeft,
  Box,
  Braces,
  CheckCircle2,
  CircleSlash,
  Clock3,
  Copy,
  Database,
  FileCode2,
  FileText,
  Layers3,
  Loader2,
  Network,
  Pencil,
  Plus,
  Power,
  RefreshCw,
  Search,
  Settings,
  Sparkles,
  Terminal,
  Trash2,
} from 'lucide-react';
import {
  activateFunctionVersion,
  createFunctionDefinition,
  createFunctionVersion,
  deleteFunctionDefinition,
  listFunctionLanguages,
  listFunctionVersions,
  listFunctions,
  publishFunctionVersion,
  updateFunctionDefinition,
  updateFunctionVersion,
  updateFunctionVersionMetadata,
  updateFunctionVersionSettings,
  type FunctionDefinitionDTO,
  type FunctionLanguageDTO,
  type FunctionSourceMode,
  type FunctionTestCase,
  type FunctionVersionDTO,
  type FunctionVersionRequest,
  type FunctionVersionSettingsRequest,
} from '../api';
import { FunctionCodeViewer } from '../components/functions/FunctionCodeViewer';
import { FunctionInvocationsList } from '../components/functions/FunctionInvocationsList';
import { FunctionTestPanel } from '../components/functions/FunctionTestPanel';
import { FunctionVersionWorkbench } from '../components/functions/FunctionVersionWorkbench';

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

function languagesForSourceMode(languages: FunctionLanguageDTO[], sourceMode: FunctionSourceMode) {
  return sourceMode === 'MULTI_FILE'
    ? languages.filter((language) => language.multiFileSupported)
    : languages;
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

function functionStatusLabel(status: FunctionDefinitionDTO['status']) {
  if (status === 'ENABLED') return 'Enabled';
  if (status === 'DISABLED') return 'Disabled';
  return 'Archived';
}

function functionStatusTone(status: FunctionDefinitionDTO['status']) {
  if (status === 'ENABLED') return 'ok';
  if (status === 'ARCHIVED') return 'danger';
  return 'muted';
}

function versionSourceFiles(version: FunctionVersionDTO | null) {
  if (!version) return [];
  if (version.sourceMode === 'SINGLE_FILE') {
    return [{
      path: 'main',
      content: version.sourceCode || '',
    }];
  }
  return version.files || [];
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
  const [showArchived, setShowArchived] = useState(false);
  const [versions, setVersions] = useState<FunctionVersionDTO[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [activeTab, setActiveTab] = useState<DetailTab>('overview');
  const [showVersionWorkbench, setShowVersionWorkbench] = useState(false);
  const [newVersionLanguageId, setNewVersionLanguageId] = useState('');
  const [newVersionSourceMode, setNewVersionSourceMode] = useState<FunctionSourceMode>('SINGLE_FILE');
  const [newVersionTestCases, setNewVersionTestCases] = useState<FunctionTestCase[]>([]);
  // When set, the workbench is editing this version rather than creating a
  // fresh one. Drafts are edited in place; for published versions, metadata
  // (note/settings/test cases) saves in place while code or language changes
  // fork a new version.
  const [editingVersion, setEditingVersion] = useState<FunctionVersionDTO | null>(null);
  const invocationsRefreshKey = 0;
  const [busy, setBusy] = useState(false);

  const languageName = useMemo(() => {
    const map = new Map(languages.map((language) => [language.id, language.name]));
    return (id: number) => map.get(id) || `Language ${id}`;
  }, [languages]);

  const selected = functions.find((fn) => fn.id === selectedId) || null;
  const activeFunctionCount = functions.filter((fn) => fn.status === 'ENABLED').length;
  const pausedFunctionCount = functions.filter((fn) => fn.status === 'DISABLED').length;
  const archivedFunctionCount = functions.filter((fn) => fn.status === 'ARCHIVED').length;
  const draftFunctionCount = functions.filter((fn) => fn.activeVersion == null).length;
  const nextVersionNumber = versions.reduce((max, version) => Math.max(max, version.version), 0) + 1;
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
    listFunctions({ includeArchived: showArchived }).then((data) => {
      setFunctions(data);
      return data;
    })
  ), [showArchived]);

  useEffect(() => {
    let active = true;
    Promise.all([
      listFunctions({ includeArchived: showArchived }),
      listFunctionLanguages().catch(() => []),
    ])
      .then(([fns, langs]) => {
        if (!active) return;
        setFunctions(fns);
        setLanguages(langs);
      })
      .catch((err: Error) => { if (active) setError(err.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [showArchived]);

  const reloadVersions = useCallback((functionId: string) => {
    setVersionsLoading(true);
    return listFunctionVersions(functionId)
      .then((data) => setVersions(data))
      .catch(() => setVersions([]))
      .finally(() => setVersionsLoading(false));
  }, []);

  const openFunction = (id: string) => {
    setActiveTab('overview');
    setShowVersionWorkbench(false);
    reloadVersions(id);
  };

  const selectFunction = (id: string) => {
    setCreating(false);
    setSelectedId(id);
    openFunction(id);
  };

  const handleFunctionCreated = (functionId: string) => {
    setCreating(false);
    setSelectedId(functionId);
    openFunction(functionId);
    reloadFunctions().catch((err: Error) => setError(err.message));
  };

  const handleVersionCreated = () => {
    setShowVersionWorkbench(false);
    setEditingVersion(null);
    setNewVersionSourceMode('SINGLE_FILE');
    setActiveTab('overview');
    if (selectedId) {
      Promise.all([reloadVersions(selectedId), reloadFunctions()])
        .catch((err: Error) => setError(err.message));
    }
  };

  const openEditVersion = (version: FunctionVersionDTO) => {
    setNewVersionLanguageId(String(version.languageId));
    setNewVersionSourceMode(version.sourceMode);
    setNewVersionTestCases(version.testCases ?? []);
    setEditingVersion(version);
    setShowVersionWorkbench(true);
  };

  // Persist an edit and close the workbench. Note, execution settings, and
  // test cases save onto the edited version in place; code or language changes
  // fork a new version instead (drafts are the exception: fully mutable).
  const saveEditedVersion = async (
    request: FunctionVersionRequest,
    codeChanged: boolean,
    publish: boolean,
  ) => {
    if (!selectedId || !editingVersion) throw new Error('No version is being edited.');
    const languageChanged = request.languageId !== editingVersion.languageId;
    if (editingVersion.status === 'DRAFT') {
      await updateFunctionVersion(selectedId, editingVersion.version, { ...request, status: 'DRAFT' });
      if (publish) {
        await publishFunctionVersion(selectedId, editingVersion.version);
        await activateFunctionVersion(selectedId, editingVersion.version);
      }
    } else if (codeChanged || languageChanged) {
      if (publish) {
        const created = await createFunctionVersion(selectedId, { ...request, status: 'AVAILABLE' });
        await activateFunctionVersion(selectedId, created.version);
      } else {
        await createFunctionVersion(selectedId, { ...request, status: 'DRAFT' });
      }
    } else {
      await updateFunctionVersionMetadata(selectedId, editingVersion.version, request);
      if (publish && selected?.activeVersion !== editingVersion.version) {
        await activateFunctionVersion(selectedId, editingVersion.version);
      }
    }
    await handleVersionCreated();
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

  const publishDraft = async (version: number) => {
    if (!selectedId || busy) return;
    setBusy(true);
    try {
      await publishFunctionVersion(selectedId, version);
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

  const archiveSelected = async () => {
    if (!selectedId || busy) return;
    setBusy(true);
    try {
      await deleteFunctionDefinition(selectedId);
      await reloadFunctions();
      setSelectedId(null);
      setVersions([]);
      setActiveTab('overview');
    } finally {
      setBusy(false);
    }
  };

  const updateVersionSettings = async (
    version: number,
    request: FunctionVersionSettingsRequest,
  ) => {
    if (!selectedId || busy) return;
    setBusy(true);
    try {
      await updateFunctionVersionSettings(selectedId, version, request);
      await reloadVersions(selectedId);
    } finally {
      setBusy(false);
    }
  };

  const saveVersionTestCases = async (
    version: FunctionVersionDTO,
    testCases: FunctionTestCase[],
  ) => {
    if (!selectedId) throw new Error('No function is selected.');
    if (busy) throw new Error('Another function action is already in progress.');
    setBusy(true);
    try {
      await updateFunctionVersionMetadata(selectedId, version.version, {
        sourceMode: version.sourceMode,
        languageId: version.languageId,
        sourceCode: version.sourceCode,
        additionalFilesBase64: version.additionalFilesBase64,
        compilerOptions: version.compilerOptions,
        commandLineArguments: version.commandLineArguments,
        cpuTimeLimitSeconds: version.cpuTimeLimitSeconds,
        wallTimeLimitSeconds: version.wallTimeLimitSeconds,
        memoryLimitKb: version.memoryLimitKb,
        maxFileSizeKb: version.maxFileSizeKb,
        maxOutputBytes: version.maxOutputBytes,
        enableNetwork: version.enableNetwork,
        note: version.note,
        testCases,
      });
      await reloadVersions(selectedId);
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
      {creating ? (
        <FunctionCreateWorkbench
          languages={languages}
          onCancel={() => setCreating(false)}
          onDone={handleFunctionCreated}
        />
      ) : selected && showVersionWorkbench ? (
        <FunctionVersionWorkbench
          key={editingVersion ? `edit-${editingVersion.id}` : 'new-version'}
          resourceLabel={functionResource(selected, editingVersion?.status === 'DRAFT' ? editingVersion.version : nextVersionNumber)}
          languages={languages}
          languageId={newVersionLanguageId}
          onLanguageChange={setNewVersionLanguageId}
          initialTestCases={newVersionTestCases}
          initialSourceMode={editingVersion?.sourceMode}
          onSourceModeChange={setNewVersionSourceMode}
          initialSourceCode={editingVersion?.sourceCode}
          initialFiles={editingVersion && editingVersion.sourceMode === 'MULTI_FILE' ? editingVersion.files : undefined}
          initialSettings={editingVersion ? {
            compilerOptions: editingVersion.compilerOptions,
            commandLineArguments: editingVersion.commandLineArguments,
            cpuTimeLimitSeconds: editingVersion.cpuTimeLimitSeconds,
            wallTimeLimitSeconds: editingVersion.wallTimeLimitSeconds,
            memoryLimitKb: editingVersion.memoryLimitKb,
            maxFileSizeKb: editingVersion.maxFileSizeKb,
            maxOutputBytes: editingVersion.maxOutputBytes,
            enableNetwork: editingVersion.enableNetwork,
          } : undefined}
          initialNote={editingVersion?.note}
          description={selected.description || ''}
          metadata={(
            <SelectedMetadata
              selected={selected}
              languages={languagesForSourceMode(languages, newVersionSourceMode)}
              languageId={newVersionLanguageId}
              onLanguageChange={setNewVersionLanguageId}
              editingVersion={editingVersion}
              nextVersionNumber={nextVersionNumber}
            />
          )}
          onCancel={() => {
            setShowVersionWorkbench(false);
            setEditingVersion(null);
            setNewVersionSourceMode('SINGLE_FILE');
          }}
          saveDraftLabel={editingVersion
            ? (editingVersion.status === 'DRAFT' ? 'Update draft' : 'Save changes')
            : 'Save draft'}
          onSaveDraft={editingVersion
            ? (request, meta) => saveEditedVersion(request, meta.codeChanged, false)
            : async (request) => {
                await createFunctionVersion(selected.id, request);
                await handleVersionCreated();
              }}
          publishLabel={editingVersion ? 'Publish & activate' : 'Publish version'}
          onPublish={editingVersion
            ? (request, meta) => saveEditedVersion(request, meta.codeChanged, true)
            : async (request) => {
                await createFunctionVersion(selected.id, request);
                await handleVersionCreated();
              }}
        />
      ) : selected ? (
        <FunctionDetail
          selected={selected}
          versions={versions}
          versionsLoading={versionsLoading}
          activeTab={activeTab}
          setActiveTab={setActiveTab}
          languageName={languageName}
          busy={busy}
          onBack={() => {
            setSelectedId(null);
            setVersions([]);
            setActiveTab('overview');
          }}
          onNewVersion={() => {
            const sorted = [...versions].sort((left, right) => right.version - left.version);
            const base = sorted.find((version) => version.version === selected.activeVersion) || sorted[0];
            setNewVersionLanguageId(base ? String(base.languageId) : '');
            setNewVersionSourceMode(base?.sourceMode ?? 'SINGLE_FILE');
            setNewVersionTestCases(base?.testCases ?? []);
            setEditingVersion(null);
            setShowVersionWorkbench(true);
          }}
          onEditVersion={openEditVersion}
          onActivate={activate}
          onPublishDraft={publishDraft}
          onToggleStatus={() => toggleStatus(selected)}
          onArchive={archiveSelected}
          onUpdateVersionSettings={updateVersionSettings}
          testPanel={(
            versions.length === 0 ? (
              <EmptyPanel message="Publish a version first, then test it here." />
            ) : (
              <FunctionTestPanel
                versions={versions}
                activeVersion={selected.activeVersion}
                onSaveTestCases={saveVersionTestCases}
              />
            )
          )}
          invocationsPanel={<FunctionInvocationsList functionId={selected.id} refreshKey={invocationsRefreshKey} />}
        />
      ) : (
        <FunctionListView
          functions={filteredFunctions}
          totalCount={functions.length}
          activeCount={activeFunctionCount}
          pausedCount={pausedFunctionCount}
          archivedOrDraftCount={showArchived ? archivedFunctionCount : draftFunctionCount}
          showArchived={showArchived}
          search={search}
          onSearch={setSearch}
          onToggleArchived={() => setShowArchived((value) => !value)}
          onRefresh={() => reloadFunctions()}
          onSelect={selectFunction}
          onCreate={() => {
            setCreating(true);
            setSelectedId(null);
            setVersions([]);
            setShowVersionWorkbench(false);
          }}
        />
      )}
    </div>
  );
}

function FunctionListView({
  functions,
  totalCount,
  activeCount,
  pausedCount,
  archivedOrDraftCount,
  showArchived,
  search,
  onSearch,
  onToggleArchived,
  onRefresh,
  onSelect,
  onCreate,
}: {
  functions: FunctionDefinitionDTO[];
  totalCount: number;
  activeCount: number;
  pausedCount: number;
  archivedOrDraftCount: number;
  showArchived: boolean;
  search: string;
  onSearch: (value: string) => void;
  onToggleArchived: () => void;
  onRefresh: () => void;
  onSelect: (id: string) => void;
  onCreate: () => void;
}) {
  return (
    <div className="flex h-full min-h-0 w-full flex-1 flex-col">
      <div className="glass-shell grid shrink-0 grid-cols-1 border-b border-border-subtle bg-surface-base md:grid-cols-4">
        <FunctionMetric label={showArchived ? 'Visible functions' : 'Live functions'} value={totalCount} tone="text-primary" />
        <FunctionMetric label="Active functions" value={activeCount} tone="text-status-success" />
        <FunctionMetric label="Paused functions" value={pausedCount} tone="text-on-surface-variant" />
        <FunctionMetric label={showArchived ? 'Archived functions' : 'Draft functions'} value={archivedOrDraftCount} tone={showArchived ? 'text-status-error' : 'text-status-info'} />
      </div>

      <div className="flex shrink-0 flex-wrap items-center gap-2 border-b border-border-subtle bg-surface-base/60 px-5 py-3">
        <label className="flex h-9 min-w-[220px] flex-1 items-center gap-2 rounded-lg border border-border-subtle bg-surface-container-lowest px-3">
          <Search size={14} className="text-on-surface-variant" />
          <input
            value={search}
            onChange={(event) => onSearch(event.target.value)}
            placeholder="Search functions..."
            className="min-w-0 flex-1 border-none bg-transparent text-[12px] text-on-surface outline-none placeholder:text-on-surface-variant/55"
          />
        </label>
        <button
          type="button"
          onClick={onToggleArchived}
          className={`flex h-9 items-center gap-2 rounded-lg border px-3 text-[12px] transition-colors ${
            showArchived
              ? 'border-status-error/35 bg-status-error/10 text-status-error'
              : 'border-border-subtle text-on-surface-variant hover:border-primary/45 hover:text-on-surface'
          }`}
        >
          <Archive size={13} />
          {showArchived ? 'Showing archived' : 'Show archived'}
        </button>
        <button
          type="button"
          onClick={onCreate}
          className="flex h-9 items-center gap-2 rounded-lg border border-primary/45 bg-primary px-3 text-[13px] font-semibold text-on-primary shadow-[0_16px_42px_rgba(242,121,90,0.24)] transition-colors hover:bg-primary-fixed-dim"
        >
          <Plus size={16} />
          New Function
        </button>
        <button
          type="button"
          onClick={onRefresh}
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-border-subtle text-on-surface-variant transition-colors hover:border-primary/45 hover:text-on-surface"
          title="Refresh functions"
        >
          <RefreshCw size={14} />
        </button>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto bg-[linear-gradient(180deg,#081421,#050b13)] p-5">
        {functions.length === 0 ? (
          <div className="rounded-lg border border-dashed border-border-subtle px-3 py-16 text-center text-[12px] text-on-surface-variant">
            No functions yet.
          </div>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {functions.map((fn) => (
              <button
                key={fn.id}
                type="button"
                onClick={() => onSelect(fn.id)}
                className="group flex flex-col rounded-lg border border-border-subtle bg-surface-container-lowest/60 p-4 text-left transition-colors hover:border-primary/55 hover:bg-[linear-gradient(145deg,rgba(242,121,90,0.1),rgba(14,23,34,0.78))]"
              >
                <div className="flex items-center gap-2">
                  <span className={`h-2 w-2 shrink-0 rounded-full ${
                    fn.status === 'ENABLED'
                      ? 'bg-secondary'
                      : fn.status === 'ARCHIVED'
                        ? 'bg-status-error'
                        : 'bg-on-surface-variant/45'
                  }`} />
                  <span className="min-w-0 flex-1 truncate text-[14px] font-semibold text-on-surface">{fn.name}</span>
                  <span className="rounded-md border border-border-subtle bg-surface-container-low px-1.5 py-0.5 font-mono-sm text-[10px] text-primary">
                    {fn.activeVersion ? `v${fn.activeVersion}` : '-'}
                  </span>
                </div>
                <div className="mt-2 truncate font-mono-sm text-[11px] text-on-surface-variant">{fn.namespace} / {fn.name}</div>
                <div className="mt-1 font-mono-sm text-[11px] text-on-surface-variant">{formatUpdated(fn.updatedAt)}</div>
              </button>
            ))}
          </div>
        )}
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
  const [sourceMode, setSourceMode] = useState<FunctionSourceMode>('SINGLE_FILE');

  const sortedLanguages = [...languagesForSourceMode(languages, sourceMode)]
    .sort((a, b) => a.name.localeCompare(b.name));
  const validDefinition = slug.test(namespace) && slug.test(name);
  const metadata = (
    <div className="grid gap-3 lg:grid-cols-3">
      <MetaInput label="Namespace" value={namespace} onChange={setNamespace} placeholder="billing" />
      <MetaInput label="Name" value={name} onChange={setName} placeholder="calculate-tax" />
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
      onSourceModeChange={setSourceMode}
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
  activeTab,
  setActiveTab,
  languageName,
  busy,
  onBack,
  onNewVersion,
  onEditVersion,
  onActivate,
  onPublishDraft,
  onToggleStatus,
  onArchive,
  onUpdateVersionSettings,
  testPanel,
  invocationsPanel,
}: {
  selected: FunctionDefinitionDTO;
  versions: FunctionVersionDTO[];
  versionsLoading: boolean;
  activeTab: DetailTab;
  setActiveTab: (tab: DetailTab) => void;
  languageName: (id: number) => string;
  busy: boolean;
  onBack: () => void;
  onNewVersion: () => void;
  onEditVersion: (version: FunctionVersionDTO) => void;
  onActivate: (version: number) => void;
  onPublishDraft: (version: number) => void;
  onToggleStatus: () => void;
  onArchive: () => void;
  onUpdateVersionSettings: (version: number, request: FunctionVersionSettingsRequest) => Promise<void>;
  testPanel: ReactNode;
  invocationsPanel: ReactNode;
}) {
  const sortedVersions = [...versions].sort((left, right) => {
    const leftActive = left.version === selected.activeVersion;
    const rightActive = right.version === selected.activeVersion;
    if (leftActive !== rightActive) {
      return leftActive ? -1 : 1;
    }
    return right.version - left.version;
  });
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
  const statusLabel = functionStatusLabel(selected.status);
  const networkLabel = displayVersion?.enableNetwork ? 'Network enabled' : 'Network disabled';
  const archived = selected.status === 'ARCHIVED';
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
        <div className="flex min-w-0 items-center gap-2">
          <button
            type="button"
            onClick={onBack}
            className="flex shrink-0 items-center gap-1.5 text-[14px] font-semibold text-on-surface-variant transition-colors hover:text-on-surface"
            title="Back to functions"
          >
            <ArrowLeft size={14} />
            Functions
          </button>
          <span className="text-on-surface-variant/60">/</span>
          <span className="min-w-0 truncate text-[14px] font-semibold text-on-surface" aria-current="page">{selected.name}</span>
          <span className={`rounded-md border px-2 py-1 text-[11px] font-medium ${
            selected.status === 'ENABLED'
              ? 'border-secondary/35 bg-secondary/10 text-secondary'
              : selected.status === 'ARCHIVED'
                ? 'border-status-error/35 bg-status-error/10 text-status-error'
              : 'border-border-subtle bg-surface-container-low text-on-surface-variant'
          }`}>
            {statusLabel}
          </span>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <button
            type="button"
            onClick={onNewVersion}
            disabled={archived}
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
                  <HeroChip icon={<CheckCircle2 size={13} />} label={statusLabel} tone={functionStatusTone(selected.status)} />
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
              <FunctionOverviewDetails
                selected={selected}
                draftCount={draftCount}
                availableCount={availableCount}
                archivedCount={archivedCount}
              />
            )}
            {activeTab === 'versions' && (
              <VersionHistoryPanel
                versions={sortedVersions}
                activeVersion={selected.activeVersion}
                languageName={languageName}
                loading={versionsLoading}
                busy={busy}
                onActivate={onActivate}
                onPublishDraft={onPublishDraft}
                onNewVersion={onNewVersion}
                onEditVersion={onEditVersion}
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
                onArchive={onArchive}
                onUpdateVersionSettings={onUpdateVersionSettings}
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

function HeroChip({ icon, label, tone = 'muted' }: { icon: ReactNode; label: string; tone?: 'ok' | 'muted' | 'danger' }) {
  return (
    <span className={`inline-flex h-8 items-center gap-1.5 rounded-md border px-2.5 text-[12px] ${
      tone === 'ok'
        ? 'border-secondary/35 bg-secondary/10 text-secondary'
        : tone === 'danger'
          ? 'border-status-error/35 bg-status-error/10 text-status-error'
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

function OverviewLine({
  label,
  value,
  mono,
  copyable,
  stacked,
}: {
  label: string;
  value: string;
  mono?: boolean;
  copyable?: boolean;
  stacked?: boolean;
}) {
  const copyValue = async () => {
    if (!copyable) return;
    try {
      await navigator.clipboard.writeText(value);
    } catch {
      // Best effort copy action.
    }
  };

  if (stacked) {
    return (
      <div className="min-w-0 text-[12px]">
        <div className="mb-1 text-on-surface-variant">{label}</div>
        <button
          type="button"
          onClick={copyValue}
          className={`block min-w-0 max-w-full truncate text-left text-on-surface ${mono ? 'font-mono-sm' : ''} ${copyable ? 'cursor-copy hover:text-primary' : 'cursor-default'}`}
          title={value}
        >
          {value}
          {copyable && <Copy size={12} className="ml-2 inline text-on-surface-variant" />}
        </button>
      </div>
    );
  }

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

function VersionHistoryPanel({
  versions,
  activeVersion,
  languageName,
  loading,
  busy,
  onActivate,
  onPublishDraft,
  onNewVersion,
  onEditVersion,
}: {
  versions: FunctionVersionDTO[];
  activeVersion: number | null;
  languageName: (id: number) => string;
  loading: boolean;
  busy: boolean;
  onActivate: (version: number) => void;
  onPublishDraft: (version: number) => void;
  onNewVersion: () => void;
  onEditVersion: (version: FunctionVersionDTO) => void;
}) {
  const preferredVersion = activeVersion != null && versions.some((version) => version.version === activeVersion)
    ? activeVersion
    : versions[0]?.version ?? null;
  const [selectedVersionNumber, setSelectedVersionNumber] = useState<number | null>(
    preferredVersion,
  );
  const selectedVersion = versions.find((version) => version.version === selectedVersionNumber)
    || versions.find((version) => version.version === activeVersion)
    || versions[0]
    || null;

  useEffect(() => {
    if (versions.length === 0) {
      setSelectedVersionNumber(null);
      return;
    }
    if (!versions.some((version) => version.version === selectedVersionNumber)) {
      setSelectedVersionNumber(preferredVersion);
    }
  }, [preferredVersion, selectedVersionNumber, versions]);

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
    <div className="grid min-h-[560px] gap-4 xl:grid-cols-[320px_minmax(0,1fr)]">
      <div className="rounded-lg border border-border-subtle bg-surface-container-lowest/55 p-4">
        <div className="mb-4 flex items-center justify-between gap-3">
          <div>
            <h3 className="text-[15px] font-semibold text-on-surface">Versions</h3>
            <p className="mt-1 text-[12px] text-on-surface-variant">Click a version to inspect its source and details.</p>
          </div>
          <button type="button" onClick={onNewVersion} className="flex h-8 items-center gap-1.5 rounded-lg border border-primary/45 px-3 text-[12px] text-primary hover:bg-primary/10">
            <Plus size={13} />
            Add
          </button>
        </div>
        <div className="space-y-2">
            {versions.map((version) => {
              const selected = selectedVersion?.version === version.version;
              const current = activeVersion === version.version;
              return (
                <button
                  key={version.id}
                  type="button"
                  onClick={() => setSelectedVersionNumber(version.version)}
                  className={`w-full rounded-lg border p-3 text-left transition-colors ${
                    selected
                      ? 'border-primary/55 bg-primary/10'
                      : 'border-border-subtle bg-surface-container-lowest/50 hover:border-primary/35'
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <span className="font-mono-sm text-[13px] font-semibold text-on-surface">v{version.version}</span>
                    <StatusChip
                      label={current ? 'current' : version.status.toLowerCase()}
                      tone={current || version.status === 'AVAILABLE' ? 'ok' : version.status === 'DRAFT' ? 'info' : 'muted'}
                    />
                  </div>
                  <div className="mt-2 space-y-1 text-[11px] text-on-surface-variant">
                    <div className="truncate">{languageName(version.languageId)}</div>
                    <div>{version.sourceMode === 'MULTI_FILE' ? 'Multi-file' : 'Single file'} - {formatUpdated(version.updatedAt).replace('Updated ', '')}</div>
                    {version.note?.trim() && (
                      <div className="line-clamp-2 whitespace-pre-wrap text-on-surface-variant/80" title={version.note}>
                        {version.note.trim()}
                      </div>
                    )}
                  </div>
                </button>
              );
            })}
          </div>
      </div>
      <VersionInspector
        title={selectedVersion ? `Version v${selectedVersion.version}` : 'Version details'}
        version={selectedVersion}
        activeVersion={activeVersion}
        languageName={languageName}
        busy={busy}
        onActivate={onActivate}
        onPublishDraft={onPublishDraft}
        onEditVersion={onEditVersion}
        emptyMessage="Choose a version to inspect its code and execution settings."
      />
    </div>
  );
}

function VersionInspector({
  title,
  version,
  activeVersion,
  languageName,
  busy,
  onActivate,
  onPublishDraft,
  onEditVersion,
  emptyMessage,
}: {
  title: string;
  version: FunctionVersionDTO | null;
  activeVersion: number | null;
  languageName: (id: number) => string;
  busy: boolean;
  onActivate: (version: number) => void;
  onPublishDraft: (version: number) => void;
  onEditVersion: (version: FunctionVersionDTO) => void;
  emptyMessage: string;
}) {
  const files = useMemo(() => versionSourceFiles(version), [version]);
  const isActive = Boolean(version && activeVersion === version.version);

  return (
    <div className="rounded-lg border border-border-subtle bg-surface-container-lowest/55 p-4">
      <div className="mb-4 flex items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <FileCode2 size={16} className="text-primary" />
            <h3 className="text-[15px] font-semibold text-on-surface">{title}</h3>
            {isActive && <StatusChip label="current" tone="ok" />}
          </div>
          {version && (
            <p className="mt-1 text-[12px] text-on-surface-variant">
              {languageName(version.languageId)} - {version.sourceMode === 'MULTI_FILE' ? 'Multi-file' : 'Single file'} - {formatDateTime(version.updatedAt)}
            </p>
          )}
        </div>
        {version && (
          <div className="flex shrink-0 items-center gap-2">
            {version.status !== 'ARCHIVED' && (
              <button
                type="button"
                onClick={() => onEditVersion(version)}
                disabled={busy}
                className="flex h-8 items-center gap-1.5 rounded-lg border border-border-subtle px-3 text-[12px] text-on-surface-variant transition-colors hover:border-primary/45 hover:text-on-surface disabled:opacity-50"
                title={version.status === 'DRAFT' ? 'Edit this draft in place' : 'Edit as a new draft, then publish & activate'}
              >
                <Pencil size={13} />
                {version.status === 'DRAFT' ? 'Edit draft' : 'Edit'}
              </button>
            )}
            {!isActive && version.status === 'DRAFT' && (
              <button
                type="button"
                onClick={() => onPublishDraft(version.version)}
                disabled={busy}
                className="flex h-8 items-center gap-1.5 rounded-lg border border-primary/45 px-3 text-[12px] text-primary hover:bg-primary/10 disabled:opacity-50"
              >
                <CheckCircle2 size={13} />
                Publish draft
              </button>
            )}
            {!isActive && version.status === 'AVAILABLE' && (
              <button
                type="button"
                onClick={() => onActivate(version.version)}
                disabled={busy}
                className="flex h-8 items-center gap-1.5 rounded-lg border border-primary/45 px-3 text-[12px] text-primary hover:bg-primary/10 disabled:opacity-50"
              >
                <Power size={13} />
                Activate
              </button>
            )}
          </div>
        )}
      </div>
      {!version ? (
        <EmptyPanel message={emptyMessage} />
      ) : (
        <div className="space-y-4">
          <FunctionCodeViewer files={files} languageName={languageName(version.languageId)} />
          <VersionTestCasesCard testCases={version.testCases} />
          <VersionConfigGrid version={version} languageName={languageName} />
        </div>
      )}
    </div>
  );
}
type ConfirmState = {
  title: string;
  message: string;
  confirmLabel: string;
  tone: 'primary' | 'danger';
  action: () => void | Promise<void>;
};

function FunctionSettingsPanel({
  selected,
  activeVersion,
  resourceUri,
  busy,
  onToggleStatus,
  onArchive,
  onUpdateVersionSettings,
}: {
  selected: FunctionDefinitionDTO;
  activeVersion: FunctionVersionDTO | null;
  resourceUri: string;
  busy: boolean;
  onToggleStatus: () => void;
  onArchive: () => void;
  onUpdateVersionSettings: (version: number, request: FunctionVersionSettingsRequest) => Promise<void>;
}) {
  const [compilerOptions, setCompilerOptions] = useState('');
  const [commandLineArguments, setCommandLineArguments] = useState('');
  const [cpuTime, setCpuTime] = useState('');
  const [wallTime, setWallTime] = useState('');
  const [memoryLimit, setMemoryLimit] = useState('');
  const [maxFileSize, setMaxFileSize] = useState('');
  const [maxOutput, setMaxOutput] = useState('');
  const [networkEnabled, setNetworkEnabled] = useState(false);
  const [settingsError, setSettingsError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<ConfirmState | null>(null);
  const archived = selected.status === 'ARCHIVED';

  useEffect(() => {
    if (!activeVersion) return;
    setCompilerOptions(activeVersion.compilerOptions || '');
    setCommandLineArguments(activeVersion.commandLineArguments || '');
    setCpuTime(String(activeVersion.cpuTimeLimitSeconds));
    setWallTime(String(activeVersion.wallTimeLimitSeconds));
    setMemoryLimit(String(activeVersion.memoryLimitKb));
    setMaxFileSize(String(activeVersion.maxFileSizeKb));
    setMaxOutput(String(activeVersion.maxOutputBytes));
    setNetworkEnabled(activeVersion.enableNetwork);
    setSettingsError(null);
  }, [activeVersion]);

  const readDecimal = (label: string, value: string, min: number) => {
    const parsed = Number(value);
    if (!Number.isFinite(parsed) || parsed < min) {
      throw new Error(`${label} must be at least ${min}.`);
    }
    return parsed;
  };

  const readInteger = (label: string, value: string, min: number) => {
    const parsed = readDecimal(label, value, min);
    if (!Number.isInteger(parsed)) {
      throw new Error(`${label} must be a whole number.`);
    }
    return parsed;
  };

  const buildRequest = (): FunctionVersionSettingsRequest => {
    const cpuSeconds = readDecimal('CPU time', cpuTime, 0.1);
    const wallSeconds = readDecimal('Wall time', wallTime, 0.1);
    if (wallSeconds < cpuSeconds) {
      throw new Error('Wall time must be greater than or equal to CPU time.');
    }
    if (compilerOptions.length > 512) {
      throw new Error('Compiler options must be 512 characters or less.');
    }
    if (commandLineArguments.length > 512) {
      throw new Error('Command-line arguments must be 512 characters or less.');
    }
    return {
      compilerOptions: compilerOptions.trim() || null,
      commandLineArguments: commandLineArguments.trim() || null,
      cpuTimeLimitSeconds: cpuSeconds,
      wallTimeLimitSeconds: wallSeconds,
      memoryLimitKb: readInteger('Memory limit', memoryLimit, 1024),
      maxFileSizeKb: readInteger('Max file size', maxFileSize, 1),
      maxOutputBytes: readInteger('Max output', maxOutput, 1),
      enableNetwork: networkEnabled,
    };
  };

  const requestSave = () => {
    if (!activeVersion || busy || archived) return;
    try {
      setSettingsError(null);
      const request = buildRequest();
      setConfirm({
        title: 'Apply execution settings?',
        message: `The updated resource limits will apply to v${activeVersion.version} the next time this function runs.`,
        confirmLabel: 'Apply settings',
        tone: 'primary',
        action: () => onUpdateVersionSettings(activeVersion.version, request),
      });
    } catch (err) {
      setSettingsError((err as Error).message);
    }
  };

  const requestToggleStatus = () => {
    if (busy) return;
    const enabling = selected.status !== 'ENABLED';
    setConfirm({
      title: enabling ? 'Enable function?' : 'Disable function?',
      message: enabling
        ? 'Workflows will be able to resolve and run this function again.'
        : 'Workflows will no longer be able to resolve or run this function until it is enabled again.',
      confirmLabel: enabling ? 'Enable function' : 'Disable function',
      tone: 'primary',
      action: onToggleStatus,
    });
  };

  const requestDelete = () => {
    if (busy) return;
    setConfirm({
      title: 'Delete this function?',
      message: 'This is a soft delete — the function is archived, not permanently removed. It stops resolving for workflows, but its versions and history are preserved and you can restore it later from "Show archived".',
      confirmLabel: 'Delete function',
      tone: 'danger',
      action: onArchive,
    });
  };

  const runConfirm = async () => {
    const action = confirm?.action;
    setConfirm(null);
    if (action) await action();
  };

  const numberError = (value: string, opts: { min: number; integer?: boolean }): string | null => {
    const trimmed = value.trim();
    if (!trimmed) return 'Required.';
    const parsed = Number(trimmed);
    if (!Number.isFinite(parsed)) return 'Enter a number.';
    if (opts.integer && !Number.isInteger(parsed)) return 'Whole number only.';
    if (parsed < opts.min) return `Must be at least ${opts.min}.`;
    return null;
  };

  const cpuError = numberError(cpuTime, { min: 0.1 });
  const rawWallError = numberError(wallTime, { min: 0.1 });
  const wallError = rawWallError
    || (!cpuError && Number(wallTime) < Number(cpuTime) ? 'Must be ≥ CPU time.' : null);
  const memoryError = numberError(memoryLimit, { min: 1024, integer: true });
  const maxFileError = numberError(maxFileSize, { min: 1, integer: true });
  const maxOutputError = numberError(maxOutput, { min: 1, integer: true });
  const compilerError = compilerOptions.length > 512 ? 'Must be 512 characters or less.' : null;
  const argsError = commandLineArguments.length > 512 ? 'Must be 512 characters or less.' : null;
  const hasFieldErrors = Boolean(
    cpuError || wallError || memoryError || maxFileError || maxOutputError || compilerError || argsError,
  );

  const statusToneClass = selected.status === 'ENABLED'
    ? 'border-secondary/35 bg-secondary/10 text-secondary'
    : selected.status === 'ARCHIVED'
      ? 'border-status-error/35 bg-status-error/10 text-status-error'
      : 'border-border-subtle bg-surface-container-low text-on-surface-variant';

  return (
    <div className="mx-auto w-full max-w-[720px] space-y-4">
      <div className="rounded-xl border border-border-subtle bg-surface-container-lowest/55 p-5">
        <SettingsSectionHeader
          icon={<Settings size={16} className="text-primary" />}
          title="Function"
          description="General details and availability for this function."
        />
        <div className="mt-4 divide-y divide-border-subtle/70">
          <SettingsRow label="Status" description="Whether workflows can resolve this function.">
            <span className={`rounded-md border px-2 py-1 text-[11px] font-medium ${statusToneClass}`}>
              {functionStatusLabel(selected.status)}
            </span>
          </SettingsRow>
          <SettingsRow label="Resource URI" description="Reference used by workflows to call this function.">
            <span className="max-w-full truncate font-mono-sm text-[12px] text-on-surface" title={resourceUri}>{resourceUri}</span>
          </SettingsRow>
          <SettingsRow label="Description" description="Human-readable summary of what this function does.">
            <span className="max-w-full text-right text-[12px] text-on-surface-variant">{selected.description || 'No description'}</span>
          </SettingsRow>
        </div>

        {!archived && (
          <div className="mt-5 border-t border-border-subtle/70 pt-4">
            <div className="text-[12px] font-semibold text-on-surface">Availability</div>
            <p className="mt-1 text-[11px] leading-4 text-on-surface-variant">
              Disabling pauses the function without removing it. Deleting is a soft delete — it archives the function so its history is preserved and it can be restored later.
            </p>
            <div className="mt-3 grid gap-2 sm:grid-cols-2">
              <button
                type="button"
                onClick={requestToggleStatus}
                disabled={busy}
                className={`flex h-9 items-center justify-center gap-2 rounded-lg border px-3 text-[12px] transition-colors disabled:opacity-50 ${
                  selected.status === 'ENABLED'
                    ? 'border-secondary/35 bg-secondary/10 text-secondary hover:bg-secondary/15'
                    : 'border-border-subtle text-on-surface-variant hover:text-on-surface'
                }`}
              >
                <Power size={14} />
                {selected.status === 'ENABLED' ? 'Disable function' : 'Enable function'}
              </button>
              <button
                type="button"
                onClick={requestDelete}
                disabled={busy}
                className="flex h-9 items-center justify-center gap-2 rounded-lg border border-status-error/35 px-3 text-[12px] text-status-error transition-colors hover:bg-status-error/10 disabled:opacity-50"
              >
                <Trash2 size={14} />
                Delete function
              </button>
            </div>
          </div>
        )}
      </div>

      <div className="rounded-xl border border-border-subtle bg-surface-container-lowest/55 p-5">
        <SettingsSectionHeader
          icon={<Braces size={16} className="text-primary" />}
          title="Execution settings"
          description={activeVersion ? `Resource limits applied to v${activeVersion.version} when it runs.` : 'Resource limits for the current version.'}
        />
        {activeVersion ? (
          <div className="mt-4 space-y-4">
            <div className="grid gap-3 sm:grid-cols-2">
              <SettingsInput label="CPU time (s)" value={cpuTime} onChange={setCpuTime} disabled={busy || archived} error={cpuError} hint="Seconds, e.g. 2 or 0.5" />
              <SettingsInput label="Wall time (s)" value={wallTime} onChange={setWallTime} disabled={busy || archived} error={wallError} hint="Seconds, ≥ CPU time" />
              <SettingsInput label="Memory (KB)" value={memoryLimit} onChange={setMemoryLimit} disabled={busy || archived} error={memoryError} integer hint="Whole KB, min 1024" />
              <SettingsInput label="Max file size (KB)" value={maxFileSize} onChange={setMaxFileSize} disabled={busy || archived} error={maxFileError} integer hint="Whole KB" />
              <SettingsInput label="Max output (bytes)" value={maxOutput} onChange={setMaxOutput} disabled={busy || archived} error={maxOutputError} integer hint="Whole bytes" />
            </div>
            <label className="flex h-[58px] items-center justify-between rounded-lg border border-border-subtle bg-surface-container-lowest px-3">
              <span>
                <span className="block text-[12px] font-semibold text-on-surface">Network access</span>
                <span className="mt-0.5 block text-[11px] text-on-surface-variant">Outbound calls during execution.</span>
              </span>
              <input
                type="checkbox"
                checked={networkEnabled}
                disabled={busy || archived}
                onChange={(event) => setNetworkEnabled(event.target.checked)}
                className="h-5 w-5 rounded border-border-subtle bg-surface-container text-primary focus:ring-primary disabled:opacity-50"
              />
            </label>
            <SettingsTextInput label="Compiler options" value={compilerOptions} onChange={setCompilerOptions} disabled={busy || archived} placeholder="-O2 -pipe" maxLength={512} error={compilerError} />
            <SettingsTextInput label="Command-line arguments" value={commandLineArguments} onChange={setCommandLineArguments} disabled={busy || archived} placeholder="--fast-mode" maxLength={512} error={argsError} />
            {settingsError && <div className="text-[12px] text-status-error">{settingsError}</div>}
            <div className="flex justify-end border-t border-border-subtle/70 pt-4">
              <button
                type="button"
                onClick={requestSave}
                disabled={busy || archived || hasFieldErrors}
                className="flex h-9 items-center gap-2 rounded-lg border border-primary bg-primary px-4 text-[12px] font-semibold text-on-primary transition-colors hover:bg-primary-fixed-dim disabled:cursor-not-allowed disabled:opacity-50"
              >
                <Settings size={14} />
                Save execution settings
              </button>
            </div>
          </div>
        ) : (
          <div className="mt-4">
            <EmptyPanel message="No current version yet. Add a version, then edit execution settings here." />
          </div>
        )}
      </div>

      {confirm && (
        <ConfirmDialog
          title={confirm.title}
          message={confirm.message}
          confirmLabel={confirm.confirmLabel}
          tone={confirm.tone}
          busy={busy}
          onCancel={() => setConfirm(null)}
          onConfirm={runConfirm}
        />
      )}
    </div>
  );
}

function SettingsSectionHeader({ icon, title, description }: { icon: ReactNode; title: string; description: string }) {
  return (
    <div className="flex items-start gap-2.5">
      <div className="mt-0.5">{icon}</div>
      <div>
        <h3 className="text-[15px] font-semibold text-on-surface">{title}</h3>
        <p className="mt-0.5 text-[12px] text-on-surface-variant">{description}</p>
      </div>
    </div>
  );
}

function SettingsRow({ label, description, children }: { label: string; description: string; children: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 py-3">
      <div className="min-w-0">
        <div className="text-[12px] font-medium text-on-surface">{label}</div>
        <div className="mt-0.5 text-[11px] text-on-surface-variant">{description}</div>
      </div>
      <div className="flex min-w-0 max-w-[55%] justify-end">{children}</div>
    </div>
  );
}

function ConfirmDialog({
  title,
  message,
  confirmLabel,
  tone,
  busy,
  onCancel,
  onConfirm,
}: {
  title: string;
  message: string;
  confirmLabel: string;
  tone: 'primary' | 'danger';
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <div
      className="fixed inset-0 z-[100] flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      onClick={onCancel}
    >
      <div
        className="w-full max-w-[440px] rounded-xl border border-border-subtle bg-surface-container-lowest p-5 shadow-[0_30px_80px_rgba(0,0,0,0.5)]"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-start gap-3">
          <div className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border ${
            tone === 'danger'
              ? 'border-status-error/35 bg-status-error/10 text-status-error'
              : 'border-primary/35 bg-primary/10 text-primary'
          }`}>
            {tone === 'danger' ? <AlertTriangle size={18} /> : <Settings size={18} />}
          </div>
          <div className="min-w-0">
            <h3 className="text-[15px] font-semibold text-on-surface">{title}</h3>
            <p className="mt-1.5 text-[12px] leading-5 text-on-surface-variant">{message}</p>
          </div>
        </div>
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            disabled={busy}
            className="flex h-9 items-center rounded-lg border border-border-subtle px-4 text-[12px] text-on-surface-variant transition-colors hover:text-on-surface disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={busy}
            className={`flex h-9 items-center gap-2 rounded-lg border px-4 text-[12px] font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
              tone === 'danger'
                ? 'border-status-error bg-status-error/90 text-white hover:bg-status-error'
                : 'border-primary bg-primary text-on-primary hover:bg-primary-fixed-dim'
            }`}
          >
            {busy && <Loader2 size={14} className="animate-spin" />}
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

function SettingsInput({
  label,
  value,
  onChange,
  disabled,
  hint,
  error,
  integer,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled: boolean;
  hint?: string;
  error?: string | null;
  integer?: boolean;
}) {
  const invalid = Boolean(error);
  return (
    <label className="block">
      <span className={labelClass}>{label}</span>
      <input
        value={value}
        onChange={(event) => {
          // Digits only for integer fields; digits + one dot for decimals.
          const cleaned = integer
            ? event.target.value.replace(/[^\d]/g, '')
            : event.target.value.replace(/[^\d.]/g, '').replace(/(\..*)\./g, '$1');
          onChange(cleaned);
        }}
        disabled={disabled}
        inputMode={integer ? 'numeric' : 'decimal'}
        aria-invalid={invalid}
        className={`${fieldClass} ${invalid ? 'border-status-error/70 focus:border-status-error' : ''}`}
      />
      {invalid ? (
        <span className="mt-1 block text-[10px] text-status-error">{error}</span>
      ) : hint ? (
        <span className="mt-1 block text-[10px] text-on-surface-variant/70">{hint}</span>
      ) : null}
    </label>
  );
}

function SettingsTextInput({
  label,
  value,
  onChange,
  disabled,
  placeholder,
  maxLength,
  error,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled: boolean;
  placeholder: string;
  maxLength?: number;
  error?: string | null;
}) {
  const invalid = Boolean(error);
  return (
    <label className="block">
      <span className={labelClass}>
        {label}
        {maxLength != null && (
          <span className={`ml-auto font-mono-sm text-[10px] ${invalid ? 'text-status-error' : 'text-on-surface-variant/60'}`}>
            {value.length}/{maxLength}
          </span>
        )}
      </span>
      <input
        value={value}
        onChange={(event) => onChange(event.target.value)}
        disabled={disabled}
        placeholder={placeholder}
        aria-invalid={invalid}
        className={`${fieldClass} ${invalid ? 'border-status-error/70 focus:border-status-error' : ''}`}
      />
      {invalid && <span className="mt-1 block text-[10px] text-status-error">{error}</span>}
    </label>
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

function VersionTestCasesCard({ testCases }: { testCases: FunctionTestCase[] }) {
  const cases = testCases ?? [];
  return (
    <div className="rounded-lg border border-border-subtle bg-surface-container-lowest/55 p-4">
      <div className="mb-3 flex items-center gap-2">
        <Sparkles size={15} className="text-primary" />
        <h4 className="text-[13px] font-semibold text-on-surface">Saved test cases</h4>
        <span className="rounded-md border border-border-subtle px-1.5 py-0.5 font-mono-sm text-[10px] text-on-surface-variant">{cases.length}</span>
      </div>
      {cases.length === 0 ? (
        <p className="text-[12px] text-on-surface-variant">
          No test cases saved with this version. Add them in the workbench when you create a version.
        </p>
      ) : (
        <div className="space-y-2">
          {cases.map((testCase, index) => (
            <div key={index} className="rounded-lg border border-border-subtle bg-surface-container-lowest px-3 py-2">
              <div className="mb-2 text-[12px] font-semibold text-on-surface">{testCase.name?.trim() || `Case ${index + 1}`}</div>
              <div className="grid gap-2 md:grid-cols-2">
                <TestCaseField label="Input (stdin)" value={testCase.input} />
                <TestCaseField label="Expected output" value={testCase.expectedOutput} />
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function TestCaseField({ label, value }: { label: string; value: string }) {
  const trimmed = value?.trim();
  return (
    <div className="min-w-0">
      <div className="mb-1 font-mono-sm text-[9px] uppercase tracking-[0.07em] text-on-surface-variant">{label}</div>
      {trimmed ? (
        <pre className="max-h-32 overflow-auto rounded-md border border-border-subtle bg-surface-base p-2 font-mono-sm text-[11px] leading-relaxed text-on-surface">{trimmed}</pre>
      ) : (
        <div className="rounded-md border border-dashed border-border-subtle px-2 py-2 text-[11px] text-on-surface-variant/70">Empty</div>
      )}
    </div>
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
      <div className="md:col-span-2 xl:col-span-4">
        <TinyDetail label="Version note" value={version.note?.trim() || 'None'} />
      </div>
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

function SelectedMetadata({
  selected,
  languages,
  languageId,
  onLanguageChange,
  editingVersion,
  nextVersionNumber,
}: {
  selected: FunctionDefinitionDTO;
  languages: FunctionLanguageDTO[];
  languageId: string;
  onLanguageChange: (value: string) => void;
  editingVersion?: FunctionVersionDTO | null;
  nextVersionNumber?: number;
}) {
  const sortedLanguages = [...languages].sort((a, b) => a.name.localeCompare(b.name));
  const editBanner = (() => {
    if (!editingVersion) return null;
    if (editingVersion.status === 'DRAFT') {
      return `Editing draft v${editingVersion.version} in place. Changes overwrite this draft until you publish it.`;
    }
    return `Editing v${editingVersion.version}. Note, execution settings, and test cases save onto v${editingVersion.version} in place. Changing the code or language creates a new version${nextVersionNumber ? ` (v${nextVersionNumber})` : ''} instead.`;
  })();
  return (
    <div className="space-y-3">
      {editBanner && (
        <div className="flex items-start gap-2 rounded-lg border border-primary/35 bg-primary/10 px-3 py-2 text-[12px] leading-5 text-primary">
          <Pencil size={13} className="mt-0.5 shrink-0" />
          <span>{editBanner}</span>
        </div>
      )}
      <div className="grid gap-3 lg:grid-cols-3">
        <ReadOnlyMeta label="Namespace" value={selected.namespace} />
        <ReadOnlyMeta label="Name" value={selected.name} />
        <label>
          <span className={labelClass}>Language</span>
          <select
            value={languageId}
            onChange={(event) => onLanguageChange(event.target.value)}
            className={selectFieldClass}
          >
            <option value="">Select language</option>
            {sortedLanguages.map((language) => (
              <option key={language.id} value={language.id}>{language.name}</option>
            ))}
          </select>
        </label>
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
        className={`${fieldClass} ${readOnly ? 'cursor-not-allowed text-on-surface-variant' : ''}`}
      />
    </label>
  );
}

function ReadOnlyMeta({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <span className={labelClass}>{label}</span>
      <div
        className="flex h-9 cursor-not-allowed items-center rounded-lg border border-border-subtle bg-surface-container-lowest px-3 font-mono-sm text-[12px] text-on-surface-variant"
        title={`${label} can't be changed here`}
      >
        {value}
      </div>
    </div>
  );
}
