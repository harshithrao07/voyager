import { Filter } from 'lucide-react';

export type WorkflowSummary = {
  id: string;
  name: string;
  status: 'Active' | 'Paused' | 'Draft' | 'Archived' | 'Failed';
  schedule: string;
  nextRun: string;
  description: string;
};

type Props = {
  workflows: WorkflowSummary[];
  onSelect: (workflow: WorkflowSummary) => void;
};

function statusClass(status: WorkflowSummary['status']) {
  if (status === 'Failed') return 'bg-status-error/10 text-status-error border-status-error/20';
  if (status === 'Paused') return 'bg-surface-bright text-on-surface-variant border-border-muted';
  if (status === 'Draft') return 'bg-status-info/10 text-status-info border-status-info/20';
  if (status === 'Archived') return 'bg-surface-container text-on-surface-variant border-border-muted';
  return 'bg-status-success/10 text-status-success border-status-success/20';
}

export function WorkflowListView({ workflows, onSelect }: Props) {
  return (
    <div className="h-full min-h-0 bg-surface-base p-6">
      <section className="h-full rounded-lg border border-border-subtle bg-surface-container-lowest flex flex-col overflow-hidden">
        <div className="p-4 border-b border-border-muted bg-surface/50 backdrop-blur-sm flex items-center justify-between">
          <h2 className="font-display text-[14px] font-semibold leading-5 text-primary">Active Workflows</h2>
          <button className="text-on-surface-variant hover:text-primary transition-colors" aria-label="Filter workflows">
            <Filter size={18} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-3 flex flex-col gap-2">
          {workflows.length === 0 ? (
            <div className="glass-card flex min-h-40 items-center justify-center rounded-DEFAULT border border-border-subtle bg-surface-lowest/40 p-6 text-center text-body-sm text-on-surface-variant">
              No workflows found.
            </div>
          ) : workflows.map((workflow) => (
            <button
              key={workflow.id}
              onClick={() => onSelect(workflow)}
              className="w-full p-4 rounded-md hover:bg-surface-container border border-border-subtle bg-surface-lowest/40 hover:border-border-muted transition-all relative overflow-hidden group text-left"
            >
              <div className="flex items-center justify-between gap-6">
                <div className="min-w-0">
                  <div className="flex items-center gap-3 mb-1.5">
                    <span className="font-display text-[13px] font-semibold leading-5 text-on-surface group-hover:text-primary transition-colors truncate">
                    {workflow.name}
                    </span>
                    <span className={`px-1.5 py-0.5 rounded text-[10px] uppercase font-bold tracking-wider shrink-0 border ${statusClass(workflow.status)}`}>
                      {workflow.status}
                    </span>
                  </div>
                  <p className="text-body-sm text-on-surface-variant truncate">{workflow.description}</p>
                </div>
                <div className={`flex shrink-0 items-center gap-5 font-mono-sm text-[11px] ${workflow.status === 'Failed' ? 'text-status-error/80' : 'text-on-surface-variant'}`}>
                  <div className="flex items-center gap-1.5">
                    <span className="material-symbols-outlined text-[12px]">{workflow.schedule === 'Trigger-based' ? 'event' : 'schedule'}</span>
                    <span>{workflow.schedule}</span>
                  </div>
                  <span>{workflow.nextRun}</span>
                </div>
              </div>
            </button>
          ))}
        </div>
      </section>
    </div>
  );
}
