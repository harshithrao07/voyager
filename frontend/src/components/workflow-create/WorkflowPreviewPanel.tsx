import { Braces, GitBranch, Route, Timer } from 'lucide-react';
import type { ReactNode } from 'react';
import { getStateVisual } from '../../utils/stateVisuals';
import type { WorkflowPreview } from './types';

export function WorkflowPreviewPanel({
  preview,
  className = '',
}: {
  preview: WorkflowPreview;
  className?: string;
}) {
  return (
    <section className={`border-b border-border-subtle bg-surface-base px-4 py-4 ${className}`}>
      <div>
        <div className="font-mono-sm text-[11px] uppercase text-on-surface-variant">Workflow preview</div>
        {preview.startAt !== '-' ? (
          <div className="mt-3 flex items-center gap-2.5">
            <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-DEFAULT border border-secondary/35 bg-secondary-container/40 text-secondary-fixed">
              <span className="material-symbols-outlined text-[19px]">play_arrow</span>
            </span>
            <div className="min-w-0">
              <div className="font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant">Start state</div>
              <div className="max-w-[210px] truncate font-headline-md text-headline-md leading-tight text-on-surface">
                {preview.startAt}
              </div>
            </div>
          </div>
        ) : (
          <div className="mt-3 flex items-center gap-2.5">
            <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-DEFAULT border border-status-warning/35 bg-status-warning/10 text-status-warning">
              <span className="material-symbols-outlined text-[19px]">priority_high</span>
            </span>
            <div className="min-w-0">
              <div className="font-mono-sm text-[10px] uppercase tracking-[0.08em] text-status-warning">Start state</div>
              <div className="font-headline-md text-headline-md leading-tight text-on-surface-variant">Not defined</div>
            </div>
          </div>
        )}
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
        {preview.stateTypes.length > 0 ? preview.stateTypes.map((type) => {
          const visual = getStateVisual(type);
          return (
            <span
              key={type}
              className={`flex items-center gap-1 rounded-DEFAULT border px-2 py-1 font-mono-sm text-[11px] ${visual.chipClass}`}
            >
              <span className={`material-symbols-outlined text-[13px] ${visual.textClass}`}>{visual.iconName}</span>
              {type}
            </span>
          );
        }) : (
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
