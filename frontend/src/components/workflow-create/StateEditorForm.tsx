import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { getStateVisual } from '../../utils/stateVisuals';
import {
  isValidStateName,
  stateTypeOf,
  supportsAssign,
  supportsNextOrEnd,
  supportsOutput,
  supportsRetryCatch,
  type AslState,
} from './stateBuilder';

const labelClass = 'block font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant';
const hintClass = 'mt-1 font-body-sm text-[11px] text-on-surface-variant/70';
const errorClass = 'mt-1 font-body-sm text-[11px] text-status-error';
const sectionClass = 'border-b border-border-subtle px-6 py-5';

const END_OPTION = '__end__';
const NONE_OPTION = '__none__';

type ParseResult =
  | { ok: true; value: unknown | undefined }
  | { ok: false; error: string };

function parseJsonOrExpression(text: string, options: { requireObject?: boolean; requireArray?: boolean } = {}): ParseResult {
  const trimmed = text.trim();
  if (!trimmed) {
    return { ok: true, value: undefined };
  }
  if (trimmed.startsWith('{%') && trimmed.endsWith('%}')) {
    return { ok: true, value: trimmed };
  }
  try {
    const value = JSON.parse(trimmed);
    if (options.requireObject && (typeof value !== 'object' || value === null || Array.isArray(value))) {
      return { ok: false, error: 'Expected a JSON object.' };
    }
    if (options.requireArray && !Array.isArray(value)) {
      return { ok: false, error: 'Expected a JSON array.' };
    }
    return { ok: true, value };
  } catch {
    return { ok: false, error: 'Enter valid JSON or a {% ... %} JSONata expression.' };
  }
}

function parseIntegerOrExpression(text: string, options: { min?: number } = {}): ParseResult {
  const trimmed = text.trim();
  if (!trimmed) {
    return { ok: true, value: undefined };
  }
  if (trimmed.startsWith('{%') && trimmed.endsWith('%}')) {
    return { ok: true, value: trimmed };
  }
  if (!/^\d+$/.test(trimmed)) {
    return { ok: false, error: 'Enter a whole number or a {% ... %} expression.' };
  }
  const value = Number(trimmed);
  if (options.min !== undefined && value < options.min) {
    return { ok: false, error: `Must be at least ${options.min}.` };
  }
  return { ok: true, value };
}

function stringifyValue(value: unknown): string {
  if (value === undefined) return '';
  if (typeof value === 'string') return value;
  return JSON.stringify(value, null, 2);
}

type JsonFieldProps = {
  label: string;
  value: unknown;
  onCommit: (value: unknown | undefined) => void;
  hint?: string;
  placeholder?: string;
  rows?: number;
  requireObject?: boolean;
  requireArray?: boolean;
  monoFieldClass: string;
};

function JsonField({ label, value, onCommit, hint, placeholder, rows = 4, requireObject, requireArray, monoFieldClass }: JsonFieldProps) {
  const [text, setText] = useState(() => stringifyValue(value));
  const [error, setError] = useState<string | null>(null);

  const handleChange = (nextText: string) => {
    setText(nextText);
    const result = parseJsonOrExpression(nextText, { requireObject, requireArray });
    if (result.ok) {
      setError(null);
      onCommit(result.value);
    } else {
      setError(result.error);
    }
  };

  return (
    <div>
      <label className={labelClass}>{label}</label>
      <textarea
        value={text}
        onChange={(event) => handleChange(event.target.value)}
        placeholder={placeholder}
        rows={rows}
        spellCheck={false}
        className={`${monoFieldClass} h-auto min-h-[64px] resize-y py-2 leading-relaxed ${error ? 'border-status-error' : ''}`}
      />
      {error ? <p className={errorClass}>{error}</p> : hint ? <p className={hintClass}>{hint}</p> : null}
    </div>
  );
}

type IntegerFieldProps = {
  label: string;
  value: unknown;
  onCommit: (value: unknown | undefined) => void;
  hint?: string;
  placeholder?: string;
  min?: number;
  monoFieldClass: string;
};

