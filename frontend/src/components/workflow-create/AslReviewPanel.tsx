import Editor from '@monaco-editor/react';
import { Braces, Loader2, Save, X } from 'lucide-react';
import type { WorkflowAiStage } from '../../api';
import type { DefinitionStatus, WorkflowPreview } from './types';
import { WorkflowPreviewPanel } from './WorkflowPreviewPanel';
import { WorkflowStageStrip } from './WorkflowStageStrip';

type Props = {
  conversationStage: WorkflowAiStage;
  conversationId: string | null;
  generating: boolean;
  accepting: boolean;
  definitionStatus: DefinitionStatus;
  workflowPreview: WorkflowPreview;
  definitionText: string;
  onDefinitionTextChange: (value: string) => void;
  onReviewAsl: () => void;
  onAcceptPlan: () => void;
  onClose: () => void;
};

export function AslReviewPanel({
  conversationStage,
  conversationId,
  generating,
  accepting,
  definitionStatus,
  workflowPreview,
  definitionText,
  onDefinitionTextChange,
  onReviewAsl,
  onAcceptPlan,
  onClose,
}: Props) {
  return (
    <div className="relative flex w-[400px] shrink-0 flex-col border-l border-border-subtle bg-surface-base xl:w-[500px]">
      <div className="flex h-12 items-center justify-between border-b border-border-subtle px-4">
        <div className="flex items-center gap-2 font-mono-sm text-[12px] text-on-surface-variant">
          <Braces size={14} />
          {conversationStage === 'ASL_UNDER_REVIEW' ? 'ASL review' : 'Generated workflow'}
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={onReviewAsl}
            disabled={!conversationId || generating || !definitionStatus.valid}
            className="flex h-8 items-center gap-1.5 rounded-DEFAULT border border-secondary/30 px-2.5 font-body-sm text-body-sm text-secondary transition-colors hover:bg-secondary-container/25 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {generating ? <Loader2 className="animate-spin" size={14} /> : <Braces size={14} />}
            Check ASL
          </button>
          {conversationStage === 'PLAN_READY' && (
            <button
              type="button"
              onClick={onAcceptPlan}
              disabled={accepting}
              className="flex h-8 items-center gap-1.5 rounded-DEFAULT bg-primary px-2.5 font-body-sm text-body-sm font-medium text-on-primary transition-colors hover:bg-primary-fixed-dim disabled:cursor-not-allowed disabled:opacity-50"
            >
              {accepting ? <Loader2 className="animate-spin" size={14} /> : <Save size={14} />}
              Accept workflow
            </button>
          )}
          <button
            type="button"
            onClick={onClose}
            className="rounded-DEFAULT p-1 text-on-surface-variant transition-colors hover:bg-surface-container hover:text-on-surface"
            aria-label="Close ASL editor"
          >
            <X size={16} />
          </button>
        </div>
      </div>
      <WorkflowStageStrip
        stage={conversationStage}
        definitionStatus={definitionStatus}
        generating={generating}
        compact
      />
      <WorkflowPreviewPanel
        preview={workflowPreview}
      />
      <div className="relative flex-1 overflow-hidden">
        <Editor
          height="100%"
          defaultLanguage="json"
          theme="vs-dark"
          value={definitionText}
          onChange={(value) => onDefinitionTextChange(value || '')}
          options={{
            minimap: { enabled: false },
            scrollBeyondLastLine: false,
            fontSize: 13,
            fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
            wordWrap: 'on',
            tabSize: 2,
            lineNumbersMinChars: 3,
            padding: { top: 16, bottom: 16 },
          }}
        />
      </div>
    </div>
  );
}
