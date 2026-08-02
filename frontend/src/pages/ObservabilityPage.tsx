import { useCallback, useEffect, useId, useMemo, useState, type ReactNode } from 'react';
import {
  Activity,
  ArrowUpRight,
  Bot,
  Check,
  Clock3,
  Copy,
  Cpu,
  Eye,
  EyeOff,
  Gauge,
  Info,
  KeyRound,
  Loader2,
  RefreshCw,
  ShieldCheck,
} from 'lucide-react';
import { getWorkflowAiObservability, type WorkflowAiObservability } from '../api';

const WINDOWS = [7, 30, 90] as const;
const ERROR_REASONS = ['error', 'failed', 'failure', 'cancelled', 'canceled'];
const LANGFUSE_DEFAULT_EMAIL = 'admin@voyager.local';
const LANGFUSE_DEFAULT_PASSWORD = 'voyager-admin';

export function ObservabilityPage() {
  const [windowDays, setWindowDays] = useState<(typeof WINDOWS)[number]>(30);
  const [metrics, setMetrics] = useState<WorkflowAiObservability | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const langfuseUrl =
    typeof window === 'undefined'
      ? 'http://localhost:3100'
      : `${window.location.protocol}//${window.location.hostname}:3100`;

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setMetrics(await getWorkflowAiObservability(windowDays));
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : 'Failed to load observability metrics');
    } finally {
      setLoading(false);
    }
  }, [windowDays]);

  useEffect(() => {
    void load();
  }, [load]);

  const failedTurns = useMemo(
    () => metrics?.finishReasons.reduce(
      (sum, row) => ERROR_REASONS.some((reason) => row.reason.toLowerCase().includes(reason)) ? sum + row.count : sum,
      0,
    ) ?? 0,
    [metrics],
  );
  const completionRate = metrics?.totalTurns
    ? Math.max(0, ((metrics.totalTurns - failedTurns) / metrics.totalTurns) * 100)
    : null;
  const latestTurn = metrics?.recent[0]?.createdAt;

  return (
    <div className="h-full min-h-0 overflow-y-auto">
      <div className="mx-auto w-full max-w-[1440px] px-4 py-5 md:px-7 md:py-7">
        <section className="relative border border-border-subtle bg-surface-container-lowest">
          <div className="absolute bottom-0 left-0 top-0 w-1 bg-secondary" />
          <div className="flex flex-col gap-5 px-5 py-6 md:flex-row md:items-end md:justify-between md:px-7">
            <div className="max-w-2xl">
              <div className="mb-3 flex flex-wrap items-center gap-2 font-label-mono text-label-mono uppercase tracking-[0.12em] text-secondary">
                <span className="inline-flex items-center gap-1.5 border border-secondary/30 bg-secondary/10 px-2 py-1">
                  <Activity size={11} /> Live telemetry
                </span>
                <span className="inline-flex items-center gap-1.5 border border-border-subtle px-2 py-1 text-on-surface-variant">
                  <ShieldCheck size={11} /> Read only
                </span>
              </div>
              <h1 className="font-display text-[28px] font-semibold tracking-[-0.03em] text-on-surface md:text-[34px]">
                AI flight recorder
              </h1>
              <p className="mt-2 max-w-xl text-body-lg text-on-surface-variant">
                Runtime health, token traffic, and model latency for workflow-AI turns recorded by Voyager.
              </p>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <div className="flex h-9 items-center border border-border-subtle bg-surface-base p-0.5" aria-label="Telemetry window">
                {WINDOWS.map((days) => (
                  <button
                    key={days}
                    type="button"
                    aria-pressed={windowDays === days}
                    onClick={() => setWindowDays(days)}
                    className={`h-8 min-w-11 px-3 font-mono-sm text-mono-sm transition-colors ${
                      windowDays === days
                        ? 'bg-secondary text-on-secondary'
                        : 'text-on-surface-variant hover:bg-surface-container hover:text-on-surface'
                    }`}
                  >
                    {days}D
                  </button>
                ))}
              </div>
              <button
                type="button"
                onClick={() => void load()}
                disabled={loading}
                className="inline-flex h-9 items-center gap-2 border border-border-subtle bg-surface-base px-3 text-body-sm font-medium text-on-surface transition-colors hover:border-secondary/50 hover:text-secondary disabled:opacity-50"
              >
                <RefreshCw size={14} className={loading ? 'animate-spin' : undefined} />
                Refresh
              </button>
              <LangfuseAccess />
              <a
                href={langfuseUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex h-9 items-center gap-2 bg-primary px-3 text-body-sm font-semibold text-on-primary transition-colors hover:bg-primary/90"
              >
                Open Langfuse <ArrowUpRight size={14} />
              </a>
            </div>
          </div>

          <div className="grid border-t border-border-subtle bg-surface-base/70 sm:grid-cols-3">
            <StatusDatum label="Window" value={`Last ${windowDays} days`} />
            <StatusDatum label="Last signal" value={latestTurn ? formatRelativeTime(latestTurn) : 'No signal recorded'} />
            <StatusDatum
              label="Telemetry status"
              value={error ? 'Connection interrupted' : loading && !metrics ? 'Synchronizing' : 'Recorder online'}
              tone={error ? 'error' : 'success'}
            />
          </div>
        </section>

        {loading && !metrics ? (
          <DashboardLoading />
        ) : error && !metrics ? (
          <div className="mt-5 flex min-h-64 flex-col items-center justify-center border border-error/40 bg-error/5 px-6 text-center">
            <Activity size={24} className="text-error" />
            <h2 className="mt-4 font-headline-lg text-headline-lg font-semibold text-on-surface">Telemetry unavailable</h2>
            <p className="mt-1 max-w-lg text-body-sm text-on-surface-variant">{error}</p>
            <button type="button" onClick={() => void load()} className="mt-5 border border-error/50 px-4 py-2 text-body-sm font-medium text-error hover:bg-error/10">
              Retry connection
            </button>
          </div>
        ) : metrics && metrics.totalTurns === 0 ? (
          <EmptyDashboard langfuseUrl={langfuseUrl} />
        ) : metrics ? (
          <>
            {error && (
              <div className="mt-5 border border-error/40 bg-error/10 px-4 py-3 text-body-sm text-error">
                Refresh failed. Showing the last successful snapshot. {error}
              </div>
            )}

            <section className="mt-5 grid grid-cols-2 border-l border-t border-border-subtle lg:grid-cols-4">
              <MetricCard
                icon={<Activity size={16} />}
                label="Recorded turns"
                value={formatNumber(metrics.totalTurns)}
                detail={`${metrics.windowDays}-day capture`}
                description="Final assistant responses recorded in the selected rolling time window. Internal repair passes and tool calls do not each count as a separate turn."
              />
              <MetricCard
                icon={<Cpu size={16} />}
                label="Total tokens"
                value={formatCompact(metrics.totalTokens)}
                detail={`${formatCompact(metrics.totalInputTokens)} in / ${formatCompact(metrics.totalOutputTokens)} out`}
                description="The sum of input and output tokens reported by the model provider. Input includes the context sent to the model; output is what the model generated."
              />
              <MetricCard
                icon={<Clock3 size={16} />}
                label="Median latency"
                value={formatMs(metrics.p50LatencyMs)}
                detail={`${formatMs(metrics.avgLatencyMs)} average`}
                description="P50 response time: half of recorded turns completed faster and half completed slower. The smaller line shows the arithmetic average."
              />
              <MetricCard
                icon={<Gauge size={16} />}
                label="P95 latency"
                value={formatMs(metrics.p95LatencyMs)}
                detail={completionRate == null ? 'No completion data' : `${completionRate.toFixed(1)}% non-error finishes`}
                description="The response time that 95% of recorded turns completed within. The non-error percentage is based on technical finish reasons, not answer quality."
              />
            </section>

            <div className="mt-5 grid gap-5 xl:grid-cols-[minmax(0,1.45fr)_minmax(320px,0.55fr)]">
              <Panel
                title="Recent latency signal"
                eyebrow="Flight recorder / latest turns"
                aside={`${metrics.recent.length} samples`}
                description="Each bar is one recent final assistant turn. Bar height represents its total response time; the dashed line is p95 across every turn in the selected window."
              >
                <LatencySignal metrics={metrics} />
              </Panel>
              <Panel
                title="Finish state"
                eyebrow="Terminal reason mix"
                aside={`${formatNumber(metrics.totalTurns)} turns`}
                description="The reason the model provider gave for ending generation. STOP means normal completion; LENGTH means a limit was reached; error or cancellation states need attention."
              >
                <FinishReasons metrics={metrics} />
              </Panel>
            </div>

            <div className="mt-5 grid gap-5 xl:grid-cols-[minmax(320px,0.55fr)_minmax(0,1.45fr)]">
              <Panel
                title="Model traffic"
                eyebrow="Volume and average latency"
                aside={`${metrics.byModel.length} ${metrics.byModel.length === 1 ? 'model' : 'models'}`}
                description="Recorded turns grouped by model, with token volume and average end-to-end response time for each model."
              >
                <ModelTraffic metrics={metrics} />
              </Panel>
              <Panel
                title="Latest turns"
                eyebrow="Most recent recorded activity"
                aside="Read only"
                description="The latest 20 final assistant turns in the selected window. Times are shown in your local timezone."
              >
                <RecentTurns metrics={metrics} />
              </Panel>
            </div>

            <MetricReference />
          </>
        ) : null}
      </div>
    </div>
  );
}