function IntegerField({ label, value, onCommit, hint, placeholder, min, monoFieldClass }: IntegerFieldProps) {
  const [text, setText] = useState(() => stringifyValue(value));
  const [error, setError] = useState<string | null>(null);

  const handleChange = (nextText: string) => {
    setText(nextText);
    const result = parseIntegerOrExpression(nextText, { min });
    if (result.ok) {
      setError(null);
      onCommit(result.value);
    } else {
      setError(result.error);
    }
  };

  return (
    <div>
      <label className={labelClass}>{label}</label>
      <input
        value={text}
        onChange={(event) => handleChange(event.target.value)}
        placeholder={placeholder}
        spellCheck={false}
        className={`${monoFieldClass} ${error ? 'border-status-error' : ''}`}
      />
      {error ? <p className={errorClass}>{error}</p> : hint ? <p className={hintClass}>{hint}</p> : null}
    </div>
  );
}

type TargetSelectProps = {
  label?: string;
  value: string;
  targets: string[];
  onChange: (value: string) => void;
  allowEnd?: boolean;
  allowNone?: boolean;
  fieldClass: string;
};

function TargetSelect({ label, value, targets, onChange, allowEnd, allowNone, fieldClass }: TargetSelectProps) {
  const missing = value && value !== END_OPTION && !targets.includes(value);

  return (
    <div className="flex-1">
      {label && <label className={labelClass}>{label}</label>}
      <select
        value={value || (allowNone ? NONE_OPTION : '')}
        onChange={(event) => onChange(event.target.value)}
        className={`${fieldClass} appearance-none ${missing ? 'border-status-error' : ''}`}
      >
        {allowNone && <option value={NONE_OPTION}>None</option>}
        {!allowNone && !value && <option value="">Select state...</option>}
        {allowEnd && <option value={END_OPTION}>End workflow</option>}
        {missing && <option value={value}>{value} (missing)</option>}
        {targets.map((target) => (
          <option key={target} value={target}>{target}</option>
        ))}
      </select>
      {missing && <p className={errorClass}>Target state "{value}" does not exist.</p>}
    </div>
  );
}

type Props = {
  name: string;
  state: AslState;
  stateNames: string[];
  isStart: boolean;
  onChange: (state: AslState) => void;
  onRename: (name: string) => void;
  onDelete: () => void;
  onSetStart: () => void;
  fieldClass: string;
  monoFieldClass: string;
};

