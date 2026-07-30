import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, Archive, Clock3, Pause, Play, Save, Settings2, X } from 'lucide-react';
import Editor from '@monaco-editor/react';
import { toast } from 'sonner';
import {
  archiveWorkflow,
  getWorkflow,
  pauseWorkflow,
  resumeWorkflow,
  updateWorkflowMetadata,
  type UpdateWorkflowMetadataRequest,
  type WorkflowResponseDTO,
} from '../api';
import { validateCron } from '../utils/cronValidation';
import {
  ALL_TIMEZONES,
  ScheduleBuilder,
  TIMEZONE_GROUPS,
  timezoneLabel,
} from './workflow-create/WorkflowMetadataForm';

type Props = {
  workflow: WorkflowResponseDTO;
  onClose: () => void;
  onDirtyChange: (dirty: boolean) => void;
  onWorkflowUpdated: (workflow: WorkflowResponseDTO) => void;
  onWorkflowArchived: (workflow: WorkflowResponseDTO) => void;
};

type BusyAction = 'save' | 'pause' | 'resume' | 'archive' | null;

const fieldClass = 'mt-1.5 h-10 w-full rounded-DEFAULT border border-border-subtle bg-surface-container-lowest px-3 text-body-sm text-on-surface outline-none transition-colors placeholder:text-on-surface-variant/55 focus:border-secondary/65 focus:ring-1 focus:ring-secondary/20';
const monoFieldClass = `${fieldClass} font-mono-sm text-[11px]`;

function statusTone(status: WorkflowResponseDTO['status']) {
  if (status === 'ACTIVE') return 'border-status-success/30 bg-status-success/10 text-status-success';
  if (status === 'PAUSED') return 'border-status-info/30 bg-status-info/10 text-status-info';
  if (status === 'ARCHIVED') return 'border-border-muted bg-surface-container text-on-surface-variant';
  return 'border-primary/30 bg-primary/10 text-primary';
}

function formatNextRun(value?: string | null) {
  if (!value) return 'No upcoming run';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    timeZoneName: 'short',
  }).format(date);
}

function isVersionConflict(error: unknown) {
  return error instanceof Error && /^409\s+-/i.test(error.message);
}

