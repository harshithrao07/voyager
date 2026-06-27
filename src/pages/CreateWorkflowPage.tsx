import { CreateWorkflowView } from '../components/CreateWorkflowView';
import type { WorkflowResponseDTO } from '../api';

type Props = {
  onWorkflowCreated: (workflow: WorkflowResponseDTO) => void;
};

export function CreateWorkflowPage({ onWorkflowCreated }: Props) {
  return <CreateWorkflowView onWorkflowCreated={onWorkflowCreated} />;
}
