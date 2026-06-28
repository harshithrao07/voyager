import { CreateWorkflowView } from '../components/CreateWorkflowView';
import type { WorkflowResponseDTO } from '../api';

type Props = {
  onWorkflowCreated: (workflow: WorkflowResponseDTO) => void;
  onNavigate: (path: string) => void;
};

export function CreateWorkflowPage({ onWorkflowCreated, onNavigate }: Props) {
  return <CreateWorkflowView onWorkflowCreated={onWorkflowCreated} onNavigate={onNavigate} />;
}