function LangfuseAccess() {
  const [passwordVisible, setPasswordVisible] = useState(false);
  const [copied, setCopied] = useState<'email' | 'password' | null>(null);

  const copyCredential = async (kind: 'email' | 'password', value: string) => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(kind);
      window.setTimeout(() => setCopied((current) => current === kind ? null : current), 1600);
    } catch {
      setCopied(null);
    }
  };

  return (
    <details className="group/access relative">
      <summary className="inline-flex h-9 cursor-pointer list-none items-center gap-2 border border-border-subtle bg-surface-base px-3 text-body-sm font-medium text-on-surface transition-colors hover:border-secondary/50 hover:text-secondary focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-secondary">
        <KeyRound size={14} />
        Admin sign-in
      </summary>
      <div className="absolute right-0 top-full z-50 mt-2 w-[340px] max-w-[calc(100vw-2.5rem)] border border-border-subtle bg-surface-container p-4 shadow-2xl">
        <div className="flex items-start justify-between gap-4 border-b border-border-subtle pb-3">
          <div>
            <div className="font-mono-sm text-mono-sm uppercase text-secondary">Langfuse access</div>
            <p className="mt-1 text-body-sm text-on-surface-variant">Use these Docker-default credentials on the Langfuse sign-in screen.</p>
          </div>
          <span className="shrink-0 border border-status-warning/40 bg-status-warning/10 px-2 py-1 font-mono-sm text-[9px] uppercase text-status-warning">Default</span>
        </div>

        <CredentialRow
          label="Email"
          value={LANGFUSE_DEFAULT_EMAIL}
          copied={copied === 'email'}
          onCopy={() => void copyCredential('email', LANGFUSE_DEFAULT_EMAIL)}
        />

        <div className="mt-3">
          <div className="mb-1.5 font-mono-sm text-mono-sm uppercase text-on-surface-variant">Password</div>
          <div className="flex min-w-0 items-center border border-border-subtle bg-surface-container-lowest">
            <code className="min-w-0 flex-1 truncate px-3 py-2 font-mono-sm text-[11px] text-on-surface">
              {passwordVisible ? LANGFUSE_DEFAULT_PASSWORD : '•••••••••••••'}
            </code>
            <button
              type="button"
              onClick={() => setPasswordVisible((visible) => !visible)}
              className="grid h-8 w-8 shrink-0 place-items-center border-l border-border-subtle text-on-surface-variant hover:text-secondary"
              aria-label={passwordVisible ? 'Hide Langfuse password' : 'Show Langfuse password'}
              title={passwordVisible ? 'Hide password' : 'Show password'}
            >
              {passwordVisible ? <EyeOff size={13} /> : <Eye size={13} />}
            </button>
            <button
              type="button"
              onClick={() => void copyCredential('password', LANGFUSE_DEFAULT_PASSWORD)}
              className="grid h-8 w-8 shrink-0 place-items-center border-l border-border-subtle text-on-surface-variant hover:text-secondary"
              aria-label="Copy Langfuse password"
              title="Copy password"
            >
              {copied === 'password' ? <Check size={13} className="text-secondary" /> : <Copy size={13} />}
            </button>
          </div>
        </div>

        <p className="mt-3 border-l-2 border-primary/60 pl-3 text-[11px] leading-4 text-on-surface-variant">
          If <code className="text-on-surface">LANGFUSE_INIT_USER_EMAIL</code> or{' '}
          <code className="text-on-surface">LANGFUSE_INIT_USER_PASSWORD</code> is overridden, use the configured values instead.
        </p>
      </div>
    </details>
  );
}