export function WorkflowSettingsPanel({
  workflow,
  onClose,
  onDirtyChange,
  onWorkflowUpdated,
  onWorkflowArchived,
}: Props) {
  const [name, setName] = useState(workflow.name);
  const [maxAttempts, setMaxAttempts] = useState(workflow.maxAttempts);
  const [cronExpression, setCronExpression] = useState(workflow.cronExpression || '');
  const [scheduledInputText, setScheduledInputText] = useState(
    JSON.stringify(workflow.scheduledInput ?? {}, null, 2),
  );
  const [timezone, setTimezone] = useState(workflow.timezone || 'UTC');
  const [busyAction, setBusyAction] = useState<BusyAction>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [discardDialogOpen, setDiscardDialogOpen] = useState(false);

  const loadWorkflowDraft = (nextWorkflow: WorkflowResponseDTO) => {
    setName(nextWorkflow.name);
    setMaxAttempts(nextWorkflow.maxAttempts);
    setCronExpression(nextWorkflow.cronExpression || '');
    setScheduledInputText(JSON.stringify(nextWorkflow.scheduledInput ?? {}, null, 2));
    setTimezone(nextWorkflow.timezone || 'UTC');
  };

  const normalizedName = name.trim();
  const normalizedCron = cronExpression.trim();
  const currentCron = workflow.cronExpression?.trim() || '';
  const recurring = normalizedCron.length > 0;
  const cronError = validateCron(cronExpression);
  const timezoneMissing = recurring && !timezone.trim();
  const attemptsInvalid = !Number.isInteger(maxAttempts) || maxAttempts < 0;
  let parsedScheduledInput: unknown;
  let scheduledInputError: string | null = null;
  try {
    parsedScheduledInput = JSON.parse(scheduledInputText);
  } catch (error) {
    scheduledInputError = error instanceof Error ? error.message : 'Scheduled input must be valid JSON.';
  }
  const currentScheduledInputText = JSON.stringify(workflow.scheduledInput ?? {});
  const normalizedScheduledInputText = scheduledInputError
    ? scheduledInputText
    : JSON.stringify(parsedScheduledInput);
  const scheduledInputDirty = normalizedScheduledInputText !== currentScheduledInputText;
  const dirty = useMemo(() => (
    normalizedName !== workflow.name
    || normalizedCron !== currentCron
    || scheduledInputDirty
    || maxAttempts !== workflow.maxAttempts
    || (recurring && timezone !== (workflow.timezone || 'UTC'))
  ), [currentCron, maxAttempts, normalizedCron, normalizedName, recurring, scheduledInputDirty, timezone, workflow.maxAttempts, workflow.name, workflow.timezone]);
  const canSave = dirty
    && normalizedName.length > 0
    && !attemptsInvalid
    && !cronError
    && !scheduledInputError
    && !timezoneMissing
    && !busyAction;

  useEffect(() => {
    onDirtyChange(dirty);
    return () => onDirtyChange(false);
  }, [dirty, onDirtyChange]);

  useEffect(() => {
    if (!dirty) return undefined;
    const warnBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warnBeforeUnload);
    return () => window.removeEventListener('beforeunload', warnBeforeUnload);
  }, [dirty]);

  const requestClose = useCallback(() => {
    if (dirty) {
      setDiscardDialogOpen(true);
      return;
    }
    onClose();
  }, [dirty, onClose]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      if (discardDialogOpen) {
        setDiscardDialogOpen(false);
        return;
      }
      requestClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [discardDialogOpen, requestClose]);

  const refreshAfterConflict = async () => {
    const refreshed = await getWorkflow({ workflowId: workflow.id });
    loadWorkflowDraft(refreshed);
    onWorkflowUpdated(refreshed);
    setFormError('This workflow changed in another session. Latest settings were loaded; review them before saving again.');
  };

  const handleSave = async () => {
    if (!canSave) return;

    const request: UpdateWorkflowMetadataRequest = { expectedVersion: workflow.version };
    if (normalizedName !== workflow.name) request.name = normalizedName;
    if (normalizedCron !== currentCron) request.cronExpression = normalizedCron || null;
    if (scheduledInputDirty) request.scheduledInput = parsedScheduledInput;
    if (maxAttempts !== workflow.maxAttempts) request.maxAttempts = maxAttempts;
    if (recurring && (!currentCron || !workflow.timezone || timezone !== workflow.timezone)) {
      request.timezone = timezone;
    }

    setBusyAction('save');
    setFormError(null);
    try {
      const updated = await updateWorkflowMetadata(workflow.id, request);
      loadWorkflowDraft(updated);
      onWorkflowUpdated(updated);
      toast.success('Workflow settings saved.');
    } catch (error) {
      if (isVersionConflict(error)) {
        try {
          await refreshAfterConflict();
        } catch (refreshError) {
          setFormError(refreshError instanceof Error ? refreshError.message : 'Could not refresh workflow settings.');
        }
      } else {
        setFormError(error instanceof Error ? error.message : 'Could not save workflow settings.');
      }
    } finally {
      setBusyAction(null);
    }
  };

  const handleScheduleLifecycle = async (action: 'pause' | 'resume') => {
    setBusyAction(action);
    setFormError(null);
    try {
      const updated = action === 'pause'
        ? await pauseWorkflow(workflow.id)
        : await resumeWorkflow(workflow.id);
      loadWorkflowDraft(updated);
      onWorkflowUpdated(updated);
      toast.success(action === 'pause' ? 'Schedule paused.' : 'Schedule resumed.');
    } catch (error) {
      setFormError(error instanceof Error ? error.message : `Could not ${action} the schedule.`);
    } finally {
      setBusyAction(null);
    }
  };

  const handleArchive = async () => {
    const confirmed = window.confirm(
      `Archive “${workflow.name}”? Its revisions and execution history will be preserved, but it cannot run or be edited.`,
    );
    if (!confirmed) return;

    setBusyAction('archive');
    setFormError(null);
    try {
      const archived = await archiveWorkflow(workflow.id);
      onWorkflowArchived(archived);
      toast.success('Workflow archived. Revisions and executions are still available.');
    } catch (error) {
      setFormError(error instanceof Error ? error.message : 'Could not archive the workflow.');
    } finally {
      setBusyAction(null);
    }
  };

  const storedTimezone = timezone !== 'UTC' && !ALL_TIMEZONES.includes(timezone);
  const lifecycleBusy = Boolean(busyAction);

  return (
    <div
      className="fixed inset-0 z-50 flex justify-end bg-background/65 backdrop-blur-[2px]"
      onClick={requestClose}
      role="presentation"
    >
      <aside
        role="dialog"
        aria-modal="true"
        aria-labelledby="workflow-settings-title"
        className="flex h-full w-full max-w-[460px] flex-col border-l border-border-subtle bg-surface-base shadow-[-24px_0_70px_rgba(0,0,0,0.38)]"
        onClick={(event) => event.stopPropagation()}
      >
        <header className="flex shrink-0 items-start justify-between border-b border-border-subtle px-5 py-4">
          <div className="min-w-0">
            <div className="flex items-center gap-2 font-mono-sm text-[10px] uppercase tracking-[0.18em] text-on-surface-variant">
              <Settings2 size={13} /> Workflow control
            </div>
            <h2 id="workflow-settings-title" className="mt-1 truncate font-display text-[18px] font-semibold text-primary">
              Settings
            </h2>
          </div>
          <button
            type="button"
            onClick={requestClose}
            className="flex h-9 w-9 items-center justify-center rounded-DEFAULT border border-border-subtle text-on-surface-variant transition-colors hover:border-border-muted hover:bg-surface-container hover:text-primary"
            aria-label="Close workflow settings"
          >
            <X size={17} />
          </button>
        </header>

        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-5">
          <div className="grid grid-cols-2 overflow-hidden rounded-lg border border-border-subtle bg-surface-container-lowest">
            <div className="border-r border-border-subtle px-4 py-3">
              <div className="font-mono-sm text-[9px] uppercase tracking-[0.16em] text-on-surface-variant">Status</div>
              <span data-testid="workflow-settings-status" className={`mt-2 inline-flex rounded-full border px-2 py-1 font-mono-sm text-[10px] font-semibold ${statusTone(workflow.status)}`}>
                {workflow.status}
              </span>
            </div>
            <div className="px-4 py-3">
              <div className="font-mono-sm text-[9px] uppercase tracking-[0.16em] text-on-surface-variant">Trigger</div>
              <div className="mt-2 flex items-center gap-2 text-body-sm font-medium text-on-surface">
                <Clock3 size={14} className="text-secondary" />
                {recurring ? 'Recurring' : 'Manual'}
              </div>
            </div>
          </div>

          {formError && (
            <div className="mt-4 flex items-start gap-2 rounded-DEFAULT border border-status-error/30 bg-status-error/10 px-3 py-2.5 text-[11px] leading-5 text-status-error">
              <AlertTriangle size={14} className="mt-0.5 shrink-0" />
              <span>{formError}</span>
            </div>
          )}

          <section className="mt-6 space-y-4">
            <div>
              <div className="font-mono-sm text-[10px] uppercase tracking-[0.16em] text-secondary">Identity</div>
              <p className="mt-1 text-[11px] leading-5 text-on-surface-variant">These fields belong to the workflow, not to an immutable definition revision.</p>
            </div>
            <label className="block">
              <span className="text-body-sm text-on-surface">Workflow name</span>
              <input value={name} onChange={(event) => setName(event.target.value)} className={fieldClass} />
              {!normalizedName && <span className="mt-1 block text-[11px] text-status-error">Name cannot be blank.</span>}
            </label>
            <label className="block">
              <span className="text-body-sm text-on-surface">Workflow-level attempts</span>
              <input
                type="number"
                min={0}
                step={1}
                value={maxAttempts}
                onChange={(event) => setMaxAttempts(Number(event.target.value))}
                className={fieldClass}
              />
              <span className="mt-1 block text-[11px] leading-5 text-on-surface-variant/75">
                This does not replace state-specific ASL Retry on Task, Map, or Parallel states.
              </span>
              {attemptsInvalid && <span className="mt-1 block text-[11px] text-status-error">Use a whole number of zero or more.</span>}
            </label>
          </section>

          <div className="my-6 h-px bg-border-subtle" />

          <section className="space-y-4">
            <div>
              <div className="font-mono-sm text-[10px] uppercase tracking-[0.16em] text-secondary">Trigger policy</div>
              <p className="mt-1 text-[11px] leading-5 text-on-surface-variant">
                Schedule metadata stays outside the ASL definition and applies to whichever revision is active.
              </p>
            </div>
            <ScheduleBuilder
              cronExpression={cronExpression}
              onCronExpressionChange={setCronExpression}
              fieldClass={fieldClass}
            />
            {recurring && (
              <>
                <label className="block">
                  <span className="text-body-sm text-on-surface">Timezone</span>
                  <select data-testid="workflow-timezone" value={timezone || 'UTC'} onChange={(event) => setTimezone(event.target.value)} className={fieldClass}>
                    <option value="UTC">UTC</option>
                    {storedTimezone && <option value={timezone}>{timezone}</option>}
                    {TIMEZONE_GROUPS.map(([region, zones]) => (
                      <optgroup key={region} label={region}>
                        {zones.map((zone) => <option key={zone} value={zone}>{timezoneLabel(zone)}</option>)}
                      </optgroup>
                    ))}
                  </select>
                </label>
                <div>
                  <div className="text-body-sm text-on-surface">Scheduled run input</div>
                  <p className="mt-1 text-[11px] leading-5 text-on-surface-variant/75">
                    This JSON becomes <span className="font-mono-sm">$states.input</span> for every recurring run.
                  </p>
                  <div
                    data-testid="workflow-scheduled-input"
                    className={`mt-2 h-56 overflow-hidden rounded-DEFAULT border bg-surface-container-lowest ${scheduledInputError ? 'border-status-error' : 'border-border-subtle'}`}
                  >
                    <Editor
                      height="100%"
                      defaultLanguage="json"
                      theme="vs-dark"
                      value={scheduledInputText}
                      onChange={(value) => setScheduledInputText(value || '')}
                      options={{
                        ariaLabel: 'Scheduled run input JSON',
                        minimap: { enabled: false },
                        scrollBeyondLastLine: false,
                        fontSize: 12,
                        fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
                        wordWrap: 'on',
                        tabSize: 2,
                        lineNumbersMinChars: 3,
                        padding: { top: 10, bottom: 10 },
                        automaticLayout: true,
                      }}
                    />
                  </div>
                  {scheduledInputError && (
                    <span className="mt-1 flex items-start gap-1.5 text-[11px] text-status-error">
                      <AlertTriangle size={13} className="mt-0.5 shrink-0" /> {scheduledInputError}
                    </span>
                  )}
                </div>
              </>
            )}
            <details className="group rounded-DEFAULT border border-border-subtle bg-surface-container-lowest px-3 py-2.5">
              <summary className="cursor-pointer list-none font-mono-sm text-[10px] uppercase tracking-[0.12em] text-on-surface-variant [&::-webkit-details-marker]:hidden">
                Advanced cron
              </summary>
              <div className="mt-3">
                <input
                  data-testid="workflow-cron-expression"
                  value={cronExpression}
                  onChange={(event) => setCronExpression(event.target.value)}
                  className={`${monoFieldClass} ${cronError ? 'border-status-error' : ''}`}
                  placeholder="Leave empty for manual trigger"
                  spellCheck={false}
                />
                {cronError ? (
                  <span className="mt-1 flex items-start gap-1.5 text-[11px] text-status-error">
                    <AlertTriangle size={13} className="mt-0.5 shrink-0" /> {cronError}
                  </span>
                ) : (
                  <span className="mt-1 block text-[10px] leading-5 text-on-surface-variant/70">
                    Six fields: second minute hour day month weekday.
                  </span>
                )}
              </div>
            </details>
            {workflow.status === 'ACTIVE' && !currentCron && recurring && (
              <div className="rounded-DEFAULT border border-status-info/25 bg-status-info/10 px-3 py-2.5 text-[11px] leading-5 text-status-info">
                Saving this schedule starts recurring execution immediately because the workflow is already active.
              </div>
            )}
          </section>

          <div className="my-6 h-px bg-border-subtle" />

          <section>
            <div className="font-mono-sm text-[10px] uppercase tracking-[0.16em] text-secondary">Schedule lifecycle</div>
            {currentCron ? (
              <div className="mt-3 rounded-lg border border-border-subtle bg-surface-container-lowest p-4">
                <div data-testid="workflow-next-run" className="mb-3 rounded-DEFAULT border border-border-muted bg-surface-container-low px-3 py-2">
                  <div className="font-mono-sm text-[9px] uppercase tracking-[0.14em] text-on-surface-variant">Next scheduled run</div>
                  <div className="mt-1 font-mono-sm text-[11px] text-on-surface">{formatNextRun(workflow.nextRunAt)}</div>
                </div>
                <p className="text-[11px] leading-5 text-on-surface-variant">
                  {workflow.status === 'PAUSED'
                    ? 'The schedule is paused. Manual executions are still allowed.'
                    : workflow.status === 'DRAFT'
                      ? 'Activate a definition revision from the workflow header when this schedule is ready.'
                      : 'Pause future scheduled runs without disabling manual execution.'}
                </p>
                {workflow.status === 'ACTIVE' && (
                  <button
                    type="button"
                    data-testid="workflow-pause-schedule"
                    onClick={() => void handleScheduleLifecycle('pause')}
                    disabled={lifecycleBusy || dirty}
                    className="mt-3 flex h-9 w-full items-center justify-center gap-2 rounded-DEFAULT border border-status-info/35 bg-status-info/10 text-body-sm font-medium text-status-info transition-colors hover:bg-status-info/15 disabled:cursor-not-allowed disabled:opacity-45"
                    title={dirty ? 'Save or discard metadata changes before pausing' : undefined}
                  >
                    <Pause size={14} /> {busyAction === 'pause' ? 'Pausing...' : 'Pause schedule'}
                  </button>
                )}
                {workflow.status === 'PAUSED' && (
                  <button
                    type="button"
                    data-testid="workflow-resume-schedule"
                    onClick={() => void handleScheduleLifecycle('resume')}
                    disabled={lifecycleBusy || dirty}
                    className="mt-3 flex h-9 w-full items-center justify-center gap-2 rounded-DEFAULT border border-status-success/35 bg-status-success/10 text-body-sm font-medium text-status-success transition-colors hover:bg-status-success/15 disabled:cursor-not-allowed disabled:opacity-45"
                    title={dirty ? 'Save or discard metadata changes before resuming' : undefined}
                  >
                    <Play size={14} /> {busyAction === 'resume' ? 'Resuming...' : 'Resume schedule'}
                  </button>
                )}
              </div>
            ) : (
              <p className="mt-2 text-[11px] leading-5 text-on-surface-variant">
                Manual workflows run on demand and do not have a schedule to pause.
              </p>
            )}
          </section>

          <div className="my-6 h-px bg-border-subtle" />

          <section className="rounded-lg border border-status-error/25 bg-status-error/[0.04] p-4">
            <div className="flex items-start gap-3">
              <Archive size={16} className="mt-0.5 shrink-0 text-status-error" />
              <div>
                <div className="text-body-sm font-medium text-on-surface">Archive workflow</div>
                <p className="mt-1 text-[11px] leading-5 text-on-surface-variant">
                  Stops scheduling and editing. Definition revisions and execution history remain available from this workflow detail.
                </p>
              </div>
            </div>
            <button
              type="button"
              data-testid="workflow-archive"
              onClick={() => void handleArchive()}
              disabled={lifecycleBusy || dirty}
              className="mt-3 flex h-9 w-full items-center justify-center gap-2 rounded-DEFAULT border border-status-error/35 text-body-sm font-medium text-status-error transition-colors hover:bg-status-error/10 disabled:cursor-not-allowed disabled:opacity-45"
              title={dirty ? 'Save or discard metadata changes before archiving' : undefined}
            >
              <Archive size={14} /> {busyAction === 'archive' ? 'Archiving...' : 'Archive workflow'}
            </button>
          </section>
        </div>

        <footer className="flex shrink-0 items-center justify-between gap-3 border-t border-border-subtle bg-surface-container-lowest px-5 py-4">
          <span className="font-mono-sm text-[10px] text-on-surface-variant">Version {workflow.version}</span>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={requestClose}
              className="h-9 rounded-DEFAULT border border-border-subtle px-3 text-body-sm text-on-surface-variant transition-colors hover:bg-surface-container hover:text-on-surface"
            >
              Close
            </button>
            <button
              type="button"
              onClick={() => void handleSave()}
              disabled={!canSave}
              className="flex h-9 min-w-[126px] items-center justify-center gap-2 rounded-DEFAULT bg-primary px-4 text-body-sm font-medium text-on-primary transition-colors hover:bg-primary-fixed-dim disabled:cursor-not-allowed disabled:opacity-45"
            >
              <Save size={14} /> {busyAction === 'save' ? 'Saving...' : 'Save changes'}
            </button>
          </div>
        </footer>
      </aside>
      {discardDialogOpen && (
        <div
          className="fixed inset-0 z-[80] flex items-center justify-center bg-background/80 p-6 backdrop-blur-sm"
          onClick={(event) => {
            event.stopPropagation();
            setDiscardDialogOpen(false);
          }}
          role="presentation"
        >
          <div
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="discard-settings-title"
            aria-describedby="discard-settings-description"
            data-testid="workflow-settings-discard-dialog"
            className="w-full max-w-md rounded-lg border border-border-subtle bg-surface-elevated shadow-2xl"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="flex items-start gap-3 border-b border-border-subtle px-5 py-4">
              <div className="rounded-DEFAULT border border-status-warning/35 bg-status-warning/10 p-2 text-status-warning">
                <AlertTriangle size={18} />
              </div>
              <div>
                <h2 id="discard-settings-title" className="font-headline-lg text-headline-lg text-primary">
                  Discard unsaved settings?
                </h2>
                <p id="discard-settings-description" className="mt-1 text-body-sm leading-5 text-on-surface-variant">
                  Your workflow setting changes have not been saved and will be lost.
                </p>
              </div>
            </div>
            <div className="flex justify-end gap-2 px-5 py-4">
              <button
                type="button"
                onClick={() => setDiscardDialogOpen(false)}
                className="h-9 rounded-DEFAULT border border-border-subtle px-4 text-body-sm text-on-surface transition-colors hover:bg-surface-container-high"
              >
                Keep editing
              </button>
              <button
                type="button"
                data-testid="workflow-settings-confirm-discard"
                onClick={onClose}
                className="h-9 rounded-DEFAULT border border-status-error/40 bg-status-error/10 px-4 text-body-sm font-medium text-status-error transition-colors hover:bg-status-error/15"
              >
                Discard changes
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
