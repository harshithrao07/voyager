import {
  CreateWorkflowView,
  type WorkflowRevisionEditContext,
} from '../components/CreateWorkflowView';
import type {
  WorkflowDefinitionResponseDTO,
  WorkflowResponseDTO,
} from '../api';

type Props = {
  onWorkflowCreated: (workflow: WorkflowResponseDTO) => void;
  onWorkflowRevisionSaved?: (
    workflowId: string,
    revision: WorkflowDefinitionResponseDTO,
  ) => void | Promise<void>;
  onUnsavedChangesChange?: (dirty: boolean) => void;
  onNavigate: (path: string, options?: { replace?: boolean }) => void;
  onConversationUpdated?: () => void;
  routeChatId?: string;
  revisionEdit?: WorkflowRevisionEditContext;
};

export function CreateWorkflowPage({
  onWorkflowCreated,
  onWorkflowRevisionSaved,
  onUnsavedChangesChange,
  onNavigate,
  onConversationUpdated,
  routeChatId,
  revisionEdit,
}: Props) {
  return (
    <CreateWorkflowView
      onWorkflowCreated={onWorkflowCreated}
      onWorkflowRevisionSaved={onWorkflowRevisionSaved}
      onUnsavedChangesChange={onUnsavedChangesChange}
      onNavigate={onNavigate}
      onConversationUpdated={onConversationUpdated}
      routeChatId={routeChatId}
      revisionEdit={revisionEdit}
    />
  );
}
