import { PanelRightOpen } from 'lucide-react';
import { AslCodeViewer } from '../components/AslCodeViewer';
import { AslGraphViewer } from '../components/AslGraphViewer';
import { StateDetailsPanel } from '../components/StateDetailsPanel';
import { RevisionHistoryPanel } from '../components/RevisionHistoryPanel';
import { ExecutionStatusView } from '../components/ExecutionStatusView';
import { WorkspaceState } from '../components/WorkspaceState';
import type { WorkflowResponseDTO } from '../api';
import type { CanvasNodePositions } from '../types/workflowCanvas';

export type WorkflowRevision = {
  id: string;
  label: string;
  timestamp: string;
  active?: boolean;
  note: string;
  definition: any;
  canvasLayout: CanvasNodePositions;
};

type Props = {
  workflowDetail: WorkflowResponseDTO | null;
  workflowRevisions: WorkflowRevision[];
  workflowDetailLoading: boolean;
  workflowDetailError: string | null;
  activeTab: 'visualizer' | 'definition' | 'executions';
  selectedRevision: WorkflowRevision | null;
  currentDefinition: any;
  selectedStateName: string;
  detailsPanelOpen: boolean;
  revisionPanelOpen: boolean;
  onRetry: () => void;
  onStateSelect: (stateName: string) => void;
  onOpenDetails: () => void;
  onCloseDetails: () => void;
  onRevisionSelected: (revisionId: string) => void;
  onCloseRevisionPanel: () => void;
  onCanvasLayoutChange: (positions: CanvasNodePositions) => void;
  onNavigate?: (path: string) => void;
};

export function WorkflowDetailPage({
  workflowDetail,
  workflowRevisions,
  workflowDetailLoading,
  workflowDetailError,
  activeTab,
  selectedRevision,
  currentDefinition,
  selectedStateName,
  detailsPanelOpen,
  revisionPanelOpen,
  onRetry,
  onStateSelect,
  onOpenDetails,
  onCloseDetails,
  onRevisionSelected,
  onCloseRevisionPanel,
  onCanvasLayoutChange,
  onNavigate,
}: Props) {
  return (
    <div className="flex-1 flex overflow-hidden">
      <div className="flex-1 relative bg-surface-lowest overflow-hidden">
        {workflowDetailLoading ? (
          <WorkspaceState title="Loading workflow" message="Fetching workflow detail and revision history." />
        ) : workflowDetailError ? (
          <WorkspaceState
            title="Could not load workflow"
            message={workflowDetailError}
            action={{ label: 'Retry', onClick: onRetry }}
          />
        ) : activeTab === 'executions' && workflowDetail ? (
          <ExecutionStatusView
            key={workflowDetail.id}
            workflow={workflowDetail}
            selectedRevisionNumber={selectedRevision ? Number(selectedRevision.id) : null}
            onNavigate={onNavigate}
          />
        ) : activeTab === 'visualizer' ? (
          <AslGraphViewer
            definition={currentDefinition}
            selectedStateName={selectedStateName}
            onStateSelect={onStateSelect}
            preserveNodePositions
            initialNodePositions={selectedRevision?.canvasLayout}
            onNodePositionsChange={onCanvasLayoutChange}
          />
        ) : (
          <AslCodeViewer definition={currentDefinition} />
        )}
        {activeTab === 'visualizer' && !detailsPanelOpen && !revisionPanelOpen && (
          <button
            type="button"
            onClick={onOpenDetails}
            className="absolute right-4 top-1/2 z-40 hidden h-10 w-10 -translate-y-1/2 items-center justify-center rounded-DEFAULT border border-border-subtle bg-surface-container-highest/90 text-on-surface-variant shadow-lg backdrop-blur-xl transition-colors hover:border-border-muted hover:bg-surface-container hover:text-primary lg:flex"
            aria-label="Open state details"
            title="Open state details"
          >
            <PanelRightOpen size={18} />
          </button>
        )}
      </div>

      {(revisionPanelOpen || (activeTab === 'visualizer' && detailsPanelOpen)) && (
        <div
          id={revisionPanelOpen ? 'revision-history-panel' : undefined}
          className="glass-panel z-20 hidden w-[360px] flex-col border-l border-border-subtle bg-surface-base shadow-[-8px_0_24px_rgba(0,0,0,0.2)] lg:flex"
        >
          {revisionPanelOpen ? (
            workflowDetailLoading ? (
              <WorkspaceState title="Loading revisions" message="Fetching /revisions for this workflow." />
            ) : workflowRevisions.length === 0 ? (
              <WorkspaceState title="No revisions" message="This workflow does not have revision records yet." />
            ) : (
              <RevisionHistoryPanel
                revisions={workflowRevisions}
                selectedRevisionId={selectedRevision?.id || ''}
                onRevisionSelected={onRevisionSelected}
                onClose={onCloseRevisionPanel}
              />
            )
          ) : (
            <StateDetailsPanel
              definition={currentDefinition}
              selectedStateName={selectedStateName}
              onClose={onCloseDetails}
            />
          )}
        </div>
      )}
    </div>
  );
}
