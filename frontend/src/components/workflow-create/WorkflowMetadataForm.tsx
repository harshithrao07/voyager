import type { WorkflowPriorityDTO } from '../../api';

type Props = {
  name: string;
  onNameChange: (value: string) => void;
  priority: WorkflowPriorityDTO;
  onPriorityChange: (value: WorkflowPriorityDTO) => void;
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

export function WorkflowMetadataForm({
  name,
  onNameChange,
  priority,
  onPriorityChange,
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
  return (
    <div className="space-y-6">
      <h2 className="font-headline-lg text-headline-lg text-on-surface">Workflow settings</h2>
      <section className="space-y-4">
        <label className="block">
          <span className="text-body-sm text-on-surface">Name</span>
          <input value={name} onChange={(event) => onNameChange(event.target.value)} className={fieldClass} placeholder="Invoice approval" />
        </label>
        <div className="grid grid-cols-2 gap-4">
          <label className="block">
            <span className="text-body-sm text-on-surface">Priority</span>
            <select value={priority} onChange={(event) => onPriorityChange(event.target.value as WorkflowPriorityDTO)} className={fieldClass}>
              <option value="HIGH">High</option>
              <option value="MEDIUM">Medium</option>
              <option value="LOW">Low</option>
            </select>
          </label>
          <label className="block">
            <span className="text-body-sm text-on-surface">Attempts</span>
            <input type="number" min={0} value={maxAttempts} onChange={(event) => onMaxAttemptsChange(Number(event.target.value))} className={fieldClass} />
          </label>
        </div>
        <label className="block">
          <span className="text-body-sm text-on-surface">Idempotency key</span>
          <input value={idempotencyKey} onChange={(event) => onIdempotencyKeyChange(event.target.value)} className={monoFieldClass} />
        </label>
      </section>
      <div className="h-px bg-border-subtle" />
      <section className="space-y-4">
        <h3 className="font-headline-md text-headline-md text-on-surface">Schedule</h3>
        <label className="block">
          <span className="text-body-sm text-on-surface">Cron expression</span>
          <input value={cronExpression} onChange={(event) => onCronExpressionChange(event.target.value)} className={monoFieldClass} placeholder="Manual trigger" />
        </label>
        <label className="block">
          <span className="text-body-sm text-on-surface">Timezone</span>
          <input value={timezone} onChange={(event) => onTimezoneChange(event.target.value)} className={fieldClass} placeholder="UTC" />
        </label>
      </section>
    </div>
  );
}
