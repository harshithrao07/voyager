import { WorkflowListView, type WorkflowSummary } from '../components/WorkflowListView';

type Props = {
  workflows: WorkflowSummary[];
  onSelect: (workflow: WorkflowSummary) => void;
};

export function WorkflowsPage({ workflows, onSelect }: Props) {
  return <WorkflowListView workflows={workflows} onSelect={onSelect} />;
}