function CredentialRow({ label, value, copied, onCopy }: { label: string; value: string; copied: boolean; onCopy: () => void }) {
  return (
    <div className="mt-3">
      <div className="mb-1.5 font-mono-sm text-mono-sm uppercase text-on-surface-variant">{label}</div>
      <div className="flex min-w-0 items-center border border-border-subtle bg-surface-container-lowest">
        <code className="min-w-0 flex-1 truncate px-3 py-2 font-mono-sm text-[11px] text-on-surface">{value}</code>
        <button
          type="button"
          onClick={onCopy}
          className="grid h-8 w-8 shrink-0 place-items-center border-l border-border-subtle text-on-surface-variant hover:text-secondary"
          aria-label={`Copy Langfuse ${label.toLowerCase()}`}
          title={`Copy ${label.toLowerCase()}`}
        >
          {copied ? <Check size={13} className="text-secondary" /> : <Copy size={13} />}
        </button>
      </div>
    </div>
  );
}

function StatusDatum({ label, value, tone = 'neutral' }: { label: string; value: string; tone?: 'neutral' | 'success' | 'error' }) {
  return (
    <div className="flex items-center justify-between gap-3 border-b border-r border-border-subtle px-5 py-3 last:border-r-0 sm:border-b-0">
      <span className="font-mono-sm text-mono-sm uppercase text-on-surface-variant">{label}</span>
      <span className={`flex items-center gap-2 text-body-sm font-medium ${tone === 'error' ? 'text-error' : tone === 'success' ? 'text-secondary' : 'text-on-surface'}`}>
        {tone !== 'neutral' && <span className={`h-1.5 w-1.5 ${tone === 'error' ? 'bg-error' : 'bg-secondary'}`} />}
        {value}
      </span>
    </div>
  );
}

