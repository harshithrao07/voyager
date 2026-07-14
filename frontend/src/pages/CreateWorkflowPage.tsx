import {
  CreateWorkflowView,
  type WorkflowRevisionEditContext,
} from '../components/CreateWorkflowView';
import type {
  WorkflowAiConversationSummaryDTO,
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
  routeChatId?: string;
  onChatStarted?: (chat: WorkflowAiConversationSummaryDTO) => void;
  onChatUpdated?: (previousId: string | null, chat: WorkflowAiConversationSummaryDTO) => void;
  revisionEdit?: WorkflowRevisionEditContext;
};

export function CreateWorkflowPage({
  onWorkflowCreated,
  onWorkflowRevisionSaved,
  onUnsavedChangesChange,
  onNavigate,
  routeChatId,
  onChatStarted,
  onChatUpdated,
  revisionEdit,
}: Props) {
  return (
    <CreateWorkflowView
      onWorkflowCreated={onWorkflowCreated}
      onWorkflowRevisionSaved={onWorkflowRevisionSaved}
      onUnsavedChangesChange={onUnsavedChangesChange}
      onNavigate={onNavigate}
      routeChatId={routeChatId}
      onChatStarted={onChatStarted}
      onChatUpdated={onChatUpdated}
      revisionEdit={revisionEdit}
    />
  );
}
