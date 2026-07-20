import { useState } from 'react';
import type { ReactNode } from 'react';
import { Plus, Search, Trash2 } from 'lucide-react';
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
import {
  createNestedMachine,
  isMachine,
  type MachinePathSegment,
} from './nestedMachine';

const labelClass = 'block font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant';
const hintClass = 'mt-1 font-body-sm text-[11px] text-on-surface-variant/70';
const errorClass = 'mt-1 font-body-sm text-[11px] text-status-error';
const sectionClass = 'border-b border-border-subtle px-6 py-5';

function timestampOneHourFromNow() {
  return new Date(Date.now() + 3600_000).toISOString().replace(/\.\d{3}Z$/, 'Z');
}

function CollapsibleSection({
  icon,
  title,
  description,
  badge,
  testId,
  defaultOpen = false,
  children,
}: {
  icon?: string;
  title: string;
  description?: string;
  badge?: string;
  testId?: string;
  defaultOpen?: boolean;
  children: ReactNode;
}) {
  return (
    <details data-testid={testId} open={defaultOpen} className={`group ${sectionClass}`}>
      <summary className="flex cursor-pointer list-none items-center gap-2 [&::-webkit-details-marker]:hidden">
        {icon ? <span className="material-symbols-outlined text-[15px] text-on-surface-variant">{icon}</span> : null}
        <h3 className="font-mono-sm text-[11px] font-medium uppercase tracking-[0.08em] text-on-surface">{title}</h3>
        {badge ? (
          <span className="rounded-full border border-border-subtle px-1.5 py-px font-mono-sm text-[9px] text-on-surface-variant">{badge}</span>
        ) : null}
        <span className="material-symbols-outlined ml-auto text-[18px] text-on-surface-variant transition-transform duration-200 group-open:rotate-180">
          expand_more
        </span>
      </summary>
      {description ? <p className="mt-2 font-body-sm text-[11px] leading-relaxed text-on-surface-variant/70">{description}</p> : null}
      <div className="mt-4">{children}</div>
    </details>
  );
}

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

