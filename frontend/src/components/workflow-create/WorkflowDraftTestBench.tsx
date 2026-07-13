import { useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  ArrowRight,
  Check,
  CircleStop,
  FlaskConical,
  Loader2,
  Play,
  RotateCcw,
} from 'lucide-react';
import {
  testDraftWorkflowState,
  type DraftStateTestResponse,
} from '../../api';
import type { AslDefinition } from './stateBuilder';

type Props = {
  definition: AslDefinition;
  selectedStateName: string;
  onStateSelect: (stateName: string) => void;
};

type TraceEntry = DraftStateTestResponse & { id: number };

const EMPTY_JSON = '{}';

function formatJson(value: unknown) {
  return JSON.stringify(value ?? {}, null, 2);
}

function parseJson(value: string, label: string) {
  try {
    return JSON.parse(value);
  } catch {
    throw new Error(`${label} must be valid JSON.`);
  }
}

function statusTone(status: DraftStateTestResponse['status']) {
  if (status === 'FAILED') return 'border-status-error/35 bg-status-error/10 text-status-error';
  if (status === 'TASK_PREVIEW') return 'border-amber-400/30 bg-amber-400/10 text-amber-200';
  if (status === 'WAITING') return 'border-sky-400/30 bg-sky-400/10 text-sky-200';
  return 'border-secondary/35 bg-secondary-container/40 text-secondary-fixed';
}

