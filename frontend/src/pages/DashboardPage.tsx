import type { WorkflowSummary } from '../components/WorkflowListView';

type Props = {
  workflows: WorkflowSummary[];
  totalWorkflows?: number;
  onSelect: (workflow: WorkflowSummary) => void;
};

function workflowStatusBadgeClass(status: WorkflowSummary['status']) {
  if (status === 'Failed') return 'border-status-error/20 bg-status-error/10 text-status-error';
  if (status === 'Paused') return 'border-border-muted bg-surface-container text-on-surface-variant';
  if (status === 'Draft') return 'border-status-info/20 bg-status-info/10 text-status-info';
  if (status === 'Archived') return 'border-border-muted bg-surface-container-low text-on-surface-variant';
  return 'border-status-success/20 bg-status-success/10 text-status-success';
}

function DashboardMetric({ label, value, tone }: { label: string; value: string | number; tone: string }) {
  return (
    <div className="border-b border-border-subtle px-4 py-3 md:border-b-0 md:border-r">
      <div className="text-label-caps font-label-caps text-on-surface-variant">{label}</div>
      <div className={`mt-1 font-mono-sm text-[14px] font-semibold ${tone}`}>{value}</div>
    </div>
  );
}

export function DashboardPage({ workflows, totalWorkflows, onSelect }: Props) {
  const activeCount = workflows.filter((workflow) => workflow.status === 'Active').length;
  const pausedCount = workflows.filter((workflow) => workflow.status === 'Paused').length;
  const archivedCount = workflows.filter((workflow) => workflow.status === 'Archived').length;

  return (
    <div className="flex h-full min-h-0 flex-col bg-surface-lowest">
      <div className="glass-shell grid shrink-0 grid-cols-1 border-b border-border-subtle bg-surface-base md:grid-cols-4">
        <DashboardMetric label="Total workflows" value={totalWorkflows ?? workflows.length} tone="text-primary" />
        <DashboardMetric label="Active workflows" value={activeCount} tone="text-status-success" />
        <DashboardMetric label="Paused workflows" value={pausedCount} tone="text-on-surface-variant" />
        <DashboardMetric label="Archived workflows" value={archivedCount} tone="text-status-info" />
      </div>

      <div className="flex-1 overflow-hidden p-4">
        <section className="glass-panel flex h-full min-h-0 flex-col overflow-hidden border border-border-subtle bg-surface">
          <div className="glass-shell flex h-11 shrink-0 items-center justify-between border-b border-border-subtle bg-surface-elevated px-4">
            <h2 className="font-display text-[14px] font-semibold leading-5 text-primary">Workflow Executions</h2>
            <button className="rounded-DEFAULT border border-border-subtle bg-surface-lowest px-3 py-1.5 font-body-sm text-body-sm text-on-surface-variant transition-colors hover:text-primary">
              Saved view: Open
            </button>
          </div>
          <div className="grid grid-cols-[1fr_120px_150px_130px] border-b border-border-subtle bg-surface-container-lowest px-4 py-2 text-label-caps font-label-caps text-on-surface-variant">
            <div>Workflow</div>
            <div>Status</div>
            <div>Schedule</div>
            <div className="text-right">Next run</div>
          </div>
          <div className="flex-1 overflow-y-auto">
            {workflows.map((workflow) => (
              <button
                key={workflow.id}
                type="button"
                onClick={() => onSelect(workflow)}
                className="grid w-full grid-cols-[1fr_120px_150px_130px] items-center border-b border-border-subtle px-4 py-3 text-left transition-colors hover:bg-surface-container-low"
              >
                <div className="min-w-0">
                  <div className="truncate font-body-sm text-body-sm font-medium text-primary">{workflow.name}</div>
                  <div className="truncate font-mono-sm text-[11px] text-on-surface-variant">{workflow.id}</div>
                </div>
                <div>
                  <span className={`rounded border px-2 py-0.5 font-body-sm text-[11px] ${workflowStatusBadgeClass(workflow.status)}`}>
                    {workflow.status}
                  </span>
                </div>
                <div className="font-mono-sm text-[11px] text-on-surface-variant">{workflow.schedule}</div>
                <div className="text-right font-mono-sm text-[11px] text-on-surface-variant">{workflow.nextRun}</div>
              </button>
            ))}
          </div>
        </section>
      </div>
    </div>
  );
}
