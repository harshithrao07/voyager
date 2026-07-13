import { AlertCircle, RefreshCw } from 'lucide-react';
import { validateCron } from '../../utils/cronValidation';

type Props = {
  name: string;
  onNameChange: (value: string) => void;
  maxAttempts: number;
  onMaxAttemptsChange: (value: number) => void;
  idempotencyKey: string;
  onIdempotencyKeyChange: (value: string) => void;
  cronExpression: string;
  onCronExpressionChange: (value: string) => void;
  timezone: string;
  onTimezoneChange: (value: string) => void;
  fieldClass: string;
  monoFieldClass: string;
};

// IANA zones, grouped by region. Falls back to a curated list on engines
// without Intl.supportedValuesOf.
function loadTimezones(): string[] {
  try {
    const supportedValuesOf = (Intl as unknown as { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf;
    if (typeof supportedValuesOf === 'function') {
      return supportedValuesOf('timeZone');
    }
  } catch {
    // fall through to the curated list
  }
  return [
    'UTC', 'America/New_York', 'America/Chicago', 'America/Denver', 'America/Los_Angeles',
    'America/Sao_Paulo', 'Europe/London', 'Europe/Berlin', 'Europe/Paris', 'Europe/Moscow',
    'Asia/Dubai', 'Asia/Kolkata', 'Asia/Shanghai', 'Asia/Singapore', 'Asia/Tokyo',
    'Australia/Sydney', 'Pacific/Auckland',
  ];
}

const ALL_TIMEZONES = loadTimezones();
const TIMEZONE_GROUPS = (() => {
  const groups = new Map<string, string[]>();
  for (const tz of ALL_TIMEZONES) {
    if (tz === 'UTC') continue;
    const region = tz.includes('/') ? tz.split('/')[0] : 'Other';
    const list = groups.get(region) ?? [];
    list.push(tz);
    groups.set(region, list);
  }
  return [...groups.entries()];
})();

function timezoneLabel(tz: string): string {
  return tz.includes('/') ? tz.split('/').slice(1).join(' / ').replace(/_/g, ' ') : tz;
}

function generateIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return `workflow-${crypto.randomUUID()}`;
  }
  return `workflow-${Date.now()}`;
}

// ---- Schedule builder (frequency -> Spring 6-field cron) ------------------

type Schedule =
  | { type: 'manual' }
  | { type: 'minutes'; interval: number }
  | { type: 'hourly'; minute: number }
  | { type: 'daily'; minute: number; hour: number }
  | { type: 'weekly'; minute: number; hour: number; dow: number }
  | { type: 'monthly'; minute: number; hour: number; dom: number }
  | { type: 'custom' };

type FrequencyType = Exclude<Schedule['type'], 'manual' | 'custom'>;

const WEEKDAYS = [
  { value: 1, label: 'Monday' },
  { value: 2, label: 'Tuesday' },
  { value: 3, label: 'Wednesday' },
  { value: 4, label: 'Thursday' },
  { value: 5, label: 'Friday' },
  { value: 6, label: 'Saturday' },
  { value: 0, label: 'Sunday' },
];

function parseSchedule(cron: string): Schedule {
  const expr = cron.trim();
  if (!expr) return { type: 'manual' };
  let match: RegExpMatchArray | null;
  if ((match = expr.match(/^0 \*\/(\d+) \* \* \* \*$/))) return { type: 'minutes', interval: Number(match[1]) };
  if ((match = expr.match(/^0 (\d+) \* \* \* \*$/))) return { type: 'hourly', minute: Number(match[1]) };
  if ((match = expr.match(/^0 (\d+) (\d+) \* \* \*$/))) return { type: 'daily', minute: Number(match[1]), hour: Number(match[2]) };
  if ((match = expr.match(/^0 (\d+) (\d+) \* \* (\d+)$/))) return { type: 'weekly', minute: Number(match[1]), hour: Number(match[2]), dow: Number(match[3]) };
  if ((match = expr.match(/^0 (\d+) (\d+) (\d+) \* \*$/))) return { type: 'monthly', minute: Number(match[1]), hour: Number(match[2]), dom: Number(match[3]) };
  return { type: 'custom' };
}

