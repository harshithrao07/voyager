import { CheckCircle2, Circle, Loader2 } from 'lucide-react';
import type { WorkflowAiStage } from '../../api';
import type { DefinitionStatus } from './types';

const stageItems: Array<{
  id: WorkflowAiStage;
  label: string;
}> = [
  { id: 'COLLECTING_WORKFLOW_DETAILS', label: 'Details' },
  { id: 'ASL_READY', label: 'ASL ready' },
  { id: 'ASL_UNDER_REVIEW', label: 'Reviewing' },
  { id: 'COLLECTING_SCHEDULE_DETAILS', label: 'Schedule' },
  { id: 'PLAN_READY', label: 'Ready' },
  { id: 'ACCEPTED', label: 'Accepted' },
];

function stageIndex(stage: WorkflowAiStage) {
  return Math.max(0, stageItems.findIndex((item) => item.id === stage));
}

export function WorkflowStageStrip({
  stage,
  definitionStatus,
  generating,
  compact = false,
}: {
  stage: WorkflowAiStage;
  definitionStatus: DefinitionStatus;
  generating: boolean;
  compact?: boolean;
}) {
  const activeIndex = stageIndex(stage);

  return (
    <div className={`border-b border-border-subtle bg-surface-base/95 ${compact ? 'px-4 py-3' : 'px-6 py-4'}`}>
      <div className="flex items-center justify-between gap-4">
        <div className="min-w-0">
          <div className="font-mono-sm text-[11px] uppercase text-on-surface-variant">Generation stage</div>
          <div className="mt-1 truncate font-headline-md text-headline-md text-on-surface">
            {stageItems[activeIndex]?.label || 'Details'}
          </div>
        </div>
        <div className={`shrink-0 rounded-DEFAULT border px-2.5 py-1 font-mono-sm text-[11px] ${
          definitionStatus.valid
            ? 'border-secondary/35 bg-secondary-container/25 text-secondary-fixed'
            : 'border-status-error/35 bg-status-error/10 text-status-error'
        }`}
        >
          {definitionStatus.valid ? 'Valid ASL' : 'Needs fix'}
        </div>
      </div>

      <div className={`mt-4 grid gap-2 ${compact ? 'grid-cols-3' : 'grid-cols-6'}`}>
        {stageItems.map((item, index) => {
          const complete = index < activeIndex || stage === 'ACCEPTED';
          const active = index === activeIndex && stage !== 'ACCEPTED';
          return (
            <div key={item.id} className="min-w-0">
              <div className={`h-1 rounded-full ${
                complete ? 'bg-secondary' : active ? 'bg-primary' : 'bg-border-muted'
              }`}
              />
              <div className={`mt-2 flex min-w-0 items-center gap-1.5 font-mono-sm text-[11px] ${
                complete ? 'text-secondary' : active ? 'text-primary' : 'text-on-surface-variant'
              }`}
              >
                {complete ? (
                  <CheckCircle2 size={12} className="shrink-0" />
                ) : active && generating ? (
                  <Loader2 size={12} className="shrink-0 animate-spin" />
                ) : (
                  <Circle size={12} className="shrink-0" />
                )}
                <span className="truncate">{item.label}</span>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