function parseNumberOrExpression(text: string, options: { min?: number } = {}): ParseResult {
  const trimmed = text.trim();
  if (!trimmed) {
    return { ok: true, value: undefined };
  }
  if (trimmed.startsWith('{%') && trimmed.endsWith('%}')) {
    return { ok: true, value: trimmed };
  }
  if (!/^\d+(\.\d+)?$/.test(trimmed)) {
    return { ok: false, error: 'Enter a number or a {% ... %} expression.' };
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

function cleanPatchedObject<T extends Record<string, unknown>>(next: T, patch: Record<string, unknown>): T {
  for (const [key, value] of Object.entries(patch)) {
    if (value === undefined) {
      delete next[key];
    }
  }
  return next;
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
        aria-label={label}
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

type NumberFieldProps = {
  label: string;
  value: unknown;
  onCommit: (value: unknown | undefined) => void;
  hint?: string;
  placeholder?: string;
  min?: number;
  monoFieldClass: string;
};

function NumberField({ label, value, onCommit, hint, placeholder, min, monoFieldClass }: NumberFieldProps) {
  const [text, setText] = useState(() => stringifyValue(value));
  const [error, setError] = useState<string | null>(null);

  const handleChange = (nextText: string) => {
    setText(nextText);
    const result = parseNumberOrExpression(nextText, { min });
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

export type TaskResourceOption = {
  value: string;
  label: string;
  description: string;
  argumentsTemplate?: Record<string, unknown>;
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
        {allowEnd && <option value={END_OPTION}>End: true</option>}
        {missing && <option value={value}>{value} (missing)</option>}
        {targets.map((target) => (
          <option key={target} value={target}>{target}</option>
        ))}
      </select>
      {missing && <p className={errorClass}>Target state "{value}" does not exist.</p>}
    </div>
  );
}

function resourceDisplayValue(resource: string) {
  return resource.startsWith('voyager://')
    ? resource.slice('voyager://'.length).replace(/^\/+/, '') || resource
    : resource;
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
  taskResourceOptions?: TaskResourceOption[];
  onOpenNestedScope?: (segment: MachinePathSegment) => void;
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
  taskResourceOptions = [],
  onOpenNestedScope,
}: Props) {
  const type = stateTypeOf(state);
  const visual = getStateVisual(type);
  const otherStates = stateNames.filter((stateName) => stateName !== name);
  const [nameDraft, setNameDraft] = useState(name);
  const [resourceSearch, setResourceSearch] = useState('');

  const setField = (field: string, value: unknown | undefined) => {
    const next = { ...state };
    if (value === undefined) {
      delete next[field];
    } else {
      next[field] = value;
    }
    onChange(next);
  };

  const setFields = (patch: Record<string, unknown | undefined>) => {
    const next = { ...state };
    for (const [field, value] of Object.entries(patch)) {
      if (value === undefined) {
        delete next[field];
      } else {
        next[field] = value;
      }
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
  const parallelBranches: unknown[] = Array.isArray(state.Branches) ? state.Branches : [];

  const updateChoice = (index: number, patch: Record<string, unknown>) => {
    const nextChoices = choices.map((choice, choiceIndex) => (
      choiceIndex === index ? cleanPatchedObject({ ...choice, ...patch }, patch) : choice
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
      return cleanPatchedObject({ ...retrier, ...patch }, patch);
    }));
  };

  const updateCatcher = (index: number, patch: Record<string, unknown>) => {
    updateArrayField('Catch', catchers.map((catcher, catcherIndex) => {
      if (catcherIndex !== index) return catcher;
      return cleanPatchedObject({ ...catcher, ...patch }, patch);
    }));
  };

  const waitMode = state.Timestamp !== undefined ? 'Timestamp' : 'Seconds';
  const argumentsObject: Record<string, unknown> = (
    state.Arguments && typeof state.Arguments === 'object' && !Array.isArray(state.Arguments)
      ? state.Arguments as Record<string, unknown>
      : {}
  );
  const systemTaskResourceOptions: TaskResourceOption[] = [
    {
      value: 'voyager://system/webhook',
      label: 'webhook',
      description: 'System',
      argumentsTemplate: {
        url: '{% $states.input.url %}',
        method: 'POST',
        headers: {},
        body: '{% $states.input.body %}',
      },
    },
    {
      value: 'voyager://system/send-email',
      label: 'send-email',
      description: 'System',
      argumentsTemplate: {
        to: '{% $states.input.to %}',
        subject: '{% $states.input.subject %}',
        body: '{% $states.input.body %}',
      },
    },
  ];
  const mergedTaskResourceOptions = [
    ...systemTaskResourceOptions,
    ...taskResourceOptions.filter((option) => (
      !systemTaskResourceOptions.some((systemOption) => systemOption.value === option.value)
    )),
  ];
  const resourceSearchQuery = resourceSearch.trim().toLowerCase();
  const visibleTaskResourceOptions = resourceSearchQuery
    ? mergedTaskResourceOptions.filter((option) => (
      `${option.label} ${option.value} ${option.description}`.toLowerCase().includes(resourceSearchQuery)
    ))
    : mergedTaskResourceOptions;
  const systemResourceValues = new Set(systemTaskResourceOptions.map((option) => option.value));
  const visibleSystemResources = visibleTaskResourceOptions.filter((option) => systemResourceValues.has(option.value));
  const visibleCustomResources = visibleTaskResourceOptions.filter((option) => !systemResourceValues.has(option.value));
  const visibleMcpResources = visibleCustomResources.filter((option) => option.value.startsWith('voyager://mcp/'));
  const visibleFunctionResources = visibleCustomResources.filter((option) => !option.value.startsWith('voyager://mcp/'));
  const selectedTaskResourceOption = mergedTaskResourceOptions.find((option) => option.value === state.Resource);
  const selectedSystemResource = state.Resource === 'voyager://system/webhook' || state.Resource === 'voyager://system/send-email'
    ? state.Resource
    : '';

  const applyTaskResource = (option: TaskResourceOption) => {
    const resourceChanged = state.Resource !== option.value;
    setFields({
      Resource: option.value,
      Arguments: resourceChanged ? undefined : state.Arguments,
    });
  };

  const applyArgumentsTemplate = (option: TaskResourceOption) => {
    if (!option.argumentsTemplate) return;
    setField('Arguments', option.argumentsTemplate);
  };

  const renderResourceOption = (option: TaskResourceOption) => {
    const isSystem = systemResourceValues.has(option.value);
    const isActive = state.Resource === option.value;
    return (
      <button
        key={option.value}
        type="button"
        onClick={() => applyTaskResource(option)}
        className={`flex w-full items-center gap-2 rounded-[3px] px-2 py-1.5 text-left transition-colors hover:bg-surface-container ${isActive ? 'bg-secondary-container/35' : ''}`}
        title={option.value}
      >
        <span className={`material-symbols-outlined shrink-0 text-[15px] ${isSystem ? 'text-secondary' : 'text-status-info'}`}>
          {isSystem ? 'bolt' : 'function'}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate font-mono-sm text-[11px] text-on-surface">{option.label}</span>
          <span className="block truncate font-mono-sm text-[9px] text-on-surface-variant">{resourceDisplayValue(option.value)}</span>
        </span>
        {!isSystem && option.description ? (
          <span className="shrink-0 rounded border border-border-subtle px-1.5 py-0.5 font-mono-sm text-[9px] text-on-surface-variant">
            {option.description.replace(/^Function\s+/i, '')}
          </span>
        ) : null}
      </button>
    );
  };

  const resourceGroupLabelClass = 'flex items-center gap-1 px-2 pb-1 pt-1.5 font-mono-sm text-[9px] uppercase tracking-[0.08em] text-on-surface-variant/70';

  const argumentText = (key: string) => {
    const value = argumentsObject[key];
    if (value === undefined) return '';
    if (typeof value === 'string') return value;
    return JSON.stringify(value, null, 2);
  };

  const updateArgument = (key: string, value: string) => {
    const nextArguments = { ...argumentsObject };
    if (value) {
      nextArguments[key] = value;
    } else {
      delete nextArguments[key];
    }
    setField('Arguments', Object.keys(nextArguments).length > 0 ? nextArguments : undefined);
  };

  const updateArgumentValue = (key: string, value: unknown | undefined) => {
    const nextArguments = { ...argumentsObject };
    if (value === undefined) {
      delete nextArguments[key];
    } else {
      nextArguments[key] = value;
    }
    setField('Arguments', Object.keys(nextArguments).length > 0 ? nextArguments : undefined);
  };

  const handleWaitModeChange = (mode: 'Seconds' | 'Timestamp') => {
    if (mode === waitMode) return;
    const next = { ...state };
    if (mode === 'Seconds') {
      delete next.Timestamp;
      next.Seconds = 60;
    } else {
      delete next.Seconds;
      next.Timestamp = timestampOneHourFromNow();
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
    <div
      className="flex min-h-0 flex-1 flex-col overflow-y-auto"
      data-testid={`workflow-state-editor-${type.toLowerCase()}`}
    >
      <div className={`h-1 shrink-0 ${visual.barClass}`} />
      <div className={`${sectionClass} ${visual.softBgClass}`}>
        <div className="flex items-center justify-between gap-3">
          <span className={`flex items-center gap-1.5 rounded border px-2 py-1 font-mono-sm text-[10px] ${visual.chipClass}`}>
            <span className="material-symbols-outlined text-[13px]">{visual.iconName}</span>
            {visual.label}
          </span>
          <div className="flex items-center gap-2">
            {isStart ? (
              <span className="rounded border border-secondary/35 bg-secondary-container/40 px-2 py-1 font-mono-sm text-[10px] text-secondary-fixed">
                StartAt
              </span>
            ) : (
              <button
                type="button"
                onClick={onSetStart}
                className="rounded border border-border-subtle px-2 py-1 font-mono-sm text-[10px] text-on-surface-variant transition-colors hover:border-secondary hover:text-secondary"
              >
                Set StartAt
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
        <CollapsibleSection
          icon="database"
          title="Task"
          description="The resource this state invokes and the input it receives."
        >
          <div className="space-y-4">
            <div>
              <label className={labelClass}>Resource</label>
              <input
                value={typeof state.Resource === 'string' ? state.Resource : ''}
                onChange={(event) => {
                  const value = event.target.value;
                  const option = mergedTaskResourceOptions.find((resourceOption) => resourceOption.value === value);
                  if (option) {
                    applyTaskResource(option);
                  } else {
                    setField('Resource', value);
                  }
                }}
                placeholder="voyager://system/webhook"
                spellCheck={false}
                list="voyager-task-resource-schemes"
                className={monoFieldClass}
              />
              <datalist id="voyager-task-resource-schemes">
                <option value="voyager://function/" />
                {mergedTaskResourceOptions.map((option) => (
                  <option key={option.value} value={option.value} label={option.label} />
                ))}
                <option value="voyager://mcp/" />
              </datalist>
              <label className="mt-2 flex h-8 items-center gap-2 rounded-DEFAULT border border-border-subtle bg-surface-container-lowest px-2 text-on-surface-variant focus-within:border-secondary/60">
                <Search size={12} className="shrink-0" />
                <input
                  value={resourceSearch}
                  onChange={(event) => setResourceSearch(event.target.value)}
                  placeholder="Search resources"
                  className="min-w-0 flex-1 border-0 bg-transparent p-0 font-mono-sm text-[11px] text-on-surface outline-none placeholder:text-on-surface-variant/50 focus:border-0 focus:outline-none focus:ring-0"
                />
              </label>
              <div className="mt-2 max-h-[186px] overflow-y-auto rounded-DEFAULT border border-border-subtle bg-surface-container-lowest/55 p-1">
                {visibleSystemResources.length > 0 && (
                  <div>
                    <div className={resourceGroupLabelClass}>
                      <span className="material-symbols-outlined text-[12px] text-secondary">bolt</span>
                      System
                    </div>
                    {visibleSystemResources.map(renderResourceOption)}
                  </div>
                )}
                {visibleFunctionResources.length > 0 && (
                  <div className={visibleSystemResources.length > 0 ? 'mt-1 border-t border-border-subtle/60 pt-1' : ''}>
                    <div className={resourceGroupLabelClass}>
                      <span className="material-symbols-outlined text-[12px] text-status-info">function</span>
                      Functions
                    </div>
                    {visibleFunctionResources.map(renderResourceOption)}
                  </div>
                )}
                {visibleMcpResources.length > 0 && (
                  <div className={(visibleSystemResources.length > 0 || visibleFunctionResources.length > 0) ? 'mt-1 border-t border-border-subtle/60 pt-1' : ''}>
                    <div className={resourceGroupLabelClass}>
                      <span className="material-symbols-outlined text-[12px] text-primary">cable</span>
                      MCP tools
                    </div>
                    {visibleMcpResources.map(renderResourceOption)}
                  </div>
                )}
                {visibleTaskResourceOptions.length === 0 && (
                  <div className="px-2 py-3 text-center font-mono-sm text-[11px] text-on-surface-variant">
                    No resources match.
                  </div>
                )}
              </div>
              <p className={hintClass}>URI of the resource this task invokes.</p>
            </div>
            {selectedSystemResource ? (
              <div className="rounded-DEFAULT border border-border-subtle bg-surface-container-lowest/45 p-3">
                <div className={labelClass}>Arguments</div>
                <div className="mt-3 space-y-3">
                  {selectedSystemResource === 'voyager://system/webhook' ? (
                    <>
                      <div>
                        <label className={labelClass}>url</label>
                        <input
                          value={argumentText('url')}
                          onChange={(event) => updateArgument('url', event.target.value)}
                          placeholder="{% $states.input.url %}"
                          spellCheck={false}
                          className={monoFieldClass}
                        />
                      </div>
                      <div>
                        <label className={labelClass}>method</label>
                        <input
                          value={argumentText('method')}
                          onChange={(event) => updateArgument('method', event.target.value)}
                          placeholder="POST"
                          list="voyager-webhook-methods"
                          spellCheck={false}
                          className={monoFieldClass}
                        />
                        <datalist id="voyager-webhook-methods">
                          {['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'].map((method) => (
                            <option key={method} value={method} />
                          ))}
                        </datalist>
                        <p className={hintClass}>Defaults to POST when omitted.</p>
                      </div>
                      <JsonField
                        label="headers"
                        value={argumentsObject.headers}
                        onCommit={(value) => updateArgumentValue('headers', value)}
                        requireObject
                        placeholder={'{\n  "Authorization": "{% $states.input.authorization %}",\n  "X-Correlation-ID": "{% $states.input.orderId %}"\n}'}
                        rows={4}
                        hint="Request headers as a JSON object. Static values are stored in the workflow definition."
                        monoFieldClass={monoFieldClass}
                      />
                      <JsonField
                        label="body"
                        value={argumentsObject.body}
                        onCommit={(value) => updateArgumentValue('body', value)}
                        placeholder="{% $states.input.body %}"
                        rows={4}
                        hint="Any JSON value or a JSONata expression. GET, HEAD, and OPTIONS send no body when omitted."
                        monoFieldClass={monoFieldClass}
                      />
                    </>
                  ) : (
                    <>
                      <div>
                        <label className={labelClass}>to</label>
                        <input
                          value={argumentText('to')}
                          onChange={(event) => updateArgument('to', event.target.value)}
                          placeholder="{% $states.input.to %}"
                          spellCheck={false}
                          className={monoFieldClass}
                        />
                      </div>
                      <div>
                        <label className={labelClass}>subject</label>
                        <input
                          value={argumentText('subject')}
                          onChange={(event) => updateArgument('subject', event.target.value)}
                          placeholder="{% $states.input.subject %}"
                          spellCheck={false}
                          className={monoFieldClass}
                        />
                      </div>
                      <div>
                        <label className={labelClass}>body</label>
                        <textarea
                          value={argumentText('body')}
                          onChange={(event) => updateArgument('body', event.target.value)}
                          placeholder="{% $states.input.body %}"
                          rows={3}
                          spellCheck={false}
                          className={`${monoFieldClass} h-auto min-h-[72px] resize-y py-2 leading-relaxed`}
                        />
                      </div>
                    </>
                  )}
                </div>
                {selectedTaskResourceOption?.argumentsTemplate && (
                  <button
                    type="button"
                    onClick={() => applyArgumentsTemplate(selectedTaskResourceOption)}
                    className="mt-3 h-7 rounded-DEFAULT border border-border-subtle px-2 font-mono-sm text-[10px] text-on-surface-variant transition-colors hover:border-secondary/45 hover:text-secondary"
                  >
                    Fill from input
                  </button>
                )}
              </div>
            ) : (
              <JsonField
                label="Arguments"
                value={state.Arguments}
                onCommit={(value) => setField('Arguments', value)}
                placeholder={'{\n  "orderId": "{% $states.input.orderId %}"\n}'}
                hint="JSON sent to the resource. Defaults to the state input."
                monoFieldClass={monoFieldClass}
              />
            )}
            <div className="grid grid-cols-2 gap-3">
              <IntegerField
                label="TimeoutSeconds"
                value={state.TimeoutSeconds}
                onCommit={(value) => setField('TimeoutSeconds', value)}
                placeholder="e.g. 30"
                min={1}
                monoFieldClass={monoFieldClass}
              />
              <IntegerField
                label="HeartbeatSeconds"
                value={state.HeartbeatSeconds}
                onCommit={(value) => setField('HeartbeatSeconds', value)}
                placeholder="Optional"
                min={1}
                monoFieldClass={monoFieldClass}
              />
            </div>
          </div>
        </CollapsibleSection>
      )}

      {type === 'Choice' && (
        <CollapsibleSection
          icon="call_split"
          title="Choice rules"
          description="Evaluated top to bottom — the first rule whose condition is true wins."
        >
          <div className="mt-3 space-y-3">
            {choices.map((choice, index) => (
              <div key={index} className="rounded-DEFAULT border border-border-subtle p-3">
                <div className="flex items-start gap-2">
                  <div className="flex-1 space-y-2">
                    <div>
                      <label className={labelClass}>Condition</label>
                      <input
                        value={typeof choice?.Condition === 'string' ? choice.Condition : ''}
                        onChange={(event) => updateChoice(index, { Condition: event.target.value })}
                        placeholder="{% $states.input.total > 1000 %}"
                        spellCheck={false}
                        className={monoFieldClass}
                      />
                    </div>
                    <TargetSelect
                      label="Next"
                      value={typeof choice?.Next === 'string' ? choice.Next : ''}
                      targets={otherStates}
                      onChange={(value) => updateChoice(index, { Next: value })}
                      fieldClass={fieldClass}
                    />
                    <JsonField
                      label="Assign"
                      value={choice?.Assign}
                      onCommit={(value) => updateChoice(index, { Assign: value })}
                      requireObject
                      rows={3}
                      placeholder={'{\n  "route": "{% $states.input.route %}"\n}'}
                      hint="Optional variables assigned only when this rule is selected."
                      monoFieldClass={monoFieldClass}
                    />
                    <JsonField
                      label="Output"
                      value={choice?.Output}
                      onCommit={(value) => updateChoice(index, { Output: value })}
                      rows={3}
                      placeholder={'{\n  "selected": true\n}'}
                      hint="Optional output used only when this rule is selected."
                      monoFieldClass={monoFieldClass}
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
        </CollapsibleSection>
      )}

      {type === 'Wait' && (
        <CollapsibleSection
          icon="schedule"
          title="Wait"
          description="Pause here for a number of seconds or until a specific timestamp."
        >
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
                {mode}
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
        </CollapsibleSection>
      )}

      {type === 'Fail' && (
        <CollapsibleSection
          icon="cancel"
          title="Failure"
          description="Stops the workflow immediately and reports this error."
        >
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
        </CollapsibleSection>
      )}

      {type === 'Parallel' && (
        <CollapsibleSection
          icon="account_tree"
          title="Parallel branches"
          description="Run every branch at the same time; each receives the same input."
        >
          <div className="space-y-4">
            <div className="space-y-2">
              {parallelBranches.map((branch, branchIndex) => {
                const validBranch = isMachine(branch);
                const branchStateCount = validBranch ? Object.keys(branch.States || {}).length : 0;
                return (
                  <div
                    key={branchIndex}
                    className="flex items-center gap-3 rounded-DEFAULT border border-border-subtle bg-surface-container-lowest px-3 py-3"
                  >
                    <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-DEFAULT border border-secondary/30 bg-secondary-container/25 font-mono-sm text-[11px] text-secondary">
                      {branchIndex + 1}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block font-mono-sm text-[11px] text-on-surface">Branch {branchIndex + 1}</span>
                      <span className="block truncate text-[10px] text-on-surface-variant">
                        {validBranch ? `${branchStateCount} states · starts at ${branch.StartAt || 'unset'}` : 'Invalid nested machine'}
                      </span>
                    </span>
                    <button
                      type="button"
                      onClick={() => onOpenNestedScope?.({ kind: 'parallel', stateName: name, branchIndex })}
                      disabled={!validBranch || !onOpenNestedScope}
                      className="flex h-8 items-center gap-1.5 rounded-DEFAULT border border-secondary/35 px-2.5 font-mono-sm text-[10px] text-secondary transition-colors hover:bg-secondary-container/25 disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      <span className="material-symbols-outlined text-[14px]">open_in_new</span>
                      Open canvas
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        if (parallelBranches.length <= 1) return;
                        if (!window.confirm(`Remove Branch ${branchIndex + 1} and all of its states?`)) return;
                        setField('Branches', parallelBranches.filter((_, index) => index !== branchIndex));
                      }}
                      disabled={parallelBranches.length <= 1}
                      className="flex h-8 w-8 items-center justify-center rounded-DEFAULT text-on-surface-variant transition-colors hover:bg-status-error/10 hover:text-status-error disabled:cursor-not-allowed disabled:opacity-35"
                      aria-label={`Remove Branch ${branchIndex + 1}`}
                    >
                      <Trash2 size={13} />
                    </button>
                  </div>
                );
              })}
              <button
                type="button"
                onClick={() => {
                  const branchIndex = parallelBranches.length;
                  const branch = createNestedMachine(`Branch${branchIndex + 1}Start`);
                  setField('Branches', [...parallelBranches, branch]);
                  onOpenNestedScope?.({ kind: 'parallel', stateName: name, branchIndex });
                }}
                className="flex h-9 w-full items-center justify-center gap-2 rounded-DEFAULT border border-dashed border-secondary/35 font-mono-sm text-[10px] text-secondary transition-colors hover:bg-secondary-container/20"
              >
                <Plus size={13} /> Add branch
              </button>
            </div>
            <details className="group rounded-DEFAULT border border-border-subtle bg-surface-container-lowest px-3 py-2.5">
              <summary className="cursor-pointer list-none font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant [&::-webkit-details-marker]:hidden">
                Advanced branch JSON
              </summary>
              <div className="mt-3">
                <JsonField
                  label="Branches"
                  value={state.Branches}
                  onCommit={(value) => setField('Branches', value)}
                  requireArray
                  rows={10}
                  hint="Array of closed nested machines. Transitions cannot leave a branch."
                  monoFieldClass={monoFieldClass}
                />
              </div>
            </details>
            <JsonField
              label="Arguments"
              value={state.Arguments}
              onCommit={(value) => setField('Arguments', value)}
              hint="JSON passed to every branch. Defaults to the state input."
              monoFieldClass={monoFieldClass}
            />
          </div>
        </CollapsibleSection>
      )}

      {type === 'Map' && (
        <CollapsibleSection
          icon="repeat"
          title="Map"
          description="Run the processor once for each item in a collection."
        >
          <div className="space-y-4">
            <div className="rounded-DEFAULT border border-border-subtle bg-surface-container-lowest p-3">
              <div className="flex items-center gap-3">
                <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-DEFAULT border border-secondary/30 bg-secondary-container/25 text-secondary">
                  <span className="material-symbols-outlined text-[17px]">account_tree</span>
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block font-mono-sm text-[11px] text-on-surface">Item processor</span>
                  <span className="block truncate text-[10px] text-on-surface-variant">
                    {isMachine(state.ItemProcessor)
                      ? `${Object.keys(state.ItemProcessor.States || {}).length} states · starts at ${state.ItemProcessor.StartAt || 'unset'}`
                      : 'Create an INLINE processor machine'}
                  </span>
                </span>
                <button
                  type="button"
                  onClick={() => {
                    if (!isMachine(state.ItemProcessor)) {
                      onChange({ ...state, ItemProcessor: createNestedMachine('ProcessItem') });
                    }
                    onOpenNestedScope?.({ kind: 'map', stateName: name });
                  }}
                  disabled={!onOpenNestedScope}
                  className="flex h-8 items-center gap-1.5 rounded-DEFAULT border border-secondary/35 px-2.5 font-mono-sm text-[10px] text-secondary transition-colors hover:bg-secondary-container/25 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  <span className="material-symbols-outlined text-[14px]">open_in_new</span>
                  {isMachine(state.ItemProcessor) ? 'Open canvas' : 'Create canvas'}
                </button>
              </div>
              <p className="mt-2 text-[10px] leading-5 text-on-surface-variant/75">
                Voyager supports INLINE Map only. Distributed Map is rejected during validation and activation.
                Each iteration has its own closed transition and variable scope.
              </p>
            </div>
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
              label="ItemSelector"
              value={state.ItemSelector}
              onCommit={(value) => setField('ItemSelector', value)}
              hint="Optional transform applied to each item before processing."
              monoFieldClass={monoFieldClass}
            />
            <details className="group rounded-DEFAULT border border-border-subtle bg-surface-container-lowest px-3 py-2.5">
              <summary className="cursor-pointer list-none font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant [&::-webkit-details-marker]:hidden">
                Advanced processor JSON
              </summary>
              <div className="mt-3">
                <JsonField
                  label="ItemProcessor"
                  value={state.ItemProcessor}
                  onCommit={(value) => setField('ItemProcessor', value)}
                  requireObject
                  rows={10}
                  hint="Nested INLINE machine with its own StartAt and States."
                  monoFieldClass={monoFieldClass}
                />
              </div>
            </details>
            <IntegerField
              label="MaxConcurrency"
              value={state.MaxConcurrency}
              onCommit={(value) => setField('MaxConcurrency', value)}
              placeholder="0 = unlimited, 1 = sequential"
              min={0}
              monoFieldClass={monoFieldClass}
            />
          </div>
        </CollapsibleSection>
      )}

      {supportsNextOrEnd(type) && (
        <CollapsibleSection
          icon="arrow_forward"
          title="Transition"
          description="Where execution continues once this state finishes, or End to stop the workflow."
        >
          <TargetSelect
            value={transitionValue}
            targets={otherStates}
            onChange={handleTransitionChange}
            allowEnd
            fieldClass={fieldClass}
          />
        </CollapsibleSection>
      )}

      {(supportsAssign(type) || supportsOutput(type)) && (
        <CollapsibleSection
          icon="dataset"
          title="Data flow"
          description="Reshape this state's output and expose variables to later states. Optional."
        >
          <div className="space-y-4">
            {supportsAssign(type) && (
              <JsonField
                label="Assign"
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
        </CollapsibleSection>
      )}

      {supportsRetryCatch(type) && (
        <CollapsibleSection
          icon="restart_alt"
          title="Error handling"
          testId="workflow-error-handling"
          description="Retry transient failures, then catch remaining errors and route to a fallback state."
          badge={retriers.length > 0 || catchers.length > 0 ? `${retriers.length + catchers.length}` : undefined}
        >
          <label className={labelClass}>Retry</label>
          <div className="mt-3 space-y-3">
            {retriers.map((retrier, index) => (
              <div key={index} className="rounded-DEFAULT border border-border-subtle p-3">
                <div className="flex items-start gap-2">
                  <div className="flex-1 space-y-3">
                    <div>
                      <label className={labelClass}>ErrorEquals</label>
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
                        label="IntervalSeconds"
                        value={retrier?.IntervalSeconds}
                        onCommit={(value) => updateRetrier(index, { IntervalSeconds: value })}
                        placeholder="1"
                        min={1}
                        monoFieldClass={monoFieldClass}
                      />
                      <IntegerField
                        label="MaxAttempts"
                        value={retrier?.MaxAttempts}
                        onCommit={(value) => updateRetrier(index, { MaxAttempts: value })}
                        placeholder="3"
                        min={0}
                        monoFieldClass={monoFieldClass}
                      />
                      <NumberField
                        label="BackoffRate"
                        value={retrier?.BackoffRate}
                        onCommit={(value) => updateRetrier(index, { BackoffRate: value })}
                        placeholder="2"
                        min={1}
                        monoFieldClass={monoFieldClass}
                      />
                      <IntegerField
                        label="MaxDelaySeconds"
                        value={retrier?.MaxDelaySeconds}
                        onCommit={(value) => updateRetrier(index, { MaxDelaySeconds: value })}
                        placeholder="Optional"
                        min={1}
                        monoFieldClass={monoFieldClass}
                      />
                    </div>
                    <div>
                      <label className={labelClass}>JitterStrategy</label>
                      <select
                        value={typeof retrier?.JitterStrategy === 'string' ? retrier.JitterStrategy : NONE_OPTION}
                        onChange={(event) => updateRetrier(index, {
                          JitterStrategy: event.target.value === NONE_OPTION ? undefined : event.target.value,
                        })}
                        className={`${fieldClass} appearance-none`}
                      >
                        <option value={NONE_OPTION}>None</option>
                        <option value="FULL">FULL</option>
                        <option value="NONE">NONE</option>
                      </select>
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
                        <label className={labelClass}>ErrorEquals</label>
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
                        label="Next"
                        value={typeof catcher?.Next === 'string' ? catcher.Next : ''}
                        targets={otherStates}
                        onChange={(value) => updateCatcher(index, { Next: value })}
                        fieldClass={fieldClass}
                      />
                      <JsonField
                        label="Assign"
                        value={catcher?.Assign}
                        onCommit={(value) => updateCatcher(index, { Assign: value })}
                        requireObject
                        rows={3}
                        placeholder={'{\n  "errorName": "{% $states.errorOutput.Error %}"\n}'}
                        hint="Optional variables assigned only when this catcher handles the error."
                        monoFieldClass={monoFieldClass}
                      />
                      <JsonField
                        label="Output"
                        value={catcher?.Output}
                        onCommit={(value) => updateCatcher(index, { Output: value })}
                        rows={3}
                        placeholder={'{\n  "handled": true,\n  "error": "{% $states.errorOutput %}"\n}'}
                        hint="Optional output passed to the recovery state."
                        monoFieldClass={monoFieldClass}
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
        </CollapsibleSection>
      )}
    </div>
  );
}
