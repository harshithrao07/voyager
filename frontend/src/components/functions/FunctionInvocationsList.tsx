import { useEffect, useState } from 'react';
import { ChevronDown, ChevronRight, Loader2 } from 'lucide-react';
import { listFunctionInvocations, type FunctionInvocationDTO } from '../../api';

const statusTone: Record<string, string> = {
  SUCCEEDED: 'text-secondary',
  FAILED: 'text-status-error',
  RUNNING: 'text-status-info',
};

function formatTime(value: string | null) {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat(undefined, { month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(date);
}

function formatMaybe(value: unknown) {
  if (value === null || value === undefined || value === '') return '-';
  return String(value);
}

function formatJson(value: unknown) {
  if (value === null || value === undefined) return '-';
  return JSON.stringify(value, null, 2);
}

export function FunctionInvocationsList({ functionId, refreshKey }: { functionId: string; refreshKey: number }) {
  const [invocations, setInvocations] = useState<FunctionInvocationDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    listFunctionInvocations(functionId)
      .then((data) => { if (active) { setInvocations(data); setError(null); } })
      .catch((err: Error) => { if (active) setError(err.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [functionId, refreshKey]);

  if (loading) {
    return (
      <div className="flex items-center gap-2 font-mono-sm text-[12px] text-on-surface-variant">
        <Loader2 size={14} className="animate-spin" /> Loading invocations...
      </div>
    );
  }
  if (error) {
    return <p className="font-mono-sm text-[11px] text-status-error">{error}</p>;
  }
  if (invocations.length === 0) {
    return (
      <div className="flex min-h-[120px] items-center justify-center rounded-lg border border-dashed border-border-subtle text-body-sm text-on-surface-variant/70">
        No invocations yet. Run a test or trigger a workflow function task.
      </div>
    );
  }

  return (
    <div className="overflow-hidden rounded-lg border border-border-subtle">
      {invocations.map((invocation, index) => {
        const expanded = expandedId === invocation.id;
        return (
          <div key={invocation.id} className={index > 0 ? 'border-t border-border-subtle' : ''}>
            <button
              type="button"
              onClick={() => setExpandedId(expanded ? null : invocation.id)}
              className="flex w-full items-center gap-3 px-3 py-2.5 text-left transition-colors hover:bg-surface-container-low"
            >
              {expanded ? <ChevronDown size={14} className="shrink-0 text-on-surface-variant" /> : <ChevronRight size={14} className="shrink-0 text-on-surface-variant" />}
              <span className={`font-mono-sm text-[11px] font-semibold uppercase ${statusTone[invocation.status] || 'text-on-surface-variant'}`}>{invocation.status}</span>
              <span className="font-mono-sm text-[11px] text-on-surface-variant">v{invocation.version}</span>
              <span className="flex-1 truncate font-mono-sm text-[11px] text-on-surface-variant">{formatTime(invocation.startedAt)}</span>
              {invocation.workflowExecutionId && (
                <span className="hidden rounded border border-border-subtle px-1.5 py-0.5 font-mono-sm text-[10px] text-on-surface-variant md:inline" title={`Workflow run ${invocation.workflowExecutionId}${invocation.stateName ? ` - ${invocation.stateName}` : ''}`}>
                  workflow
                </span>
              )}
              {invocation.durationMs != null && <span className="font-mono-sm text-[10px] text-on-surface-variant/70">{invocation.durationMs}ms</span>}
              {invocation.errorName && <span className="max-w-[160px] truncate font-mono-sm text-[10px] text-status-error">{invocation.errorName}</span>}
            </button>
            {expanded && (
              <div className="space-y-2 border-t border-border-subtle bg-surface-base/50 px-3 py-3">
                <div className="grid gap-2 md:grid-cols-2 xl:grid-cols-4">
                  <Mini label="Invocation ID" value={invocation.id} />
                  <Mini label="Function ID" value={invocation.functionId} />
                  <Mini label="Judge0 token" value={formatMaybe(invocation.judge0Token)} />
                  <Mini label="Judge0 status" value={`${formatMaybe(invocation.judge0StatusId)} ${formatMaybe(invocation.judge0StatusDescription)}`} />
                  <Mini label="Workflow run" value={formatMaybe(invocation.workflowExecutionId)} />
                  <Mini label="Workflow state" value={formatMaybe(invocation.stateName)} />
                  <Mini label="Exit code" value={formatMaybe(invocation.exitCode)} />
                  <Mini label="Exit signal" value={formatMaybe(invocation.exitSignal)} />
                  <Mini label="CPU time" value={invocation.timeSeconds != null ? `${invocation.timeSeconds}s` : '-'} />
                  <Mini label="Wall time" value={invocation.wallTimeSeconds != null ? `${invocation.wallTimeSeconds}s` : '-'} />
                  <Mini label="Memory" value={invocation.memoryKb != null ? `${invocation.memoryKb} KB` : '-'} />
                  <Mini label="Duration" value={invocation.durationMs != null ? `${invocation.durationMs}ms` : '-'} />
                  <Mini label="Started" value={formatTime(invocation.startedAt)} />
                  <Mini label="Completed" value={formatTime(invocation.completedAt)} />
                  <Mini label="Created" value={formatTime(invocation.createdAt)} />
                  <Mini label="Updated" value={formatTime(invocation.updatedAt)} />
                </div>
                <Detail label="Input" body={formatJson(invocation.input)} />
                {invocation.output != null && <Detail label="Output" body={JSON.stringify(invocation.output, null, 2)} />}
                {invocation.stdout && <Detail label="stdout" body={invocation.stdout} />}
                {invocation.stderr && <Detail label="stderr" body={invocation.stderr} />}
                {invocation.compileOutput && <Detail label="compile output" body={invocation.compileOutput} />}
                {invocation.message && <Detail label="message" body={invocation.message} />}
                {invocation.errorMessage && <Detail label="error" body={invocation.errorMessage} />}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function Mini({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0 rounded-md border border-border-subtle bg-surface-container-lowest px-2.5 py-2">
      <div className="font-mono-sm text-[9px] uppercase tracking-[0.06em] text-on-surface-variant/70">{label}</div>
      <div className="mt-1 truncate font-mono-sm text-[10px] text-on-surface" title={value}>{value}</div>
    </div>
  );
}

function Detail({ label, body }: { label: string; body: string }) {
  return (
    <div>
      <div className="mb-1 font-mono-sm text-[10px] uppercase tracking-[0.06em] text-on-surface-variant/70">{label}</div>
      <pre className="max-h-52 overflow-auto rounded-md border border-border-subtle bg-surface-base p-2.5 font-mono-sm text-[11px] leading-relaxed text-on-surface">{body}</pre>
    </div>
  );
}
