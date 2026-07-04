import { useCallback, useEffect, useMemo, useState } from 'react';
import { Loader2, Plus, Power, RefreshCw } from 'lucide-react';
import {
  activateFunctionVersion,
  createFunctionDefinition,
  listFunctionLanguages,
  listFunctionVersions,
  listFunctions,
  updateFunctionDefinition,
  type FunctionDefinitionDTO,
  type FunctionLanguageDTO,
  type FunctionVersionDTO,
} from '../api';
import { FunctionVersionForm } from '../components/functions/FunctionVersionForm';
import { FunctionTestPanel } from '../components/functions/FunctionTestPanel';
import { FunctionInvocationsList } from '../components/functions/FunctionInvocationsList';

type DetailTab = 'versions' | 'test' | 'invocations';

const field =
  'h-9 w-full rounded-lg border border-border-subtle bg-surface-container-lowest px-3 font-mono-sm text-[12px] text-on-surface outline-none transition-colors placeholder:text-on-surface-variant/45 focus:border-primary/50';
const fieldLabel = 'mb-1 block font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant/70';

export function FunctionsPage() {
  const [functions, setFunctions] = useState<FunctionDefinitionDTO[]>([]);
  const [languages, setLanguages] = useState<FunctionLanguageDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const [versions, setVersions] = useState<FunctionVersionDTO[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [activeTab, setActiveTab] = useState<DetailTab>('versions');
  const [showVersionForm, setShowVersionForm] = useState(false);
  const [invocationsRefreshKey, setInvocationsRefreshKey] = useState(0);
  const [busy, setBusy] = useState(false);

  const languageName = useMemo(() => {
    const map = new Map(languages.map((language) => [language.id, language.name]));
    return (id: number) => map.get(id) || `Language ${id}`;
  }, [languages]);

  const selected = functions.find((fn) => fn.id === selectedId) || null;

  const reloadFunctions = useCallback(() => {
    return listFunctions().then((data) => {
      setFunctions(data);
      return data;
    });
  }, []);

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

  const openFunction = (id: string) => {
    setActiveTab('versions');
    setShowVersionForm(false);
    reloadVersions(id);
  };

  const selectFunction = (id: string) => {
    setCreating(false);
    setSelectedId(id);
    openFunction(id);
  };

  const handleFunctionCreated = async (fn: FunctionDefinitionDTO) => {
    await reloadFunctions();
    setCreating(false);
    setSelectedId(fn.id);
    openFunction(fn.id);
  };

  const handleVersionCreated = async () => {
    setShowVersionForm(false);
    setActiveTab('versions');
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
        displayName: fn.displayName,
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
      <div className="flex h-full items-center justify-center gap-2 font-mono-sm text-[12px] text-on-surface-variant">
        <Loader2 size={16} className="animate-spin" /> Loading functions…
      </div>
    );
  }
  if (error) {
    return (
      <div className="flex h-full items-center justify-center px-6 text-center text-body-sm text-on-surface-variant">
        {error}
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0">
      <aside className="flex w-[280px] shrink-0 flex-col border-r border-border-subtle">
        <div className="flex items-center justify-between border-b border-border-subtle px-4 py-3">
          <span className="font-mono-sm text-[11px] uppercase tracking-[0.1em] text-on-surface-variant/70">
            Functions ({functions.length})
          </span>
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={() => reloadFunctions()}
              className="flex h-7 w-7 items-center justify-center rounded-md text-on-surface-variant transition-colors hover:bg-surface-container-low hover:text-on-surface"
              title="Refresh"
            >
              <RefreshCw size={14} />
            </button>
            <button
              type="button"
              onClick={() => { setCreating(true); setSelectedId(null); setVersions([]); }}
              className="flex h-7 items-center gap-1 rounded-md border border-primary/40 bg-primary/10 px-2 font-mono-sm text-[11px] text-primary transition-colors hover:bg-primary/20"
              title="New function"
            >
              <Plus size={13} /> New
            </button>
          </div>
        </div>
        <div className="flex-1 space-y-1 overflow-y-auto p-2">
          {functions.length === 0 ? (
            <p className="px-2 py-6 text-center font-mono-sm text-[11px] text-on-surface-variant/70">No functions yet.</p>
          ) : (
            functions.map((fn) => {
              const active = fn.id === selectedId && !creating;
              return (
                <button
                  key={fn.id}
                  type="button"
                  onClick={() => selectFunction(fn.id)}
                  className={`flex w-full flex-col gap-0.5 rounded-lg border px-3 py-2 text-left transition-colors ${
                    active
                      ? 'border-primary/45 bg-primary/10'
                      : 'border-transparent hover:border-border-subtle hover:bg-surface-container-low'
                  }`}
                >
                  <span className="flex items-center gap-2">
                    <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${fn.status === 'ENABLED' ? 'bg-secondary' : 'bg-on-surface-variant/50'}`} />
                    <span className="truncate text-[13px] font-medium text-on-surface">{fn.displayName || fn.name}</span>
                  </span>
                  <span className="truncate pl-3.5 font-mono-sm text-[10px] text-on-surface-variant">
                    {fn.namespace}/{fn.name}{fn.activeVersion ? ` · v${fn.activeVersion}` : ' · no active version'}
                  </span>
                </button>
              );
            })
          )}
        </div>
      </aside>

      <div className="min-w-0 flex-1 overflow-y-auto">
        {creating ? (
          <FunctionCreateForm onCreated={handleFunctionCreated} onCancel={() => setCreating(false)} />
        ) : !selected ? (
          <div className="flex h-full items-center justify-center px-6 text-center text-body-sm text-on-surface-variant/70">
            Select a function, or create one to run code as a <span className="mx-1 font-mono-sm text-secondary">function://</span> task.
          </div>
        ) : (
          <div className="mx-auto w-full max-w-[860px] px-6 py-6">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="min-w-0">
                <h1 className="truncate font-display text-[18px] font-semibold text-on-surface">{selected.displayName || selected.name}</h1>
                <p className="mt-1 font-mono-sm text-[11px] text-on-surface-variant">
                  <span className="text-secondary">function://{selected.namespace}/{selected.name}</span>
                  {selected.activeVersion ? ` @v${selected.activeVersion}` : ' · no active version'}
                </p>
                {selected.description && <p className="mt-2 max-w-[560px] text-body-sm text-on-surface-variant">{selected.description}</p>}
              </div>
              <button
                type="button"
                onClick={() => toggleStatus(selected)}
                disabled={busy}
                className={`flex h-8 items-center gap-1.5 rounded-lg border px-3 font-mono-sm text-[11px] transition-colors disabled:opacity-50 ${
                  selected.status === 'ENABLED'
                    ? 'border-secondary/40 bg-secondary/10 text-secondary'
                    : 'border-border-subtle text-on-surface-variant hover:text-on-surface'
                }`}
                title={selected.status === 'ENABLED' ? 'Disable function' : 'Enable function'}
              >
                <Power size={13} /> {selected.status === 'ENABLED' ? 'Enabled' : 'Disabled'}
              </button>
            </div>

            <div className="mt-5 flex items-center gap-1 border-b border-border-subtle">
              {(['versions', 'test', 'invocations'] as DetailTab[]).map((tab) => (
                <button
                  key={tab}
                  type="button"
                  onClick={() => setActiveTab(tab)}
                  className={`-mb-px border-b-2 px-3 py-2 font-body-sm text-body-sm capitalize transition-colors ${
                    activeTab === tab
                      ? 'border-primary text-primary'
                      : 'border-transparent text-on-surface-variant hover:text-on-surface'
                  }`}
                >
                  {tab}
                </button>
              ))}
            </div>

            <div className="mt-5">
              {activeTab === 'versions' && (
                <div className="space-y-4">
                  <div className="flex justify-end">
                    {!showVersionForm && (
                      <button
                        type="button"
                        onClick={() => setShowVersionForm(true)}
                        className="flex h-8 items-center gap-1.5 rounded-lg border border-primary/40 bg-primary/10 px-3 font-mono-sm text-[11px] text-primary transition-colors hover:bg-primary/20"
                      >
                        <Plus size={13} /> New version
                      </button>
                    )}
                  </div>
                  {showVersionForm && (
                    <FunctionVersionForm
                      functionId={selected.id}
                      languages={languages}
                      onCreated={handleVersionCreated}
                      onCancel={() => setShowVersionForm(false)}
                    />
                  )}
                  {versionsLoading ? (
                    <div className="flex items-center gap-2 font-mono-sm text-[12px] text-on-surface-variant">
                      <Loader2 size={14} className="animate-spin" /> Loading versions…
                    </div>
                  ) : versions.length === 0 ? (
                    <div className="flex min-h-[100px] items-center justify-center rounded-lg border border-dashed border-border-subtle text-body-sm text-on-surface-variant/70">
                      No versions yet — publish one to make this function runnable.
                    </div>
                  ) : (
                    <div className="overflow-hidden rounded-lg border border-border-subtle">
                      {versions.map((version, index) => {
                        const isActive = selected.activeVersion === version.version;
                        return (
                          <div key={version.id} className={`flex items-center gap-3 px-4 py-3 ${index > 0 ? 'border-t border-border-subtle' : ''}`}>
                            <span className="font-mono-sm text-[12px] font-semibold text-on-surface">v{version.version}</span>
                            {isActive && <span className="rounded border border-secondary/40 bg-secondary/10 px-1.5 py-0.5 font-mono-sm text-[9px] uppercase tracking-[0.06em] text-secondary">Active</span>}
                            <span className="font-mono-sm text-[11px] text-on-surface-variant">{languageName(version.languageId)}</span>
                            <span className="hidden font-mono-sm text-[10px] text-on-surface-variant/70 md:inline">
                              {version.sourceMode === 'MULTI_FILE' ? 'multi-file' : 'single-file'} · {version.cpuTimeLimitSeconds}s · {version.memoryLimitKb}KB{version.enableNetwork ? ' · net' : ''}
                            </span>
                            <span className="flex-1" />
                            {!isActive && (
                              <button
                                type="button"
                                onClick={() => activate(version.version)}
                                disabled={busy}
                                className="rounded-md border border-border-subtle px-2.5 py-1 font-mono-sm text-[10px] text-on-surface-variant transition-colors hover:border-primary/45 hover:text-primary disabled:opacity-50"
                              >
                                Activate
                              </button>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              )}

              {activeTab === 'test' && (
                versions.length === 0 ? (
                  <div className="flex min-h-[120px] items-center justify-center rounded-lg border border-dashed border-border-subtle text-body-sm text-on-surface-variant/70">
                    Publish a version first, then test it here.
                  </div>
                ) : (
                  <FunctionTestPanel
                    functionId={selected.id}
                    versions={versions}
                    activeVersion={selected.activeVersion}
                    onInvoked={() => setInvocationsRefreshKey((key) => key + 1)}
                  />
                )
              )}

              {activeTab === 'invocations' && (
                <FunctionInvocationsList functionId={selected.id} refreshKey={invocationsRefreshKey} />
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function FunctionCreateForm({ onCreated, onCancel }: { onCreated: (fn: FunctionDefinitionDTO) => void; onCancel: () => void }) {
  const [namespace, setNamespace] = useState('');
  const [name, setName] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [description, setDescription] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const slug = /^[a-z0-9][a-z0-9-]*$/;
  const canSave = slug.test(namespace) && slug.test(name);

  const submit = async () => {
    if (!canSave || saving) return;
    setSaving(true);
    setError(null);
    try {
      const created = await createFunctionDefinition({
        namespace,
        name,
        displayName: displayName.trim() || undefined,
        description: description.trim() || undefined,
      });
      onCreated(created);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="mx-auto w-full max-w-[560px] px-6 py-8">
      <h1 className="font-display text-[18px] font-semibold text-on-surface">New function</h1>
      <p className="mt-1 font-mono-sm text-[11px] text-on-surface-variant">
        Referenced from workflows as <span className="text-secondary">function://namespace/name@version</span>.
      </p>
      <div className="mt-5 space-y-3">
        <div className="grid grid-cols-2 gap-3">
          <div>
            <span className={fieldLabel}>Namespace</span>
            <input value={namespace} onChange={(e) => setNamespace(e.target.value)} className={field} placeholder="billing" />
          </div>
          <div>
            <span className={fieldLabel}>Name</span>
            <input value={name} onChange={(e) => setName(e.target.value)} className={field} placeholder="calculate-tax" />
          </div>
        </div>
        <p className="font-mono-sm text-[10px] text-on-surface-variant/60">Lowercase letters, numbers, and hyphens only.</p>
        <div>
          <span className={fieldLabel}>Display name (optional)</span>
          <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} className={field} placeholder="Calculate Tax" />
        </div>
        <div>
          <span className={fieldLabel}>Description (optional)</span>
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className="min-h-[80px] w-full resize-y rounded-lg border border-border-subtle bg-surface-container-lowest p-3 font-body-sm text-body-sm text-on-surface outline-none transition-colors placeholder:text-on-surface-variant/45 focus:border-primary/50"
            placeholder="What this function does"
          />
        </div>
        {error && <p className="font-mono-sm text-[11px] text-status-error">{error}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" onClick={onCancel} className="h-9 rounded-lg border border-border-subtle px-4 font-body-sm text-body-sm text-on-surface-variant transition-colors hover:text-on-surface">
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={!canSave || saving}
            className="flex h-9 items-center gap-2 rounded-lg border border-primary bg-primary px-4 font-body-sm text-body-sm font-medium text-on-primary transition-colors hover:bg-primary-fixed-dim disabled:cursor-not-allowed disabled:opacity-50"
          >
            {saving && <Loader2 size={15} className="animate-spin" />}
            Create function
          </button>
        </div>
      </div>
    </div>
  );
}