function MetricCard({ icon, label, value, detail, description }: { icon: ReactNode; label: string; value: string; detail: string; description: string }) {
  return (
    <article className="border-b border-r border-border-subtle bg-surface-container-lowest px-4 py-5 md:px-5">
      <div className="flex items-center gap-2 font-mono-sm text-mono-sm uppercase text-on-surface-variant">
        <span className="text-secondary">{icon}</span> {label} <InfoTip label={label} description={description} />
      </div>
      <div className="mt-4 font-display text-[25px] font-semibold tracking-[-0.03em] text-on-surface md:text-[30px]">{value}</div>
      <div className="mt-1 font-mono-sm text-mono-sm text-on-surface-variant">{detail}</div>
    </article>
  );
}

function Panel({ title, eyebrow, aside, description, children }: { title: string; eyebrow: string; aside: string; description: string; children: ReactNode }) {
  return (
    <section className="border border-border-subtle bg-surface-container-lowest">
      <header className="flex items-end justify-between gap-4 border-b border-border-subtle px-4 py-4 md:px-5">
        <div>
          <div className="font-mono-sm text-mono-sm uppercase text-secondary">{eyebrow}</div>
          <div className="mt-1 flex items-center gap-2">
            <h2 className="font-headline-lg text-headline-lg font-semibold text-on-surface">{title}</h2>
            <InfoTip label={title} description={description} />
          </div>
        </div>
        <span className="shrink-0 font-mono-sm text-mono-sm uppercase text-on-surface-variant">{aside}</span>
      </header>
      {children}
    </section>
  );
}

