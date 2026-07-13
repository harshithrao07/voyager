import { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, Boxes, Cpu, HardDrive, Loader2, Network, RefreshCw, ServerCog, Timer } from 'lucide-react';
import { getFunctionRuntimeInfo, type Judge0LimitsDTO, type Judge0RuntimeInfoDTO } from '../api';

function formatSeconds(value: number | null) {
  return value == null ? '—' : `${value}s`;
}

function formatKb(value: number | null) {
  if (value == null) return '—';
  if (value >= 1024) return `${(value / 1024).toFixed(value % 1024 === 0 ? 0 : 1)} MB`;
  return `${value} KB`;
}

function limitRows(limits: Judge0LimitsDTO) {
  return [
    { icon: <Cpu size={14} />, label: 'Max CPU time', value: formatSeconds(limits.maxCpuTimeLimit) },
    { icon: <Timer size={14} />, label: 'Max wall time', value: formatSeconds(limits.maxWallTimeLimit) },
    { icon: <HardDrive size={14} />, label: 'Max memory', value: formatKb(limits.maxMemoryLimit) },
    { icon: <HardDrive size={14} />, label: 'Max file size', value: formatKb(limits.maxFileSize) },
    { icon: <Boxes size={14} />, label: 'Max extract size', value: formatKb(limits.maxExtractSize) },
    {
      icon: <Network size={14} />,
      label: 'Network access',
      value: limits.allowEnableNetwork ? 'Allowed (opt-in)' : 'Disabled',
    },
  ];
}

export function Judge0RuntimePanel() {
  const [info, setInfo] = useState<Judge0RuntimeInfoDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    getFunctionRuntimeInfo()
      .then(setInfo)
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    let active = true;
    getFunctionRuntimeInfo()
      .then((data) => { if (active) setInfo(data); })
      .catch((err: Error) => { if (active) setError(err.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  const reachable = info?.reachable ?? false;

  return (
    <div className="glass-card rounded-xl border border-border-subtle p-5">
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-center gap-3">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-border-subtle bg-surface-container-lowest text-primary">
            <ServerCog size={18} />
          </span>
          <div>
            <h2 className="font-display text-[15px] font-semibold text-on-surface">Execution runtime</h2>
            <p className="mt-0.5 font-mono-sm text-[11px] text-on-surface-variant">
              Judge0 sandbox that powers <span className="text-secondary">voyager://namespace/name</span> tasks
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {!loading && (
            <span
              className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 font-mono-sm text-[10px] uppercase tracking-[0.08em] ${
                reachable
                  ? 'border-secondary/40 bg-secondary/10 text-secondary'
                  : 'border-status-error/40 bg-status-error/10 text-status-error'
              }`}
            >
              <span className={`h-1.5 w-1.5 rounded-full ${reachable ? 'bg-secondary' : 'bg-status-error'}`} />
              {reachable ? 'Reachable' : 'Unreachable'}
            </span>
          )}
          <button
            type="button"
            onClick={load}
            disabled={loading}
            className="flex h-8 w-8 items-center justify-center rounded-lg border border-border-subtle bg-surface-container-lowest text-on-surface-variant transition-colors hover:text-on-surface disabled:opacity-50"
            title="Refresh"
            aria-label="Refresh runtime info"
          >
            {loading ? <Loader2 size={15} className="animate-spin" /> : <RefreshCw size={15} />}
          </button>
        </div>
      </div>

      {loading ? (
        <div className="mt-6 flex items-center gap-2 font-mono-sm text-[12px] text-on-surface-variant">
          <Loader2 size={14} className="animate-spin" /> Probing Judge0…
        </div>
      ) : error ? (
        <div className="mt-5 flex items-start gap-3 rounded-lg border border-status-error/35 bg-status-error/5 p-4 text-body-sm text-on-surface-variant">
          <AlertTriangle size={16} className="mt-0.5 shrink-0 text-status-error" />
          <div>
            <p className="font-medium text-on-surface">Could not reach the functions API</p>
            <p className="mt-1 font-mono-sm text-[11px] break-words">{error}</p>
          </div>
        </div>
      ) : info && !info.reachable ? (
        <div className="mt-5 flex items-start gap-3 rounded-lg border border-status-error/35 bg-status-error/5 p-4 text-body-sm text-on-surface-variant">
          <AlertTriangle size={16} className="mt-0.5 shrink-0 text-status-error" />
          <div>
            <p className="font-medium text-on-surface">Judge0 is not responding</p>
            <p className="mt-1 font-mono-sm text-[11px] break-words">{info.error || 'No response from the execution engine.'}</p>
            <p className="mt-2 text-[12px]">
              Check that the <span className="font-mono-sm text-secondary">judge0-server</span> container is running and{' '}
              <span className="font-mono-sm text-secondary">JUDGE0_BASE_URL</span> points at it.
            </p>
          </div>
        </div>
      ) : info ? (
        <>
          <div className="mt-5 grid grid-cols-3 gap-3">
            <Stat label="Workers" value={`${info.availableWorkers}/${info.workers}`} hint="available / total" />
            <Stat label="Runtimes" value={String(info.languageCount)} hint="supported" />
            <Stat label="Statuses" value={String(info.statusCount)} hint="known" />
          </div>

          {info.limits && (
            <div className="mt-5">
              <div className="mb-2 font-mono-sm text-[10px] uppercase tracking-[0.1em] text-on-surface-variant/70">
                Sandbox ceilings (operator-set)
              </div>
              <div className="grid grid-cols-2 gap-2 md:grid-cols-3">
                {limitRows(info.limits).map((row) => (
                  <div
                    key={row.label}
                    className="flex items-center gap-2.5 rounded-lg border border-border-subtle bg-surface-container-lowest px-3 py-2.5"
                  >
                    <span className="text-on-surface-variant">{row.icon}</span>
                    <span className="min-w-0">
                      <span className="block truncate font-mono-sm text-[10px] text-on-surface-variant">{row.label}</span>
                      <span className="block truncate text-[13px] font-medium text-on-surface">{row.value}</span>
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {info.languages.length > 0 && (
            <div className="mt-5">
              <div className="mb-2 font-mono-sm text-[10px] uppercase tracking-[0.1em] text-on-surface-variant/70">
                Supported runtimes
              </div>
              <div className="flex flex-wrap gap-1.5">
                {info.languages.slice(0, 24).map((language) => (
                  <span
                    key={language.id}
                    className="rounded-md border border-border-subtle bg-surface-container-lowest px-2 py-1 font-mono-sm text-[11px] text-on-surface-variant"
                  >
                    {language.name}
                  </span>
                ))}
                {info.languages.length > 24 && (
                  <span className="rounded-md px-2 py-1 font-mono-sm text-[11px] text-on-surface-variant/70">
                    +{info.languages.length - 24} more
                  </span>
                )}
              </div>
            </div>
          )}
        </>
      ) : null}
    </div>
  );
}

function Stat({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div className="rounded-lg border border-border-subtle bg-surface-container-lowest p-3">
      <div className="font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant/70">{label}</div>
      <div className="mt-1 text-[22px] font-semibold leading-none text-on-surface">{value}</div>
      <div className="mt-1 font-mono-sm text-[10px] text-on-surface-variant/60">{hint}</div>
    </div>
  );
}