function scheduleToCron(schedule: Schedule): string {
  switch (schedule.type) {
    case 'minutes': return `0 */${schedule.interval} * * * *`;
    case 'hourly': return `0 ${schedule.minute} * * * *`;
    case 'daily': return `0 ${schedule.minute} ${schedule.hour} * * *`;
    case 'weekly': return `0 ${schedule.minute} ${schedule.hour} * * ${schedule.dow}`;
    case 'monthly': return `0 ${schedule.minute} ${schedule.hour} ${schedule.dom} * *`;
    default: return '';
  }
}

const pad = (n: number) => String(n).padStart(2, '0');

function ScheduleBuilder({
  cronExpression,
  onCronExpressionChange,
  fieldClass,
}: {
  cronExpression: string;
  onCronExpressionChange: (value: string) => void;
  fieldClass: string;
}) {
  const schedule = parseSchedule(cronExpression);
  const isManual = schedule.type === 'manual';
  const isCustom = schedule.type === 'custom';

  const minute = 'minute' in schedule ? schedule.minute : 0;
  const hour = 'hour' in schedule ? schedule.hour : 9;
  const dow = schedule.type === 'weekly' ? schedule.dow : 1;
  const dom = schedule.type === 'monthly' ? schedule.dom : 1;
  const interval = schedule.type === 'minutes' ? schedule.interval : 15;

  const rebuild = (over: Partial<{ minute: number; hour: number; dow: number; dom: number; interval: number }>) => {
    const m = over.minute ?? minute;
    const h = over.hour ?? hour;
    switch (schedule.type) {
      case 'minutes': onCronExpressionChange(scheduleToCron({ type: 'minutes', interval: over.interval ?? interval })); break;
      case 'hourly': onCronExpressionChange(scheduleToCron({ type: 'hourly', minute: m })); break;
      case 'daily': onCronExpressionChange(scheduleToCron({ type: 'daily', minute: m, hour: h })); break;
      case 'weekly': onCronExpressionChange(scheduleToCron({ type: 'weekly', minute: m, hour: h, dow: over.dow ?? dow })); break;
      case 'monthly': onCronExpressionChange(scheduleToCron({ type: 'monthly', minute: m, hour: h, dom: over.dom ?? dom })); break;
      default: break;
    }
  };

  const setFrequency = (type: FrequencyType) => {
    switch (type) {
      case 'minutes': onCronExpressionChange(scheduleToCron({ type: 'minutes', interval })); break;
      case 'hourly': onCronExpressionChange(scheduleToCron({ type: 'hourly', minute })); break;
      case 'daily': onCronExpressionChange(scheduleToCron({ type: 'daily', minute, hour })); break;
      case 'weekly': onCronExpressionChange(scheduleToCron({ type: 'weekly', minute, hour, dow })); break;
      case 'monthly': onCronExpressionChange(scheduleToCron({ type: 'monthly', minute, hour, dom })); break;
    }
  };

  return (
    <div className="space-y-3">
      <div className="grid grid-cols-2 gap-1 rounded-DEFAULT border border-border-subtle p-1">
        {(['manual', 'recurring'] as const).map((mode) => {
          const active = mode === 'manual' ? isManual : !isManual;
          return (
            <button
              key={mode}
              type="button"
              onClick={() => onCronExpressionChange(mode === 'manual' ? '' : scheduleToCron({ type: 'daily', minute: 0, hour: 9 }))}
              className={`h-8 rounded-[3px] font-mono-sm text-[11px] capitalize transition-colors ${
                active ? 'bg-secondary-container/50 text-secondary-fixed' : 'text-on-surface-variant hover:text-on-surface'
              }`}
            >
              {mode === 'manual' ? 'Manual' : 'Recurring'}
            </button>
          );
        })}
      </div>

      {isManual ? (
        <p className="text-[11px] text-on-surface-variant/70">Runs only when triggered manually.</p>
      ) : isCustom ? (
        <p className="flex items-start gap-1.5 text-[11px] text-on-surface-variant">
          <AlertCircle size={13} className="mt-px shrink-0" />
          This cron is too advanced for the simple builder — edit it under Advanced, or pick a frequency to replace it.
        </p>
      ) : null}

      {!isManual && (
        <>
          <label className="block">
            <span className="text-[11px] text-on-surface-variant">Frequency</span>
            <select
              value={isCustom ? 'custom' : schedule.type}
              onChange={(event) => {
                if (event.target.value !== 'custom') setFrequency(event.target.value as FrequencyType);
              }}
              className={fieldClass}
            >
              {isCustom && <option value="custom">Custom (see Advanced)</option>}
              <option value="minutes">Every N minutes</option>
              <option value="hourly">Hourly</option>
              <option value="daily">Daily</option>
              <option value="weekly">Weekly</option>
              <option value="monthly">Monthly</option>
            </select>
          </label>

          {schedule.type === 'minutes' && (
            <label className="block">
              <span className="text-[11px] text-on-surface-variant">Every</span>
              <div className="flex items-center gap-2">
                <input
                  type="number"
                  min={1}
                  max={59}
                  value={interval}
                  onChange={(event) => rebuild({ interval: Math.min(59, Math.max(1, Number(event.target.value) || 1)) })}
                  className={fieldClass}
                />
                <span className="shrink-0 text-[11px] text-on-surface-variant">minutes</span>
              </div>
            </label>
          )}

          {schedule.type === 'hourly' && (
            <label className="block">
              <span className="text-[11px] text-on-surface-variant">At minute</span>
              <input
                type="number"
                min={0}
                max={59}
                value={minute}
                onChange={(event) => rebuild({ minute: Math.min(59, Math.max(0, Number(event.target.value) || 0)) })}
                className={fieldClass}
              />
            </label>
          )}

          {schedule.type === 'weekly' && (
            <label className="block">
              <span className="text-[11px] text-on-surface-variant">On</span>
              <select value={dow} onChange={(event) => rebuild({ dow: Number(event.target.value) })} className={fieldClass}>
                {WEEKDAYS.map((day) => (
                  <option key={day.value} value={day.value}>{day.label}</option>
                ))}
              </select>
            </label>
          )}

          {schedule.type === 'monthly' && (
            <label className="block">
              <span className="text-[11px] text-on-surface-variant">On day of month</span>
              <select value={dom} onChange={(event) => rebuild({ dom: Number(event.target.value) })} className={fieldClass}>
                {Array.from({ length: 31 }, (_, index) => index + 1).map((day) => (
                  <option key={day} value={day}>{day}</option>
                ))}
              </select>
            </label>
          )}

          {(schedule.type === 'daily' || schedule.type === 'weekly' || schedule.type === 'monthly') && (
            <div>
              <span className="text-[11px] text-on-surface-variant">At time</span>
              <div className="flex items-center gap-2">
                <select value={hour} onChange={(event) => rebuild({ hour: Number(event.target.value) })} className={fieldClass} aria-label="Hour">
                  {Array.from({ length: 24 }, (_, index) => index).map((h) => (
                    <option key={h} value={h}>{pad(h)}</option>
                  ))}
                </select>
                <span className="mt-2 shrink-0 text-on-surface-variant">:</span>
                <select value={minute} onChange={(event) => rebuild({ minute: Number(event.target.value) })} className={fieldClass} aria-label="Minute">
                  {Array.from({ length: 60 }, (_, index) => index).map((m) => (
                    <option key={m} value={m}>{pad(m)}</option>
                  ))}
                </select>
              </div>
            </div>
          )}

          {!isCustom && (
            <p className="font-mono-sm text-[10px] text-on-surface-variant/70">
              Runs as <span className="text-on-surface-variant">{cronExpression}</span>
            </p>
          )}
        </>
      )}
    </div>
  );
}

