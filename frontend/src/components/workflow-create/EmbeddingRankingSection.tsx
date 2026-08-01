import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Activity, ChevronDown, Info, Loader2, Play, Trophy } from 'lucide-react';
import {
  getEmbeddingRankingLatest,
  listAllAiModels,
  startEmbeddingRanking,
  type AiModelConfigDTO,
  type EmbeddingRankingModelResult,
  type EmbeddingRankingRun,
} from '../../api';

/**
 * Ranks registered embedding models by retrieval quality. Self-contained: fetches the latest run
 * and the embedding-model list on mount, runs a new one on demand, and polls while a run is in
 * progress. Models registered since the last run are surfaced under "Not tested".
 */
export function EmbeddingRankingSection() {
  const [run, setRun] = useState<EmbeddingRankingRun | null>(null);
  const [models, setModels] = useState<AiModelConfigDTO[]>([]);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pollRef = useRef<number | null>(null);

  const refresh = useCallback(async () => {
    const latest = await getEmbeddingRankingLatest();
    setRun(latest);
    return latest;
  }, []);

  const refreshModels = useCallback(async () => {
    setModels(await listAllAiModels());
  }, []);

  useEffect(() => {
    void refresh().catch((e) => setError(e instanceof Error ? e.message : 'Could not load ranking.'));
    void refreshModels().catch(() => undefined);
    return () => {
      if (pollRef.current) window.clearInterval(pollRef.current);
    };
  }, [refresh, refreshModels]);

  // Poll every 2s while a run is in progress.
  useEffect(() => {
    if (run?.status !== 'RUNNING') {
      if (pollRef.current) {
        window.clearInterval(pollRef.current);
        pollRef.current = null;
      }
      return;
    }
    if (pollRef.current) return;
    pollRef.current = window.setInterval(() => {
      void refresh().catch(() => undefined);
    }, 2000);
    return () => {
      if (pollRef.current) {
        window.clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [run?.status, refresh]);

  const start = async () => {
    setStarting(true);
    setError(null);
    try {
      setRun(await startEmbeddingRanking());
      void refreshModels().catch(() => undefined);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not start ranking.');
    } finally {
      setStarting(false);
    }
  };

  const running = run?.status === 'RUNNING';
  const result = run?.status === 'COMPLETED' ? run.result : null;

  // Enabled embedding models that weren't part of the latest completed run.
  const untested = useMemo(() => {
    const tested = new Set((result?.models ?? []).map((m) => m.modelId));
    return models.filter(
      (m) => m.role === 'EMBEDDING' && m.enabled !== false && !tested.has(m.id),
    );
  }, [models, result]);

  return (
    <section className="rounded-lg border border-primary/20 bg-surface-base p-4">
      <div className="flex items-start justify-between gap-4 border-b border-border-subtle/40 pb-3">
        <div className="flex items-center gap-3">
          <Activity size={18} className="text-primary" />
          <div>
            <h3 className="font-headline-md text-headline-md font-semibold text-primary">Embedding Ranking</h3>
            <p className="mt-1 max-w-2xl text-body-sm text-on-surface-variant">
              Scores each embedding model on <em>retrieval</em> quality and ranks them, so you can choose
              the best one as the default for catalog matching.
            </p>
          </div>
        </div>
        <button
          type="button"
          onClick={start}
          disabled={starting || running}
          className="flex h-9 shrink-0 items-center gap-2 rounded-DEFAULT bg-primary px-3 text-body-sm font-medium text-surface-lowest transition-colors hover:bg-primary-fixed disabled:cursor-not-allowed disabled:opacity-50"
        >
          {starting || running ? <Loader2 size={14} className="animate-spin" /> : <Play size={14} />}
          {running ? 'Ranking…' : starting ? 'Starting…' : 'Run ranking'}
        </button>
      </div>

      <HowEmbeddingRankingWorks />

      {error && (
        <div className="mt-3 rounded-DEFAULT border border-status-error/30 bg-status-error/10 px-3 py-2 text-body-sm text-status-error">
          {error}
        </div>
      )}

      {running && (
        <div className="mt-4 flex items-center gap-2 rounded-DEFAULT border border-primary/20 bg-surface-container-lowest px-3 py-3 text-body-sm text-on-surface-variant">
          <Loader2 size={14} className="animate-spin text-primary" />
          Generating evaluation queries and scoring each embedding model in memory…
        </div>
      )}

      {run?.status === 'FAILED' && (
        <div className="mt-3 rounded-DEFAULT border border-status-error/30 bg-status-error/10 px-3 py-2 text-body-sm text-status-error">
          {run.error || 'The ranking run failed.'}
        </div>
      )}

      {!result && !running && !error && (
        <div className="mt-4 rounded-DEFAULT border border-border-subtle/60 bg-surface-container-lowest p-5 text-body-sm text-on-surface-variant">
          No ranking yet. Run one to compare your embedding models. Needs at least two catalog resources
          and one embedding model; a chat model generates the evaluation queries.
        </div>
      )}

      {result && <RankingTable result={result} />}

      {untested.length > 0 && (
        <div className="mt-4">
          <div className="mb-2 font-mono-sm text-[11px] font-semibold uppercase tracking-normal text-on-surface-variant">
            Not tested <span className="text-on-surface-variant/60">({untested.length})</span>
          </div>
          <div className="space-y-2 rounded-DEFAULT border border-border-subtle/60 bg-surface-container-lowest p-2">
            {untested.map((model) => (
              <div
                key={model.id}
                className="flex items-center justify-between gap-3 rounded-DEFAULT border border-border-subtle/50 bg-surface-base px-3 py-2"
              >
                <span className="truncate font-mono-sm text-[12px] text-on-surface">{model.displayName || model.modelName}</span>
                <span className="shrink-0 font-mono-sm text-[10px] uppercase text-on-surface-variant">
                  Run ranking to score
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </section>
  );
}

function HowEmbeddingRankingWorks() {
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
            The default chat model writes one natural-language query for each function and MCP tool in your
            catalog (cached, and regenerated only when a resource changes). Then every enabled embedding
            model embeds the whole catalog and those queries <span className="text-on-surface">in memory</span> —
            it never touches the stored vectors, so models of different dimensions compete fairly.
          </p>
        </section>

        <section>
          <h4 className="font-mono-sm text-[10px] font-semibold uppercase tracking-wide text-on-surface">What is measured</h4>
          <p className="mt-1">
            For each query the model ranks all resources by similarity; the metrics score where the
            <span className="text-on-surface"> correct</span> resource landed.
          </p>
          <div className="mt-2 space-y-1.5">
            {[
              ['recall@1', 'How often the correct resource was ranked #1 (higher is better).'],
              ['recall@k', 'How often the correct resource was in the top-k Voyager actually sends to the model.'],
              ['MRR', 'Mean reciprocal rank — rewards ranking the right resource near the top overall.'],
              ['Latency', 'Average time to embed one text — the speed cost per turn.'],
              ['Dims', 'Vector dimension. A model whose dimension differs from the stored column needs a migration to become the production default.'],
            ].map(([label, description]) => (
              <div key={label} className="flex gap-2">
                <span className="mt-px w-20 shrink-0 font-mono-sm text-[10px] font-semibold uppercase text-primary">{label}</span>
                <span className="min-w-0 flex-1">{description}</span>
              </div>
            ))}
          </div>
        </section>

        <section>
          <h4 className="font-mono-sm text-[10px] font-semibold uppercase tracking-wide text-on-surface">Requirements</h4>
          <p className="mt-1">
            Needs at least two catalog resources, one embedding model, and one chat model (to write the
            queries). Models added since the last run appear under <span className="text-on-surface">Not tested</span> until
            you run ranking again.
          </p>
        </section>
      </div>
    </details>
  );
}

function RankingTable({ result }: { result: NonNullable<EmbeddingRankingRun['result']> }) {
  const pct = (v: number | null) => (v == null ? '—' : `${Math.round(v * 100)}%`);
  const num = (v: number | null, digits = 2) => (v == null ? '—' : v.toFixed(digits));
  const ms = (v: number | null) => (v == null ? '—' : `${Math.round(v)}ms`);

  return (
    <div className="mt-4">
      <div className="mb-2 flex flex-wrap items-center gap-x-2 gap-y-1 font-mono-sm text-[10px] text-on-surface-variant">
        <span>{result.models.length} model{result.models.length === 1 ? '' : 's'}</span>
        <span className="text-border-muted">·</span>
        <span>{result.catalogSize} resources</span>
        <span className="text-border-muted">·</span>
        <span>{result.totalQueries} queries</span>
        <span className="text-border-muted">·</span>
        <span>k={result.k}</span>
      </div>
      <div className="overflow-x-auto rounded-DEFAULT border border-primary/20">
        <table className="w-full border-collapse text-body-sm">
          <thead>
            <tr className="bg-surface-container-lowest text-left font-mono-sm text-[10px] uppercase text-on-surface-variant">
              <th className="px-3 py-2 font-semibold">#</th>
              <th className="px-3 py-2 font-semibold">Model</th>
              <th className="px-3 py-2 font-semibold" title="Correct resource ranked #1">recall@1</th>
              <th className="px-3 py-2 font-semibold" title={`Correct resource in top ${result.k}`}>recall@{result.k}</th>
              <th className="px-3 py-2 font-semibold" title="Mean reciprocal rank">MRR</th>
              <th className="px-3 py-2 font-semibold">Latency</th>
              <th className="px-3 py-2 font-semibold">Dims</th>
            </tr>
          </thead>
          <tbody>
            {result.models.map((model, index) => (
              <ModelRow key={model.modelId} model={model} index={index} pct={pct} num={num} ms={ms} />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function ModelRow({
  model,
  index,
  pct,
  num,
  ms,
}: {
  model: EmbeddingRankingModelResult;
  index: number;
  pct: (v: number | null) => string;
  num: (v: number | null, digits?: number) => string;
  ms: (v: number | null) => string;
}) {
  if (model.error) {
    return (
      <tr className="border-t border-border-subtle/50">
        <td className="px-3 py-2 font-mono-sm text-[11px] text-on-surface-variant">—</td>
        <td className="px-3 py-2 font-mono-sm text-[12px] text-on-surface">{model.displayName}</td>
        <td className="px-3 py-2 text-[11px] text-status-error" colSpan={5}>{model.error}</td>
      </tr>
    );
  }
  const isTop = index === 0;
  return (
    <tr className="border-t border-border-subtle/50">
      <td className="px-3 py-2">
        {isTop
          ? <Trophy size={13} className="text-status-warning" />
          : <span className="font-mono-sm text-[11px] text-on-surface-variant">{String(index + 1).padStart(2, '0')}</span>}
      </td>
      <td className="px-3 py-2">
        <span className={`font-mono-sm text-[12px] ${isTop ? 'font-semibold text-primary' : 'text-on-surface'}`}>
          {model.displayName}
        </span>
      </td>
      <td className="px-3 py-2 font-mono-sm text-[12px] tabular-nums text-on-surface">{pct(model.recallAt1)}</td>
      <td className="px-3 py-2 font-mono-sm text-[12px] tabular-nums text-on-surface">{pct(model.recallAtK)}</td>
      <td className="px-3 py-2 font-mono-sm text-[12px] tabular-nums text-on-surface">{num(model.mrr)}</td>
      <td className="px-3 py-2 font-mono-sm text-[12px] tabular-nums text-on-surface-variant">{ms(model.avgLatencyMs)}</td>
      <td className="px-3 py-2 font-mono-sm text-[12px] tabular-nums text-on-surface-variant">{model.dimensions ?? '—'}</td>
    </tr>
  );
}
