import { useState } from 'react';
import { Loader2, ShieldAlert, X } from 'lucide-react';
import type { ElevatedMcpTool } from '../../api';

type Props = {
  tools: ElevatedMcpTool[];
  busy?: boolean;
  onConfirm: () => void | Promise<void>;
  onCancel: () => void;
};

/**
 * Shown when saving a workflow that wires in WRITE/DESTRUCTIVE MCP tools. The user must explicitly
 * acknowledge the mutating calls before the workflow is created/activated — the backend enforces the
 * same gate, so this is a required confirmation, not a courtesy warning.
 */
export function TrustConfirmationModal({ tools, busy = false, onConfirm, onCancel }: Props) {
  const [acknowledged, setAcknowledged] = useState(false);

  return (
    <div
      data-testid="mcp-trust-confirm"
      className="fixed inset-0 z-[110] flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      onClick={busy ? undefined : onCancel}
    >
      <div
        className="flex max-h-full w-full max-w-[520px] flex-col overflow-hidden rounded-xl border border-status-warning/40 bg-surface-container-lowest shadow-[0_30px_80px_rgba(0,0,0,0.5)]"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex shrink-0 items-start gap-3 border-b border-border-subtle p-5 pb-4">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-status-warning/40 bg-status-warning/10 text-status-warning">
            <ShieldAlert size={18} />
          </div>
          <div className="min-w-0 flex-1">
            <h3 className="text-[15px] font-semibold text-on-surface">Confirm elevated-trust tools</h3>
            <p className="mt-1 text-[12px] leading-5 text-on-surface-variant">
              This workflow calls MCP tools that can <span className="font-semibold text-status-warning">write or delete</span> data
              on connected systems. Review them before it is created.
            </p>
          </div>
          <button
            type="button"
            onClick={onCancel}
            disabled={busy}
            className="shrink-0 rounded-md p-1 text-on-surface-variant transition-colors hover:text-on-surface disabled:opacity-50"
            aria-label="Close"
          >
            <X size={16} />
          </button>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto p-5">
          <div className="flex flex-col gap-2">
            {tools.map((tool, index) => (
              <div
                key={`${tool.stateName}-${index}`}
                className="flex items-start gap-2 rounded-lg border border-border-subtle bg-surface-container-low/50 px-3 py-2"
              >
                <ShieldAlert size={13} className="mt-0.5 shrink-0 text-status-warning" />
                <div className="min-w-0">
                  <div className="font-mono-sm text-[12px] text-on-surface">{tool.detail}</div>
                  {tool.stateName && (
                    <div className="mt-0.5 font-mono-sm text-[10px] text-on-surface-variant/75">
                      state: {tool.stateName}
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>

          <label className="mt-4 flex items-start gap-2.5">
            <input
              data-testid="mcp-trust-ack"
              type="checkbox"
              checked={acknowledged}
              onChange={(event) => setAcknowledged(event.target.checked)}
              disabled={busy}
              className="mt-0.5 h-4 w-4 rounded border-border-subtle bg-surface-container-lowest text-status-warning focus:ring-status-warning/40"
            />
            <span className="text-[12px] leading-5 text-on-surface">
              I understand this workflow can modify or delete data on the connected MCP servers.
            </span>
          </label>
        </div>

        <div className="flex shrink-0 justify-end gap-2 border-t border-border-subtle px-5 py-4">
          <button
            type="button"
            onClick={onCancel}
            disabled={busy}
            className="flex h-9 items-center rounded-lg border border-border-subtle px-4 text-[12px] text-on-surface-variant transition-colors hover:text-on-surface disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            data-testid="mcp-trust-confirm-save"
            onClick={() => onConfirm()}
            disabled={busy || !acknowledged}
            className="flex h-9 items-center gap-2 rounded-lg border border-status-warning bg-status-warning px-4 text-[12px] font-semibold text-on-primary transition-colors hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {busy && <Loader2 size={14} className="animate-spin" />}
            Confirm &amp; create
          </button>
        </div>
      </div>
    </div>
  );
}