export function StateEditorForm({
  name,
  state,
  stateNames,
  isStart,
  onChange,
  onRename,
  onDelete,
  onSetStart,
  fieldClass,
  monoFieldClass,
}: Props) {
  const type = stateTypeOf(state);
  const visual = getStateVisual(type);
  const otherStates = stateNames.filter((stateName) => stateName !== name);
  const [nameDraft, setNameDraft] = useState(name);

  const setField = (field: string, value: unknown | undefined) => {
    const next = { ...state };
    if (value === undefined) {
      delete next[field];
    } else {
      next[field] = value;
    }
    onChange(next);
  };

  const commitRename = () => {
    const trimmed = nameDraft.trim();
    if (trimmed === name) {
      setNameDraft(name);
      return;
    }
    if (!isValidStateName(trimmed) || stateNames.includes(trimmed)) {
      setNameDraft(name);
      return;
    }
    onRename(trimmed);
  };

  const transitionValue = state.End === true ? END_OPTION : (typeof state.Next === 'string' ? state.Next : '');

  const handleTransitionChange = (value: string) => {
    const next = { ...state };
    if (value === END_OPTION) {
      delete next.Next;
      next.End = true;
    } else {
      delete next.End;
      next.Next = value;
    }
    onChange(next);
  };

  const choices: any[] = Array.isArray(state.Choices) ? state.Choices : [];

  const updateChoice = (index: number, patch: Record<string, unknown>) => {
    const nextChoices = choices.map((choice, choiceIndex) => (
      choiceIndex === index ? { ...choice, ...patch } : choice
    ));
    setField('Choices', nextChoices);
  };

  const retriers: any[] = Array.isArray(state.Retry) ? state.Retry : [];
  const catchers: any[] = Array.isArray(state.Catch) ? state.Catch : [];

  const updateArrayField = (field: 'Retry' | 'Catch', items: any[]) => {
    setField(field, items.length > 0 ? items : undefined);
  };

  const updateRetrier = (index: number, patch: Record<string, unknown>) => {
    updateArrayField('Retry', retriers.map((retrier, retrierIndex) => {
      if (retrierIndex !== index) return retrier;
      const next = { ...retrier, ...patch };
      for (const [key, value] of Object.entries(patch)) {
        if (value === undefined) delete next[key];
      }
      return next;
    }));
  };

  const updateCatcher = (index: number, patch: Record<string, unknown>) => {
    updateArrayField('Catch', catchers.map((catcher, catcherIndex) => {
      if (catcherIndex !== index) return catcher;
      const next = { ...catcher, ...patch };
      for (const [key, value] of Object.entries(patch)) {
        if (value === undefined) delete next[key];
      }
      return next;
    }));
  };

  const waitMode = state.Timestamp !== undefined ? 'Timestamp' : 'Seconds';

  const handleWaitModeChange = (mode: 'Seconds' | 'Timestamp') => {
    if (mode === waitMode) return;
    const next = { ...state };
    if (mode === 'Seconds') {
      delete next.Timestamp;
      next.Seconds = 60;
    } else {
      delete next.Seconds;
      next.Timestamp = new Date(Date.now() + 3600_000).toISOString().replace(/\.\d{3}Z$/, 'Z');
    }
    onChange(next);
  };

  const addRemoveButton = (onClick: () => void, ariaLabel: string) => (
    <button
      type="button"
      onClick={onClick}
      className="flex h-8 w-8 shrink-0 items-center justify-center rounded-DEFAULT border border-border-subtle text-on-surface-variant transition-colors hover:border-status-error hover:text-status-error"
      aria-label={ariaLabel}
      title={ariaLabel}
    >
      <Trash2 size={13} />
    </button>
  );

  const addItemButton = (label: string, onClick: () => void) => (
    <button
      type="button"
      onClick={onClick}
      className="mt-3 flex h-8 items-center gap-1.5 rounded-DEFAULT border border-dashed border-border-subtle px-3 font-mono-sm text-[11px] text-on-surface-variant transition-colors hover:border-secondary hover:text-secondary"
    >
      <Plus size={12} />
      {label}
    </button>
  );

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-y-auto">
      <div className={sectionClass}>
        <div className="flex items-center justify-between gap-3">
          <span className={`flex items-center gap-1.5 rounded border px-2 py-1 font-mono-sm text-[10px] ${visual.chipClass}`}>
            <span className="material-symbols-outlined text-[13px]">{visual.iconName}</span>
            {visual.label}
          </span>
          <div className="flex items-center gap-2">
            {isStart ? (
              <span className="rounded border border-secondary/35 bg-secondary-container/40 px-2 py-1 font-mono-sm text-[10px] text-secondary-fixed">
                START STATE
              </span>
            ) : (
              <button
                type="button"
                onClick={onSetStart}
                className="rounded border border-border-subtle px-2 py-1 font-mono-sm text-[10px] text-on-surface-variant transition-colors hover:border-secondary hover:text-secondary"
              >
                Set as start
              </button>
            )}
            {addRemoveButton(onDelete, `Delete state ${name}`)}
          </div>
        </div>

        <div className="mt-4">
          <label className={labelClass}>State name</label>
          <input
            value={nameDraft}
            onChange={(event) => setNameDraft(event.target.value)}
            onBlur={commitRename}
            onKeyDown={(event) => {
              if (event.key === 'Enter') (event.target as HTMLInputElement).blur();
            }}
            maxLength={80}
            spellCheck={false}
            className={monoFieldClass}
          />
          <p className={hintClass}>Renaming updates every transition that targets this state.</p>
        </div>

        <div className="mt-4">
          <label className={labelClass}>Comment</label>
          <input
            value={typeof state.Comment === 'string' ? state.Comment : ''}
            onChange={(event) => setField('Comment', event.target.value || undefined)}
            placeholder="Optional description"
            className={fieldClass}
          />
        </div>
      </div>

      {type === 'Task' && (
        <div className={sectionClass}>
          <div className="space-y-4">
            <div>
              <label className={labelClass}>Resource</label>
              <input
                value={typeof state.Resource === 'string' ? state.Resource : ''}
                onChange={(event) => setField('Resource', event.target.value)}
                placeholder="scheduler://queue/task"
                spellCheck={false}
                list="voyager-task-resource-schemes"
                className={monoFieldClass}
              />
              <datalist id="voyager-task-resource-schemes">
                <option value="scheduler://" />
                <option value="function://" />
                <option value="webhook://" />
                <option value="mcp://" />
              </datalist>
              <p className={hintClass}>URI of the resource this task invokes.</p>
            </div>
            <JsonField
              label="Arguments"
              value={state.Arguments}
              onCommit={(value) => setField('Arguments', value)}
              placeholder={'{\n  "orderId": "{% $states.input.orderId %}"\n}'}
              hint="JSON sent to the resource. Defaults to the state input."
              monoFieldClass={monoFieldClass}
            />
            <div className="grid grid-cols-2 gap-3">
              <IntegerField
                label="Timeout seconds"
                value={state.TimeoutSeconds}
                onCommit={(value) => setField('TimeoutSeconds', value)}
                placeholder="e.g. 30"
                min={1}
                monoFieldClass={monoFieldClass}
              />
              <IntegerField
                label="Heartbeat seconds"
                value={state.HeartbeatSeconds}
                onCommit={(value) => setField('HeartbeatSeconds', value)}
                placeholder="Optional"
                min={1}
                monoFieldClass={monoFieldClass}
              />
            </div>
          </div>
        </div>
      )}

      {type === 'Choice' && (
        <div className={sectionClass}>
          <label className={labelClass}>Choice rules</label>
          <p className={hintClass}>Evaluated in order; the first true condition wins.</p>
          <div className="mt-3 space-y-3">
            {choices.map((choice, index) => (
              <div key={index} className="rounded-DEFAULT border border-border-subtle p-3">
                <div className="flex items-start gap-2">
                  <div className="flex-1 space-y-2">
                    <input
                      value={typeof choice?.Condition === 'string' ? choice.Condition : ''}
                      onChange={(event) => updateChoice(index, { Condition: event.target.value })}
                      placeholder="{% $states.input.total > 1000 %}"
                      spellCheck={false}
                      className={monoFieldClass}
                    />
                    <TargetSelect
                      value={typeof choice?.Next === 'string' ? choice.Next : ''}
                      targets={otherStates}
                      onChange={(value) => updateChoice(index, { Next: value })}
                      fieldClass={fieldClass}
                    />
                  </div>
                  {addRemoveButton(
                    () => setField('Choices', choices.filter((_, choiceIndex) => choiceIndex !== index)),
                    `Remove rule ${index + 1}`,
                  )}
                </div>
              </div>
            ))}
          </div>
          {addItemButton('Add rule', () => setField('Choices', [...choices, { Condition: '{% true %}', Next: '' }]))}
          <div className="mt-4">
            <TargetSelect
              label="Default"
              value={typeof state.Default === 'string' ? state.Default : ''}
              targets={otherStates}
              onChange={(value) => setField('Default', value === NONE_OPTION ? undefined : value)}
              allowNone
              fieldClass={fieldClass}
            />
            <p className={hintClass}>Taken when no rule matches. Without it, the workflow fails with States.NoChoiceMatched.</p>
          </div>
        </div>
      )}

      {type === 'Wait' && (
        <div className={sectionClass}>
          <label className={labelClass}>Wait for</label>
          <div className="mt-2 grid grid-cols-2 gap-2">
            {(['Seconds', 'Timestamp'] as const).map((mode) => (
              <button
                key={mode}
                type="button"
                onClick={() => handleWaitModeChange(mode)}
                className={`h-9 rounded-DEFAULT border font-mono-sm text-[11px] transition-colors ${waitMode === mode
                  ? 'border-secondary bg-secondary-container/40 text-secondary-fixed'
                  : 'border-border-subtle text-on-surface-variant hover:text-on-surface'}`}
              >
                {mode === 'Seconds' ? 'Duration' : 'Timestamp'}
              </button>
            ))}
          </div>
          <div className="mt-3">
            {waitMode === 'Seconds' ? (
              <IntegerField
                label="Seconds"
                value={state.Seconds}
                onCommit={(value) => setField('Seconds', value ?? 0)}
                placeholder="e.g. 300"
                min={0}
                monoFieldClass={monoFieldClass}
              />
            ) : (
              <div>
                <label className={labelClass}>Timestamp</label>
                <input
                  value={typeof state.Timestamp === 'string' ? state.Timestamp : ''}
                  onChange={(event) => setField('Timestamp', event.target.value)}
                  placeholder="2026-07-10T12:00:00Z"
                  spellCheck={false}
                  className={monoFieldClass}
                />
                <p className={hintClass}>RFC 3339 timestamp or a {'{% ... %}'} expression producing one.</p>
              </div>
            )}
          </div>
        </div>
      )}

      {type === 'Fail' && (
        <div className={sectionClass}>
          <div className="space-y-4">
            <div>
              <label className={labelClass}>Error</label>
              <input
                value={typeof state.Error === 'string' ? state.Error : ''}
                onChange={(event) => setField('Error', event.target.value || undefined)}
                placeholder="e.g. OrderRejected"
                spellCheck={false}
                className={monoFieldClass}
              />
              <p className={hintClass}>Custom error name. Must not start with "States.".</p>
            </div>
            <div>
              <label className={labelClass}>Cause</label>
              <input
                value={typeof state.Cause === 'string' ? state.Cause : ''}
                onChange={(event) => setField('Cause', event.target.value || undefined)}
                placeholder="Human-readable failure description"
                className={fieldClass}
              />
            </div>
          </div>
        </div>
      )}

      {type === 'Parallel' && (
        <div className={sectionClass}>
          <div className="space-y-4">
            <JsonField
              label="Branches"
              value={state.Branches}
              onCommit={(value) => setField('Branches', value)}
              requireArray
              rows={10}
              hint="Array of nested state machines, each with StartAt and States."
              monoFieldClass={monoFieldClass}
            />
            <JsonField
              label="Arguments"
              value={state.Arguments}
              onCommit={(value) => setField('Arguments', value)}
              hint="JSON passed to every branch. Defaults to the state input."
              monoFieldClass={monoFieldClass}
            />
          </div>
        </div>
      )}

      {type === 'Map' && (
        <div className={sectionClass}>
          <div className="space-y-4">
            <JsonField
              label="Items"
              value={state.Items}
              onCommit={(value) => setField('Items', value)}
              placeholder="{% $states.input.orders %}"
              rows={2}
              hint="Array or expression producing one. Without it, the state input must be an array."
              monoFieldClass={monoFieldClass}
            />
            <JsonField
              label="Item selector"
              value={state.ItemSelector}
              onCommit={(value) => setField('ItemSelector', value)}
              hint="Optional transform applied to each item before processing."
              monoFieldClass={monoFieldClass}
            />
            <JsonField
              label="Item processor"
              value={state.ItemProcessor}
              onCommit={(value) => setField('ItemProcessor', value)}
              requireObject
              rows={10}
              hint="Nested state machine with StartAt and States, run once per item."
              monoFieldClass={monoFieldClass}
            />
            <IntegerField
              label="Max concurrency"
              value={state.MaxConcurrency}
              onCommit={(value) => setField('MaxConcurrency', value)}
              placeholder="0 = unlimited, 1 = sequential"
              min={0}
              monoFieldClass={monoFieldClass}
            />
          </div>
        </div>
      )}

      {supportsNextOrEnd(type) && (
        <div className={sectionClass}>
          <TargetSelect
            label="Then"
            value={transitionValue}
            targets={otherStates}
            onChange={handleTransitionChange}
            allowEnd
            fieldClass={fieldClass}
          />
        </div>
      )}

      {(supportsAssign(type) || supportsOutput(type)) && (
        <div className={sectionClass}>
          <div className="space-y-4">
            {supportsAssign(type) && (
              <JsonField
                label="Assign variables"
                value={state.Assign}
                onCommit={(value) => setField('Assign', value)}
                requireObject
                placeholder={'{\n  "orderTotal": "{% $states.result.total %}"\n}'}
                hint="Workflow variables visible to later states."
                monoFieldClass={monoFieldClass}
              />
            )}
            {supportsOutput(type) && (
              <JsonField
                label="Output"
                value={state.Output}
                onCommit={(value) => setField('Output', value)}
                placeholder={'{\n  "status": "{% $states.result.status %}"\n}'}
                hint="Shapes this state's output. Defaults to the result (Task, Map, Parallel) or the input."
                monoFieldClass={monoFieldClass}
              />
            )}
          </div>
        </div>
      )}

      {supportsRetryCatch(type) && (
        <div className={sectionClass}>
          <label className={labelClass}>Retry</label>
          <div className="mt-3 space-y-3">
            {retriers.map((retrier, index) => (
              <div key={index} className="rounded-DEFAULT border border-border-subtle p-3">
                <div className="flex items-start gap-2">
                  <div className="flex-1 space-y-3">
                    <div>
                      <label className={labelClass}>Errors (comma separated)</label>
                      <input
                        value={Array.isArray(retrier?.ErrorEquals) ? retrier.ErrorEquals.join(', ') : ''}
                        onChange={(event) => updateRetrier(index, {
                          ErrorEquals: event.target.value.split(',').map((item) => item.trim()).filter(Boolean),
                        })}
                        placeholder="States.Timeout, States.TaskFailed"
                        spellCheck={false}
                        className={monoFieldClass}
                      />
                    </div>
                    <div className="grid grid-cols-2 gap-3">
                      <IntegerField
                        label="Interval seconds"
                        value={retrier?.IntervalSeconds}
                        onCommit={(value) => updateRetrier(index, { IntervalSeconds: value })}
                        placeholder="1"
                        min={1}
                        monoFieldClass={monoFieldClass}
                      />
                      <IntegerField
                        label="Max attempts"
                        value={retrier?.MaxAttempts}
                        onCommit={(value) => updateRetrier(index, { MaxAttempts: value })}
                        placeholder="3"
                        min={0}
                        monoFieldClass={monoFieldClass}
                      />
                    </div>
                  </div>
                  {addRemoveButton(
                    () => updateArrayField('Retry', retriers.filter((_, retrierIndex) => retrierIndex !== index)),
                    `Remove retrier ${index + 1}`,
                  )}
                </div>
              </div>
            ))}
          </div>
          {addItemButton('Add retrier', () => updateArrayField('Retry', [
            ...retriers,
            { ErrorEquals: ['States.ALL'], IntervalSeconds: 2, MaxAttempts: 3, BackoffRate: 2 },
          ]))}

          <div className="mt-6">
            <label className={labelClass}>Catch</label>
            <div className="mt-3 space-y-3">
              {catchers.map((catcher, index) => (
                <div key={index} className="rounded-DEFAULT border border-border-subtle p-3">
                  <div className="flex items-start gap-2">
                    <div className="flex-1 space-y-3">
                      <div>
                        <label className={labelClass}>Errors (comma separated)</label>
                        <input
                          value={Array.isArray(catcher?.ErrorEquals) ? catcher.ErrorEquals.join(', ') : ''}
                          onChange={(event) => updateCatcher(index, {
                            ErrorEquals: event.target.value.split(',').map((item) => item.trim()).filter(Boolean),
                          })}
                          placeholder="States.ALL"
                          spellCheck={false}
                          className={monoFieldClass}
                        />
                      </div>
                      <TargetSelect
                        label="Recover via"
                        value={typeof catcher?.Next === 'string' ? catcher.Next : ''}
                        targets={otherStates}
                        onChange={(value) => updateCatcher(index, { Next: value })}
                        fieldClass={fieldClass}
                      />
                    </div>
                    {addRemoveButton(
                      () => updateArrayField('Catch', catchers.filter((_, catcherIndex) => catcherIndex !== index)),
                      `Remove catcher ${index + 1}`,
                    )}
                  </div>
                </div>
              ))}
            </div>
            {addItemButton('Add catcher', () => updateArrayField('Catch', [
              ...catchers,
              { ErrorEquals: ['States.ALL'], Next: '' },
            ]))}
          </div>
        </div>
      )}
    </div>
  );
}
