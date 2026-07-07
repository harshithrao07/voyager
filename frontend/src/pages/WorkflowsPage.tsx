import { WorkflowListView, type WorkflowSummary } from '../components/WorkflowListView';

type Props = {
  workflows: WorkflowSummary[];
  totalWorkflows?: number;
  onSelect: (workflow: WorkflowSummary) => void;
};

function WorkflowMetric({ label, value, tone }: { label: string; value: string | number; tone: string }) {
  return (
    <div className="border-b border-border-subtle px-4 py-3 md:border-b-0 md:border-r">
      <div className="text-label-caps font-label-caps text-on-surface-variant">{label}</div>
      <div className={`mt-1 font-mono-sm text-[14px] font-semibold ${tone}`}>{value}</div>
    </div>
  );
}

export function WorkflowsPage({ workflows, totalWorkflows, onSelect }: Props) {
  const activeCount = workflows.filter((workflow) => workflow.status === 'Active').length;
  const pausedCount = workflows.filter((workflow) => workflow.status === 'Paused').length;
  const archivedCount = workflows.filter((workflow) => workflow.status === 'Archived').length;

  return (
    <div className="flex h-full min-h-0 flex-col bg-surface-lowest">
      <div className="glass-shell grid shrink-0 grid-cols-1 border-b border-border-subtle bg-surface-base md:grid-cols-4">
        <WorkflowMetric label="Total workflows" value={totalWorkflows ?? workflows.length} tone="text-primary" />
        <WorkflowMetric label="Active workflows" value={activeCount} tone="text-status-success" />
        <WorkflowMetric label="Paused workflows" value={pausedCount} tone="text-on-surface-variant" />
        <WorkflowMetric label="Archived workflows" value={archivedCount} tone="text-status-info" />
      </div>
      <div className="min-h-0 flex-1">
        <WorkflowListView workflows={workflows} onSelect={onSelect} />
      </div>
    </div>
  );
}