export function WorkflowMetadataForm({
  name,
  onNameChange,
  maxAttempts,
  onMaxAttemptsChange,
  idempotencyKey,
  onIdempotencyKeyChange,
  cronExpression,
  onCronExpressionChange,
  timezone,
  onTimezoneChange,
  fieldClass,
  monoFieldClass,
}: Props) {
  const cronError = validateCron(cronExpression);
  const timezoneMissing = timezone !== '' && timezone !== 'UTC' && !ALL_TIMEZONES.includes(timezone);
  const isManual = cronExpression.trim() === '';

  return (
    <div className="space-y-6">
      <h2 className="font-headline-lg text-headline-lg text-on-surface">Workflow settings</h2>
      <section className="space-y-4">
        <label className="block">
          <span className="text-body-sm text-on-surface">Name</span>
          <input value={name} onChange={(event) => onNameChange(event.target.value)} className={fieldClass} placeholder="Invoice approval" />
        </label>
        <label className="block">
          <span className="text-body-sm text-on-surface">Attempts</span>
          <input type="number" min={0} value={maxAttempts} onChange={(event) => onMaxAttemptsChange(Number(event.target.value))} className={fieldClass} />
        </label>
      </section>
      <div className="h-px bg-border-subtle" />
      <section className="space-y-4">
        <h3 className="font-headline-md text-headline-md text-on-surface">Schedule</h3>
        <ScheduleBuilder
          cronExpression={cronExpression}
          onCronExpressionChange={onCronExpressionChange}
          fieldClass={fieldClass}
        />
        {!isManual && (
          <label className="block">
            <span className="text-body-sm text-on-surface">Timezone</span>
            <select value={timezone || 'UTC'} onChange={(event) => onTimezoneChange(event.target.value)} className={fieldClass}>
              <option value="UTC">UTC</option>
              {timezoneMissing && <option value={timezone}>{timezone}</option>}
              {TIMEZONE_GROUPS.map(([region, zones]) => (
                <optgroup key={region} label={region}>
                  {zones.map((tz) => (
                    <option key={tz} value={tz}>{timezoneLabel(tz)}</option>
                  ))}
                </optgroup>
              ))}
            </select>
          </label>
        )}
      </section>
      <div className="h-px bg-border-subtle" />
      <details className="group">
        <summary className="flex cursor-pointer list-none items-center gap-1.5 text-body-sm text-on-surface-variant [&::-webkit-details-marker]:hidden">
          <span className="material-symbols-outlined text-[18px] transition-transform group-open:rotate-90">chevron_right</span>
          Advanced
        </summary>
        <div className="mt-4 space-y-5">
          <div>
            <span className="text-body-sm text-on-surface">Cron expression</span>
            <input
              value={cronExpression}
              onChange={(event) => onCronExpressionChange(event.target.value)}
              className={`${monoFieldClass} ${cronError ? 'border-status-error' : ''}`}
              placeholder="Leave empty for manual trigger"
              spellCheck={false}
            />
            {cronError ? (
              <span className="mt-1 flex items-start gap-1.5 text-[11px] text-status-error">
                <AlertCircle size={13} className="mt-px shrink-0" />
                {cronError}
              </span>
            ) : (
              <span className="mt-1 block text-[11px] text-on-surface-variant/70">
                Six fields: second minute hour day month weekday. Drives the frequency picker above.
              </span>
            )}
          </div>
          <div>
            <span className="text-body-sm text-on-surface">Idempotency key</span>
            <div className="mt-1 flex items-center gap-2">
              <input value={idempotencyKey} readOnly className={`${monoFieldClass} cursor-default opacity-80`} />
              <button
                type="button"
                onClick={() => onIdempotencyKeyChange(generateIdempotencyKey())}
                title="Generate a new key"
                className="flex h-9 w-9 shrink-0 items-center justify-center rounded-DEFAULT border border-border-subtle text-on-surface-variant transition-colors hover:border-secondary hover:text-secondary"
              >
                <RefreshCw size={14} />
              </button>
            </div>
            <span className="mt-1 block text-[11px] text-on-surface-variant/70">
              Auto-generated to prevent duplicate submissions. You normally never need to change this.
            </span>
          </div>
        </div>
      </details>
    </div>
  );
}
