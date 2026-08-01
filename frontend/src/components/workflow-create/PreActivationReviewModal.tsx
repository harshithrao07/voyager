import { AlertTriangle, CheckCircle2, ShieldAlert, X } from 'lucide-react';
import type {
  WorkflowPreActivationWarning,
  WorkflowPreActivationWarningCategory,
} from '../../api';

type Props = {
  warnings: WorkflowPreActivationWarning[];
  onClose: () => void;
};

const CATEGORY_LABELS: Record<WorkflowPreActivationWarningCategory, string> = {
  DESTRUCTIVE_MCP: 'Destructive MCP action',
  DATA_EXPOSURE: 'Data exposure',
  ERROR_HANDLING: 'Error handling',
  OTHER: 'Operational risk',
  REVIEW_UNAVAILABLE: 'Review unavailable',
};

/** Read-only AI review: it reports observations but never proposes or applies a fix. */
export function PreActivationReviewModal({
  warnings,
  onClose,
}: Props) {
  const clean = warnings.length === 0;
  const unavailable = warnings.some((warning) => warning.category === 'REVIEW_UNAVAILABLE');

  return (
    <div
      data-testid="pre-activation-review"
      className="fixed inset-0 z-[115] flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-labelledby="pre-activation-review-title"
      onClick={onClose}
    >
      <div
        className={`flex max-h-full w-full max-w-[580px] flex-col overflow-hidden rounded-xl border bg-surface-container-lowest shadow-[0_30px_80px_rgba(0,0,0,0.5)] ${clean ? 'border-status-success/40' : 'border-status-warning/40'}`}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex shrink-0 items-start gap-3 border-b border-border-subtle p-5 pb-4">
          <div className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border ${clean ? 'border-status-success/40 bg-status-success/10 text-status-success' : 'border-status-warning/40 bg-status-warning/10 text-status-warning'}`}>
            {clean ? <CheckCircle2 size={18} /> : <ShieldAlert size={18} />}
          </div>
          <div className="min-w-0 flex-1">
            <h3 id="pre-activation-review-title" className="text-[15px] font-semibold text-on-surface">
              AI activation review
            </h3>
            <p className="mt-1 text-[12px] leading-5 text-on-surface-variant">
              {clean
                ? 'No clear activation risks were identified in this definition.'
                : unavailable
                  ? 'The AI review could not be completed.'
                  : 'The AI identified potential risks. The workflow was not changed.'}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="shrink-0 rounded-md p-1 text-on-surface-variant transition-colors hover:text-on-surface"
            aria-label="Close"
          >
            <X size={16} />
          </button>
        </div>

        <div className="min-h-0 flex-1 space-y-2 overflow-y-auto p-5">
          {clean && (
            <div className="relative overflow-hidden rounded-lg border border-status-success/25 bg-status-success/5 px-4 py-3 pl-5">
              <span className="absolute inset-y-0 left-0 w-1 bg-status-success/80" aria-hidden="true" />
              <div className="flex items-start gap-2.5">
                <CheckCircle2 size={14} className="mt-0.5 shrink-0 text-status-success" />
                <div>
                  <div className="text-[12px] font-semibold text-on-surface">Review complete</div>
                  <p className="mt-1 text-[12px] leading-5 text-on-surface-variant">
                    The default Chat model returned no activation warnings for this definition.
                  </p>
                </div>
              </div>
            </div>
          )}
          {warnings.map((warning, index) => (
            <div
              key={`${warning.category}-${warning.stateName || 'workflow'}-${index}`}
              className="relative overflow-hidden rounded-lg border border-border-subtle bg-surface-container-low/60 px-4 py-3 pl-5"
            >
              <span className="absolute inset-y-0 left-0 w-1 bg-status-warning/80" aria-hidden="true" />
              <div className="flex items-start gap-2.5">
                <AlertTriangle size={14} className="mt-0.5 shrink-0 text-status-warning" />
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-mono-sm text-[10px] uppercase tracking-[0.08em] text-status-warning">
                      {CATEGORY_LABELS[warning.category] || CATEGORY_LABELS.OTHER}
                    </span>
                    {warning.stateName && (
                      <span className="rounded border border-border-subtle px-1.5 py-0.5 font-mono-sm text-[10px] text-on-surface-variant">
                        {warning.stateName}
                      </span>
                    )}
                  </div>
                  <div className="mt-1 text-[12px] font-semibold text-on-surface">{warning.title}</div>
                  <p className="mt-1 text-[12px] leading-5 text-on-surface-variant">{warning.detail}</p>
                </div>
              </div>
            </div>
          ))}
        </div>

        <div className="flex shrink-0 justify-end border-t border-border-subtle px-5 py-4">
          <button
            type="button"
            data-testid="pre-activation-review-done"
            onClick={onClose}
            className="flex h-9 items-center rounded-lg border border-border-subtle bg-surface-container-low px-4 text-[12px] font-semibold text-on-surface transition-colors hover:border-secondary/55 hover:text-secondary"
          >
            Done
          </button>
        </div>
      </div>
    </div>
  );
}
