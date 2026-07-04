import { useState } from 'react';
import { Loader2, Play } from 'lucide-react';
import { testInvokeFunction, type FunctionInvocationDTO, type FunctionVersionDTO } from '../../api';

type Props = {
  functionId: string;
  versions: FunctionVersionDTO[];
  activeVersion: number | null;
  onInvoked: () => void;
};

const statusTone: Record<string, string> = {
  SUCCEEDED: 'border-secondary/40 bg-secondary/10 text-secondary',
  FAILED: 'border-status-error/40 bg-status-error/10 text-status-error',
  RUNNING: 'border-status-info/40 bg-status-info/10 text-status-info',
};

export function FunctionTestPanel({ functionId, versions, activeVersion, onInvoked }: Props) {
  const [versionChoice, setVersionChoice] = useState('active');
  const [inputText, setInputText] = useState('{\n  "amount": 100\n}');
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<FunctionInvocationDTO | null>(null);

  const run = async () => {
    if (running) return;
    let input: unknown = {};
    const trimmed = inputText.trim();
    if (trimmed) {
      try {
        input = JSON.parse(trimmed);
      } catch {
        setError('Input must be valid JSON.');
        return;
      }
    }
    setRunning(true);
    setError(null);
    try {
      const invocation = await testInvokeFunction(functionId, {
        version: versionChoice === 'active' ? undefined : Number(versionChoice),
        input,
      });
      setResult(invocation);
      onInvoked();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setRunning(false);
    }
  };

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <div>
        <div className="mb-2 flex items-center justify-between">
          <span className="font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant/70">Input JSON</span>
          <select
            value={versionChoice}
            onChange={(event) => setVersionChoice(event.target.value)}
            className="h-7 rounded-md border border-border-subtle bg-surface-container-lowest px-2 font-mono-sm text-[11px] text-on-surface outline-none focus:border-primary/50"
          >
            <option value="active">Active{activeVersion ? ` (v${activeVersion})` : ''}</option>
            {versions.map((version) => (
              <option key={version.id} value={version.version}>
                v{version.version}
              </option>
            ))}
          </select>
        </div>
        <textarea
          value={inputText}
          onChange={(event) => setInputText(event.target.value)}
          spellCheck={false}
          className="min-h-[220px] w-full resize-y rounded-lg border border-border-subtle bg-surface-container-lowest p-3 font-mono-sm text-[12px] leading-relaxed text-on-surface outline-none transition-colors focus:border-primary/50"
        />
        <button
          type="button"
          onClick={run}
          disabled={running}
          className="mt-3 flex h-9 items-center gap-2 rounded-lg border border-primary bg-primary px-4 font-body-sm text-body-sm font-medium text-on-primary transition-colors hover:bg-primary-fixed-dim disabled:cursor-not-allowed disabled:opacity-50"
        >
          {running ? <Loader2 size={15} className="animate-spin" /> : <Play size={15} />}
          Run test
        </button>
        {error && <p className="mt-2 font-mono-sm text-[11px] text-status-error">{error}</p>}
      </div>

      <div>
        <span className="mb-2 block font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant/70">Result</span>
        {!result ? (
          <div className="flex min-h-[220px] items-center justify-center rounded-lg border border-dashed border-border-subtle text-body-sm text-on-surface-variant/70">
            Run the function to see output
          </div>
        ) : (
          <div className="space-y-3 rounded-lg border border-border-subtle bg-surface-container-lowest p-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className={`rounded-full border px-2.5 py-0.5 font-mono-sm text-[10px] uppercase tracking-[0.06em] ${statusTone[result.status] || statusTone.RUNNING}`}>
                {result.status}
              </span>
              <span className="font-mono-sm text-[11px] text-on-surface-variant">v{result.version}</span>
              {result.timeSeconds != null && <Meta label={`${result.timeSeconds}s`} />}
              {result.memoryKb != null && <Meta label={`${result.memoryKb} KB`} />}
              {result.exitCode != null && <Meta label={`exit ${result.exitCode}`} />}
            </div>
            {result.errorName && (
              <Block title="Error" tone="error" body={`${result.errorName}${result.errorMessage ? `: ${result.errorMessage}` : ''}`} />
            )}
            {result.output != null && <Block title="Output" tone="ok" body={JSON.stringify(result.output, null, 2)} />}
            {result.stdout && <Block title="stdout" body={result.stdout} />}
            {result.stderr && <Block title="stderr" tone="error" body={result.stderr} />}
            {result.compileOutput && <Block title="compile output" tone="error" body={result.compileOutput} />}
          </div>
        )}
      </div>
    </div>
  );
}

function Meta({ label }: { label: string }) {
  return <span className="rounded border border-border-subtle px-1.5 py-0.5 font-mono-sm text-[10px] text-on-surface-variant">{label}</span>;
}

function Block({ title, body, tone }: { title: string; body: string; tone?: 'ok' | 'error' }) {
  const color = tone === 'ok' ? 'text-secondary' : tone === 'error' ? 'text-status-error' : 'text-on-surface-variant';
  return (
    <div>
      <div className={`mb-1 font-mono-sm text-[10px] uppercase tracking-[0.06em] ${color}`}>{title}</div>
      <pre className="max-h-56 overflow-auto rounded-md border border-border-subtle bg-surface-base p-2.5 font-mono-sm text-[11px] leading-relaxed text-on-surface">{body}</pre>
    </div>
  );
}