function InfoTip({ label, description }: { label: string; description: string }) {
  const id = useId();
  return (
    <span className="group relative inline-flex normal-case tracking-normal">
      <button
        type="button"
        aria-label={`About ${label}`}
        aria-describedby={id}
        className="grid h-5 w-5 place-items-center text-on-surface-variant/70 outline-none transition-colors hover:text-secondary focus-visible:text-secondary"
      >
        <Info size={12} />
      </button>
      <span
        id={id}
        role="tooltip"
        className="pointer-events-none absolute left-1/2 top-full z-40 mt-2 hidden w-64 -translate-x-1/2 border border-border-subtle bg-surface-container px-3 py-2.5 font-body-sm text-body-sm normal-case leading-5 tracking-normal text-on-surface shadow-xl group-hover:block group-focus-within:block"
      >
        {description}
      </span>
    </span>
  );
}

function MetricReference() {
  return (
    <details className="group mt-5 border border-border-subtle bg-surface-container-lowest">
      <summary className="flex cursor-pointer list-none items-center justify-between gap-4 px-4 py-4 outline-none hover:bg-surface-container-low focus-visible:bg-surface-container-low md:px-5">
        <span className="flex items-center gap-3">
          <span className="grid h-8 w-8 place-items-center border border-secondary/30 bg-secondary/10 text-secondary"><Info size={15} /></span>
          <span>
            <span className="block font-headline-md text-headline-md font-semibold text-on-surface">How to read this dashboard</span>
            <span className="mt-0.5 block text-body-sm text-on-surface-variant">Metric definitions, finish reasons, and collection boundaries</span>
          </span>
        </span>
        <span className="font-mono-sm text-mono-sm uppercase text-secondary group-open:hidden">Show guide</span>
        <span className="hidden font-mono-sm text-mono-sm uppercase text-secondary group-open:inline">Hide guide</span>
      </summary>
      <div className="grid border-t border-border-subtle md:grid-cols-2 xl:grid-cols-3">
        <Definition label="Time window">A rolling period ending now—not calendar weeks or months. A turn created within the last 7, 30, or 90 days is included.</Definition>
        <Definition label="Recorded turn">One persisted final assistant response. Multi-pass generation and tool calls can occur inside a turn; use Langfuse to inspect those internal spans.</Definition>
        <Definition label="Input / output tokens">Input is the prompt and context sent to the model. Output is generated content. Total tokens is their provider-reported sum.</Definition>
        <Definition label="P50 / median">The middle observed latency. Half of turns were faster and half were slower. This is less affected by unusually slow turns than the average.</Definition>
        <Definition label="P95 latency">Ninety-five percent of observed turns completed within this time. It highlights the slow end of normal performance.</Definition>
        <Definition label="Finish reason">STOP is normal completion. LENGTH means a generation limit was reached. TOOL_CALLS means the model requested tools. ERROR, FAILED, or CANCELLED indicate an incomplete turn.</Definition>
        <Definition label="Non-error finishes">The share without an error, failure, or cancellation finish reason. It measures technical completion only—not whether an answer was correct.</Definition>
        <Definition label="Recent turns">The table and signal show at most the latest 20 turns. Summary metrics use up to 10,000 turns from the selected window.</Definition>
        <Definition label="Model traffic">Turn count, total tokens, and average latency grouped by the model name saved with each response.</Definition>
      </div>
    </details>
  );
}

function Definition({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="border-b border-r border-border-subtle px-4 py-4 md:px-5">
      <div className="font-mono-sm text-mono-sm uppercase text-secondary">{label}</div>
      <p className="mt-2 text-body-sm leading-5 text-on-surface-variant">{children}</p>
    </div>
  );
}

