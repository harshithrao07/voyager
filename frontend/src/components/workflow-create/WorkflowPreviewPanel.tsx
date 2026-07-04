import { AlertTriangle, Braces, CheckCircle2, GitBranch, Route, Timer } from 'lucide-react';
import type { ReactNode } from 'react';
import type { DefinitionStatus, WorkflowPreview } from './types';

export function WorkflowPreviewPanel({
  preview,
  definitionStatus,
  className = '',
}: {
  preview: WorkflowPreview;
  definitionStatus: DefinitionStatus;
  className?: string;
}) {
  const statusClass = definitionStatus.valid
    ? 'border-secondary/35 bg-secondary-container/25 text-secondary-fixed'
    : 'border-status-error/35 bg-status-error/10 text-status-error';

  return (
    <section className={`border-b border-border-subtle bg-surface-base px-4 py-4 ${className}`}>
      <div className="flex items-start justify-between gap-4">
        <div>
          <div className="font-mono-sm text-[11px] uppercase text-on-surface-variant">Workflow preview</div>
          <div className="mt-1 max-w-[260px] truncate font-headline-md text-headline-md text-on-surface">
            {preview.startAt !== '-' ? preview.startAt : 'No start state'}
          </div>
        </div>
        <div className={`flex h-8 shrink-0 items-center gap-1.5 rounded-DEFAULT border px-2.5 font-mono-sm text-[11px] ${statusClass}`}>
          {definitionStatus.valid ? <CheckCircle2 size={13} /> : <AlertTriangle size={13} />}
          {definitionStatus.valid ? 'Ready' : 'Fix JSON'}
        </div>
      </div>

      <div className="mt-4 grid grid-cols-3 gap-x-4 gap-y-3">
        <PreviewMetric icon={<Route size={14} />} label="States" value={preview.stateCount} />
        <PreviewMetric icon={<Braces size={14} />} label="Tasks" value={preview.taskCount} />
        <PreviewMetric icon={<GitBranch size={14} />} label="Choices" value={preview.choiceCount} />
        <PreviewMetric icon={<Timer size={14} />} label="Waits" value={preview.waitCount} />
        <PreviewMetric label="Pass" value={preview.passCount} />
        <PreviewMetric label="Ends" value={preview.terminalCount} />
      </div>

      <div className="mt-4 flex min-h-7 flex-wrap gap-2">
        {preview.stateTypes.length > 0 ? preview.stateTypes.map((type) => (
          <span
            key={type}
            className="rounded-DEFAULT border border-border-subtle bg-surface-container-low px-2 py-1 font-mono-sm text-[11px] text-on-surface-variant"
          >
            {type}
          </span>
        )) : (
          <span className="font-mono-sm text-[11px] text-on-surface-variant">No states parsed</span>
        )}
      </div>
    </section>
  );
}

function PreviewMetric({
  icon,
  label,
  value,
}: {
  icon?: ReactNode;
  label: string;
  value: number;
}) {
  return (
    <div className="min-w-0 border-t border-border-subtle pt-2">
      <div className="flex items-center gap-1.5 font-mono-sm text-[11px] text-on-surface-variant">
        {icon}
        <span className="truncate">{label}</span>
      </div>
      <div className="mt-1 font-display text-[16px] font-medium leading-none text-on-surface">{value}</div>
    </div>
  );
}
