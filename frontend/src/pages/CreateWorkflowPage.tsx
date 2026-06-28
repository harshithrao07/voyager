import { CreateWorkflowView } from '../components/CreateWorkflowView';
import type { WorkflowAiConversationSummaryDTO, WorkflowResponseDTO } from '../api';

type Props = {
  onWorkflowCreated: (workflow: WorkflowResponseDTO) => void;
  onNavigate: (path: string, options?: { replace?: boolean }) => void;
  routeChatId?: string;
  onChatStarted?: (chat: WorkflowAiConversationSummaryDTO) => void;
  onChatUpdated?: (previousId: string | null, chat: WorkflowAiConversationSummaryDTO) => void;
};

export function CreateWorkflowPage({
  onWorkflowCreated,
  onNavigate,
  routeChatId,
  onChatStarted,
  onChatUpdated,
}: Props) {
  return (
    <CreateWorkflowView
      onWorkflowCreated={onWorkflowCreated}
      onNavigate={onNavigate}
      routeChatId={routeChatId}
      onChatStarted={onChatStarted}
      onChatUpdated={onChatUpdated}
    />
  );
}