function LatencySignal({ metrics }: { metrics: WorkflowAiObservability }) {
  const samples = [...metrics.recent].reverse().slice(-18);
  const ceiling = Math.max(metrics.p95LatencyMs, ...samples.map((turn) => turn.durationMs ?? 0), 1);

  return (
    <div className="px-4 py-5 md:px-5">
      <div className="relative flex h-40 items-end gap-1.5 border-b border-l border-border-subtle px-2 pt-3">
        <div className="absolute left-0 right-0 top-[35%] border-t border-dashed border-primary/35" />
        <span className="absolute right-2 top-[calc(35%-16px)] font-mono-sm text-[9px] uppercase text-primary">p95 {formatMs(metrics.p95LatencyMs)}</span>
        {samples.map((turn, index) => {
          const duration = turn.durationMs ?? 0;
          const height = duration ? Math.max(5, (duration / ceiling) * 100) : 3;
          return (
            <div key={`${turn.createdAt}-${index}`} className="group relative flex min-w-0 flex-1 items-end self-stretch">
              <div
                className="w-full bg-secondary/70 transition-colors group-hover:bg-secondary"
                style={{ height: `${Math.min(100, height)}%` }}
              />
              <div className="pointer-events-none absolute bottom-full left-1/2 z-10 mb-2 hidden -translate-x-1/2 border border-border-subtle bg-surface-container px-2 py-1 font-mono-sm text-[9px] text-on-surface shadow-lg group-hover:block">
                {duration ? formatMs(duration) : 'No latency'}
              </div>
            </div>
          );
        })}
        {samples.length === 0 && <span className="m-auto text-body-sm text-on-surface-variant">No recent latency samples</span>}
      </div>
      <div className="mt-3 flex items-center justify-between font-mono-sm text-[9px] uppercase text-on-surface-variant">
        <span>Older</span>
        <span>Each bar is one model turn</span>
        <span>Latest</span>
      </div>
    </div>
  );
}

function FinishReasons({ metrics }: { metrics: WorkflowAiObservability }) {
  const max = Math.max(...metrics.finishReasons.map((row) => row.count), 1);
  return (
    <div className="space-y-4 px-4 py-5 md:px-5">
      {metrics.finishReasons.length === 0 ? (
        <p className="text-body-sm text-on-surface-variant">No finish reasons recorded.</p>
      ) : metrics.finishReasons.map((row) => {
        const isError = ERROR_REASONS.some((reason) => row.reason.toLowerCase().includes(reason));
        return (
          <div key={row.reason}>
            <div className="mb-1.5 flex items-center justify-between gap-3 text-body-sm">
              <span className={isError ? 'text-error' : 'text-on-surface'}>{row.reason || 'unknown'}</span>
              <span className="font-mono-sm text-mono-sm text-on-surface-variant">{row.count} / {formatPercent(row.count, metrics.totalTurns)}</span>
            </div>
            <div className="h-1 bg-surface-container-high">
              <div className={`h-full ${isError ? 'bg-error' : 'bg-secondary'}`} style={{ width: `${(row.count / max) * 100}%` }} />
            </div>
          </div>
        );
      })}
    </div>
  );
}

function ModelTraffic({ metrics }: { metrics: WorkflowAiObservability }) {
  const maxTurns = Math.max(...metrics.byModel.map((row) => row.turns), 1);
  return (
    <div className="divide-y divide-border-subtle">
      {metrics.byModel.length === 0 ? (
        <p className="px-5 py-6 text-body-sm text-on-surface-variant">No model traffic recorded.</p>
      ) : metrics.byModel.map((row) => (
        <div key={row.model} className="px-4 py-4 md:px-5">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <div className="flex items-center gap-2 text-body-sm font-medium text-on-surface"><Bot size={13} className="shrink-0 text-secondary" /><span className="truncate">{row.model}</span></div>
              <div className="mt-1 font-mono-sm text-mono-sm text-on-surface-variant">{formatCompact(row.totalTokens)} tokens · {formatMs(row.avgLatencyMs)} avg</div>
            </div>
            <span className="font-display text-[18px] font-semibold text-on-surface">{row.turns}</span>
          </div>
          <div className="mt-3 h-1 bg-surface-container-high"><div className="h-full bg-primary" style={{ width: `${(row.turns / maxTurns) * 100}%` }} /></div>
        </div>
      ))}
    </div>
  );
}