export function WorkflowDraftTestBench({
  definition,
  selectedStateName,
  onStateSelect,
}: Props) {
  const stateNames = useMemo(
    () => Object.keys(definition.States || {}),
    [definition.States],
  );
  const fallbackState = definition.StartAt && stateNames.includes(definition.StartAt)
    ? definition.StartAt
    : stateNames[0] || '';
  const activeStateName = stateNames.includes(selectedStateName)
    ? selectedStateName
    : fallbackState;
  const activeState = activeStateName
    ? definition.States?.[activeStateName] || null
    : null;
  const isTask = activeState?.Type === 'Task';

  const [inputText, setInputText] = useState(EMPTY_JSON);
  const [variablesText, setVariablesText] = useState(EMPTY_JSON);
  const [outputText, setOutputText] = useState(EMPTY_JSON);
  const [result, setResult] = useState<DraftStateTestResponse | null>(null);
  const [trace, setTrace] = useState<TraceEntry[]>([]);
  const [executeTask, setExecuteTask] = useState(false);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!selectedStateName && fallbackState) {
      onStateSelect(fallbackState);
    }
  }, [fallbackState, onStateSelect, selectedStateName]);

  useEffect(() => {
    setResult(null);
    setOutputText(EMPTY_JSON);
    setError(null);
    setExecuteTask(false);
  }, [activeStateName]);

  const runState = async () => {
    if (!activeStateName) {
      setError('Add or select a state before testing.');
      return;
    }

    setRunning(true);
    setError(null);
    try {
      const input = parseJson(inputText, 'State input');
      const variables = parseJson(variablesText, 'Variables');
      if (!variables || typeof variables !== 'object' || Array.isArray(variables)) {
        throw new Error('Variables must be a JSON object.');
      }
      const response = await testDraftWorkflowState({
        definition,
        stateName: activeStateName,
        input,
        variables,
        executeTask: isTask && executeTask,
      });
      setResult(response);
      setOutputText(formatJson(response.output ?? response.taskArguments ?? {}));
      setVariablesText(formatJson(response.variables || {}));
      setTrace((current) => [
        ...current,
        { ...response, id: Date.now() + current.length },
      ]);
    } catch (caught: any) {
      setError(caught?.message || 'Draft state test failed.');
    } finally {
      setRunning(false);
    }
  };

  const useOutputForNextState = () => {
    try {
      const shapedOutput = parseJson(outputText, 'State output');
      setInputText(formatJson(shapedOutput));
      if (result?.nextStateName && stateNames.includes(result.nextStateName)) {
        onStateSelect(result.nextStateName);
      } else if (result?.nextStateName) {
        setError(`Output is ready, but ${result.nextStateName} is not in the draft yet.`);
      }
    } catch (caught: any) {
      setError(caught?.message || 'State output must be valid JSON.');
    }
  };

  const loadTrace = (entry: TraceEntry) => {
    setResult(entry);
    setOutputText(formatJson(entry.output ?? entry.taskArguments ?? {}));
    setError(null);
    if (stateNames.includes(entry.stateName)) onStateSelect(entry.stateName);
  };

  return (
    <section className="grid h-[330px] shrink-0 grid-cols-[190px_minmax(0,1fr)_minmax(0,1fr)] border-t border-border-subtle bg-surface-lowest">
      <div className="flex min-h-0 flex-col border-r border-border-subtle bg-surface-container-low/45">
        <div className="flex h-11 shrink-0 items-center justify-between border-b border-border-subtle px-4">
          <div className="flex items-center gap-2 font-mono-sm text-[11px] uppercase tracking-[0.08em] text-on-surface">
            <FlaskConical size={14} className="text-secondary" />
            Test trail
          </div>
          {trace.length > 0 && (
            <button
              type="button"
              onClick={() => setTrace([])}
              className="text-on-surface-variant transition-colors hover:text-on-surface"
              title="Clear test trail"
            >
              <RotateCcw size={13} />
            </button>
          )}
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto p-2">
          {trace.length === 0 ? (
            <div className="px-2 py-5 text-[11px] leading-5 text-on-surface-variant">
              Each state you test appears here. Nothing is added to execution history.
            </div>
          ) : trace.map((entry, index) => (
            <button
              key={entry.id}
              type="button"
              onClick={() => loadTrace(entry)}
              className="group relative flex w-full items-start gap-2 rounded-DEFAULT px-2 py-2.5 text-left transition-colors hover:bg-surface-container"
            >
              {index < trace.length - 1 && (
                <span className="absolute left-[15px] top-7 h-[calc(100%-14px)] w-px bg-border-subtle" />
              )}
              <span className={`relative z-10 mt-0.5 flex h-3.5 w-3.5 shrink-0 items-center justify-center rounded-full border ${statusTone(entry.status)}`}>
                {entry.status === 'FAILED' ? <CircleStop size={8} /> : <Check size={8} />}
              </span>
              <span className="min-w-0">
                <span className="block truncate font-mono-sm text-[11px] text-on-surface">{entry.stateName}</span>
                <span className="mt-0.5 block truncate text-[10px] text-on-surface-variant">
                  {entry.status === 'TASK_PREVIEW' ? 'arguments ready' : entry.nextStateName ? `to ${entry.nextStateName}` : entry.status.toLowerCase()}
                </span>
              </span>
            </button>
          ))}
        </div>
      </div>

      <div className="flex min-h-0 flex-col border-r border-border-subtle">
        <div className="flex h-11 shrink-0 items-center gap-2 border-b border-border-subtle px-4">
          <span className="font-mono-sm text-[11px] uppercase tracking-[0.08em] text-on-surface-variant">Input for</span>
          <select
            value={activeStateName}
            onChange={(event) => onStateSelect(event.target.value)}
            className="min-w-0 flex-1 bg-transparent font-mono-sm text-[12px] text-on-surface outline-none"
          >
            {stateNames.map((stateName) => (
              <option key={stateName} value={stateName}>{stateName}</option>
            ))}
          </select>
          <span className="rounded-full border border-border-subtle px-2 py-0.5 font-mono-sm text-[9px] text-on-surface-variant">
            {activeState?.Type || '—'}
          </span>
        </div>
        <textarea
          value={inputText}
          onChange={(event) => setInputText(event.target.value)}
          spellCheck={false}
          aria-label="Draft state input JSON"
          className="min-h-0 flex-1 resize-none bg-transparent p-4 font-mono text-[12px] leading-5 text-on-surface outline-none"
        />
        <div className="flex h-12 shrink-0 items-center justify-between border-t border-border-subtle px-4">
          {isTask ? (
            <label className="flex items-center gap-2 text-[10px] text-on-surface-variant">
              <input
                type="checkbox"
                checked={executeTask}
                onChange={(event) => setExecuteTask(event.target.checked)}
                className="accent-primary"
              />
              Allow task side effects
            </label>
          ) : (
            <span className="text-[10px] text-on-surface-variant">No execution record will be saved.</span>
          )}
          <button
            type="button"
            onClick={runState}
            disabled={running || !activeStateName}
            className="flex h-8 items-center gap-2 rounded-DEFAULT bg-secondary px-3 font-body-sm text-[11px] font-semibold text-on-secondary transition-colors hover:bg-secondary-fixed-dim disabled:cursor-not-allowed disabled:opacity-50"
          >
            {running ? <Loader2 size={13} className="animate-spin" /> : <Play size={12} fill="currentColor" />}
            {isTask && !executeTask ? 'Preview task' : 'Run state'}
          </button>
        </div>
      </div>

      <div className="flex min-h-0 flex-col">
        <div className="flex h-11 shrink-0 items-center justify-between border-b border-border-subtle px-4">
          <div className="flex items-center gap-2">
            <span className="font-mono-sm text-[11px] uppercase tracking-[0.08em] text-on-surface-variant">Output</span>
            {result && (
              <span className={`rounded-full border px-2 py-0.5 font-mono-sm text-[9px] ${statusTone(result.status)}`}>
                {result.status.replace('_', ' ')}
              </span>
            )}
          </div>
          {result?.durationMs != null && (
            <span className="font-mono-sm text-[10px] text-on-surface-variant">{result.durationMs} ms</span>
          )}
        </div>

        {(error || result?.status === 'FAILED') && (
          <div className="flex items-start gap-2 border-b border-status-error/20 bg-status-error/10 px-4 py-2 text-[10px] text-status-error">
            <AlertTriangle size={13} className="mt-0.5 shrink-0" />
            <span>{error || [result?.error, result?.cause].filter(Boolean).join(' — ')}</span>
          </div>
        )}

        {result?.status === 'TASK_PREVIEW' && (
          <div className="border-b border-amber-400/20 bg-amber-400/5 px-4 py-2 text-[10px] text-amber-100/80">
            Showing evaluated task arguments. Edit this as mock output, or enable task side effects and run again.
          </div>
        )}

        <textarea
          value={outputText}
          onChange={(event) => setOutputText(event.target.value)}
          spellCheck={false}
          aria-label="Draft state output JSON"
          className="min-h-0 flex-1 resize-none bg-transparent p-4 font-mono text-[12px] leading-5 text-on-surface outline-none"
        />
        <div className="flex h-12 shrink-0 items-center justify-between border-t border-border-subtle px-4">
          <div className="min-w-0 truncate text-[10px] text-on-surface-variant">
            {result?.wakeAt
              ? `Would resume ${new Date(result.wakeAt).toLocaleString()}`
              : result?.taskResource || (result?.nextStateName ? `Next: ${result.nextStateName}` : 'Run a state to inspect its output.')}
          </div>
          <button
            type="button"
            onClick={useOutputForNextState}
            disabled={!result || result.status === 'FAILED'}
            className="ml-3 flex h-8 shrink-0 items-center gap-2 rounded-DEFAULT border border-secondary/45 px-3 font-body-sm text-[11px] font-medium text-secondary transition-colors hover:bg-secondary-container/35 disabled:cursor-not-allowed disabled:opacity-40"
          >
            Use as next input
            <ArrowRight size={13} />
          </button>
        </div>
      </div>
    </section>
  );
}