function RecentTurns({ metrics }: { metrics: WorkflowAiObservability }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[680px] text-left text-body-sm">
        <thead className="border-b border-border-subtle bg-surface-base font-mono-sm text-mono-sm uppercase text-on-surface-variant">
          <tr>
            <th className="px-4 py-3 font-normal md:px-5">Recorded</th>
            <th className="px-4 py-3 font-normal">Model</th>
            <th className="px-4 py-3 text-right font-normal">Latency</th>
            <th className="px-4 py-3 text-right font-normal">Tokens</th>
            <th className="px-4 py-3 font-normal md:px-5">Finish</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border-subtle">
          {metrics.recent.map((turn, index) => {
            const isError = ERROR_REASONS.some((reason) => turn.finishReason.toLowerCase().includes(reason));
            return (
              <tr key={`${turn.createdAt}-${index}`} className="text-on-surface transition-colors hover:bg-surface-container-low">
                <td className="whitespace-nowrap px-4 py-3 text-on-surface-variant md:px-5">{formatTimestamp(turn.createdAt)}</td>
                <td className="max-w-52 truncate px-4 py-3 font-medium">{turn.model}</td>
                <td className="whitespace-nowrap px-4 py-3 text-right font-mono-sm">{turn.durationMs == null ? '—' : formatMs(turn.durationMs)}</td>
                <td className="whitespace-nowrap px-4 py-3 text-right font-mono-sm">{turn.totalTokens == null ? '—' : formatNumber(turn.totalTokens)}</td>
                <td className="px-4 py-3 md:px-5"><span className={`inline-flex items-center gap-1.5 font-mono-sm text-mono-sm ${isError ? 'text-error' : 'text-secondary'}`}><span className={`h-1.5 w-1.5 ${isError ? 'bg-error' : 'bg-secondary'}`} />{turn.finishReason || 'unknown'}</span></td>
              </tr>
            );
          })}
          {metrics.recent.length === 0 && <tr><td colSpan={5} className="px-5 py-8 text-center text-on-surface-variant">No recent turns recorded.</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

function DashboardLoading() {
  return (
    <div className="mt-5 flex min-h-64 items-center justify-center border border-border-subtle bg-surface-container-lowest text-on-surface-variant">
      <div className="flex items-center gap-3 font-mono-sm text-mono-sm uppercase"><Loader2 size={16} className="animate-spin text-secondary" /> Synchronizing recorder</div>
    </div>
  );
}

function EmptyDashboard({ langfuseUrl }: { langfuseUrl: string }) {
  return (
    <div className="mt-5 flex min-h-72 flex-col items-center justify-center border border-border-subtle bg-surface-container-lowest px-6 text-center">
      <div className="grid h-12 w-12 place-items-center border border-secondary/40 bg-secondary/10 text-secondary"><Activity size={22} /></div>
      <h2 className="mt-5 font-headline-lg text-headline-lg font-semibold text-on-surface">Waiting for the first AI turn</h2>
      <p className="mt-2 max-w-lg text-body-sm text-on-surface-variant">Start a workflow-AI conversation. Voyager will record model, latency, token, and finish-reason telemetry here automatically.</p>
      <a href={langfuseUrl} target="_blank" rel="noopener noreferrer" className="mt-5 inline-flex items-center gap-2 border border-border-subtle px-4 py-2 text-body-sm font-medium text-on-surface hover:border-secondary/50 hover:text-secondary">Open Langfuse <ArrowUpRight size={14} /></a>
    </div>
  );
}

function formatMs(ms: number): string {
  return ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1)} s`;
}

function formatNumber(value: number): string {
  return new Intl.NumberFormat().format(value);
}

function formatCompact(value: number): string {
  return new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(value);
}

function formatPercent(value: number, total: number): string {
  return total ? `${((value / total) * 100).toFixed(1)}%` : '0%';
}

function formatTimestamp(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function formatRelativeTime(value: string): string {
  const time = new Date(value).getTime();
  if (Number.isNaN(time)) return 'Signal recorded';
  const seconds = Math.max(0, Math.round((Date.now() - time) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86400)}d ago`;
}
