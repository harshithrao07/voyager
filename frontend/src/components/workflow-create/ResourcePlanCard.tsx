import { useMemo, useState } from 'react';
import { Boxes, Check, CheckCircle2, Code2, Loader2, Plug, ShieldAlert } from 'lucide-react';
import type {
  WorkflowAiMcpRequirement,
  WorkflowAiProposedFunction,
  WorkflowAiResourcePlan,
} from '../../api';

type Props = {
  plan: WorkflowAiResourcePlan;
  /** Current unresolved subset; the message-level plan remains immutable history. */
  activePlan?: WorkflowAiResourcePlan | null;
  busy: boolean;
  resolved?: boolean;
  onApprove: (functions: WorkflowAiProposedFunction[]) => void;
  onContinue: () => void;
  onOpenMcpServers: () => void;
};

function normalizedResourceText(value?: string | null) {
  return (value ?? '').trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
}

function normalizeFunctionName(name: string) {
  return normalizedResourceText(name);
}

function mcpRequirementKey(requirement: WorkflowAiMcpRequirement) {
  return normalizedResourceText(requirement.capability)
    || normalizedResourceText(requirement.suggestedToolName);
}

/**
 * Rendered in the AI chat when the assistant needs resources that don't exist yet. Proposed
 * functions can be reviewed, edited, selected, and created (Voyager publishes + activates them).
 * MCP requirements are recommend-only: the user attaches a server elsewhere, then continues.
 */
export function ResourcePlanCard({
  plan,
  activePlan = null,
  busy,
  resolved = false,
  onApprove,
  onContinue,
  onOpenMcpServers,
}: Props) {
  const functions = useMemo(() => plan.functions ?? [], [plan.functions]);
  const mcpRequirements = plan.mcpRequirements ?? [];
  const pendingFunctionNames = useMemo(() => new Set(
    (activePlan?.functions ?? []).map((fn) => normalizeFunctionName(fn.name)),
  ), [activePlan?.functions]);
  const pendingMcpCapabilities = useMemo(() => new Set(
    (activePlan?.mcpRequirements ?? []).map(mcpRequirementKey),
  ), [activePlan?.mcpRequirements]);

  const functionIsPending = (fn: WorkflowAiProposedFunction) => (
    !resolved && (activePlan === null || pendingFunctionNames.has(normalizeFunctionName(fn.name)))
  );
  const mcpIsPending = (requirement: WorkflowAiMcpRequirement) => (
    !resolved && (activePlan === null || pendingMcpCapabilities.has(mcpRequirementKey(requirement)))
  );

  // Local, editable copies so the user's corrections are what actually gets created.
  const [drafts, setDrafts] = useState<WorkflowAiProposedFunction[]>(() =>
    functions.map((fn) => ({ ...fn })),
  );
  const [selected, setSelected] = useState<Set<number>>(
    () => new Set(functions.map((_, index) => index)),
  );

  const toggle = (index: number) => {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(index)) next.delete(index);
      else next.add(index);
      return next;
    });
  };

  const updateSource = (index: number, sourceCode: string) => {
    setDrafts((current) => current.map((fn, i) => (i === index ? { ...fn, sourceCode } : fn)));
  };

  const approve = () => {
    const chosen = drafts.filter((fn, index) => selected.has(index) && functionIsPending(fn));
    if (chosen.length === 0) return;
    onApprove(chosen);
  };

  const selectedCount = drafts.filter(
    (fn, index) => selected.has(index) && functionIsPending(fn),
  ).length;
  const pendingMcpCount = mcpRequirements.filter(mcpIsPending).length;

  return (
    <div className="rounded-DEFAULT border border-secondary/30 bg-secondary-container/[0.06] p-3">
      <div className="flex items-center gap-2 border-b border-border-subtle pb-2">
        <span className="flex h-6 w-6 items-center justify-center rounded-md border border-secondary/30 bg-surface-container-low text-secondary">
          <Boxes size={13} />
        </span>
        <div className="min-w-0">
          <div className="font-mono-sm text-[11px] font-semibold text-on-surface">
            {resolved ? 'Resources reviewed' : 'Resources needed first'}
          </div>
          <div className="font-mono-sm text-[10px] text-on-surface-variant">
            {resolved
              ? 'This proposal belongs to the assistant message above.'
              : 'Review and create these before the workflow is generated.'}
          </div>
        </div>
      </div>

      {functions.length > 0 && (
        <div className="mt-3 space-y-2">
          <div className="flex items-center gap-1.5 font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant">
            <Code2 size={11} /> Functions to create
          </div>
          {drafts.map((fn, index) => {
            const pending = functionIsPending(fn);
            return (
            <div
              key={`${fn.name}-${index}`}
              className={`rounded-[4px] border px-2.5 py-2 transition-colors ${
                pending && selected.has(index)
                  ? 'border-secondary/40 bg-surface-container-low'
                  : 'border-border-subtle bg-surface-container-low/50'
              }`}
            >
              <label className={`flex items-start gap-2 ${pending ? 'cursor-pointer' : 'cursor-default'}`}>
                {pending ? <input
                  type="checkbox"
                  checked={selected.has(index)}
                  onChange={() => toggle(index)}
                  disabled={busy}
                  className="mt-0.5 accent-secondary"
                /> : <CheckCircle2 className="mt-0.5 shrink-0 text-secondary" size={13} />}
                <span className="min-w-0 flex-1">
                  <span className="flex items-center gap-2">
                    <span className="truncate font-mono-sm text-[12px] text-primary">{fn.name}</span>
                    {fn.languageId != null && (
                      <span className="shrink-0 rounded border border-border-subtle px-1 py-px font-mono-sm text-[9px] text-on-surface-variant">
                        lang {fn.languageId}
                      </span>
                    )}
                    {!pending && (
                      <span className="shrink-0 rounded border border-secondary/30 bg-secondary-container/15 px-1 py-px font-mono-sm text-[9px] uppercase text-secondary">
                        Completed
                      </span>
                    )}
                  </span>
                  {fn.description && (
                    <span className="mt-0.5 block font-body-sm text-[11px] leading-snug text-on-surface-variant">
                      {fn.description}
                    </span>
                  )}
                  {fn.rationale && (
                    <span className="mt-0.5 block font-mono-sm text-[10px] italic text-on-surface-variant/75">
                      {fn.rationale}
                    </span>
                  )}
                </span>
              </label>
              <textarea
                value={fn.sourceCode ?? ''}
                onChange={(event) => updateSource(index, event.target.value)}
                disabled={busy || !pending || !selected.has(index)}
                spellCheck={false}
                rows={Math.min(12, Math.max(4, (fn.sourceCode ?? '').split('\n').length))}
                className="mt-2 w-full resize-y rounded-[3px] border border-border-subtle bg-surface-container-lowest px-2 py-1.5 font-mono-sm text-[11px] leading-5 text-on-surface outline-none focus:border-secondary/50 disabled:opacity-60"
                aria-label={`${fn.name} source code`}
              />
            </div>
            );
          })}
        </div>
      )}

      {mcpRequirements.length > 0 && (
        <div className="mt-3 space-y-2">
          <div className="flex items-center gap-1.5 font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant">
            <Plug size={11} /> MCP servers to attach
          </div>
          {mcpRequirements.map((requirement: WorkflowAiMcpRequirement, index) => {
            const pending = mcpIsPending(requirement);
            return (
            <div key={index} className="rounded-[4px] border border-border-subtle bg-surface-container-low px-2.5 py-2">
              <div className="flex items-center gap-2">
                <span className="min-w-0 flex-1 truncate font-mono-sm text-[12px] text-on-surface">{requirement.capability}</span>
                {!pending && (
                  <span className="flex shrink-0 items-center gap-0.5 rounded border border-secondary/30 bg-secondary-container/15 px-1 py-px font-mono-sm text-[9px] uppercase text-secondary">
                    <CheckCircle2 size={9} /> Completed
                  </span>
                )}
                {requirement.trustLevelHint && (
                  <span className="flex shrink-0 items-center gap-0.5 rounded border border-status-warning/35 bg-status-warning/10 px-1 py-px font-mono-sm text-[9px] uppercase text-status-warning">
                    <ShieldAlert size={9} /> {requirement.trustLevelHint}
                  </span>
                )}
              </div>
              {requirement.reason && (
                <div className="mt-0.5 font-body-sm text-[11px] leading-snug text-on-surface-variant">{requirement.reason}</div>
              )}
              {requirement.suggestedToolName && (
                <div className="mt-0.5 font-mono-sm text-[10px] text-on-surface-variant/75">
                  suggested tool: {requirement.suggestedToolName}
                </div>
              )}
            </div>
            );
          })}
          {!resolved && pendingMcpCount > 0 && <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={onOpenMcpServers}
              disabled={busy}
              className="flex h-7 items-center gap-1.5 rounded-DEFAULT border border-border-subtle px-2.5 font-mono-sm text-[11px] text-on-surface-variant transition-colors hover:border-secondary hover:text-secondary disabled:opacity-50"
            >
              <Plug size={12} /> Open MCP Servers
            </button>
            <button
              type="button"
              onClick={onContinue}
              disabled={busy}
              className="flex h-7 items-center gap-1.5 rounded-DEFAULT border border-secondary/40 bg-secondary-container/20 px-2.5 font-mono-sm text-[11px] text-secondary transition-colors hover:bg-secondary-container/35 disabled:opacity-50"
            >
              {busy ? <Loader2 className="animate-spin" size={12} /> : <Check size={12} />}
              I've attached it — continue
            </button>
          </div>}
        </div>
      )}

      {!resolved && functions.length > 0 && (
        <button
          type="button"
          onClick={approve}
          disabled={busy || selectedCount === 0}
          className="mt-3 flex h-8 w-full items-center justify-center gap-1.5 rounded-DEFAULT bg-primary px-3 font-body-sm text-[12px] font-medium text-on-primary transition-colors hover:bg-primary-fixed-dim disabled:cursor-not-allowed disabled:opacity-50"
        >
          {busy ? <Loader2 className="animate-spin" size={14} /> : <Check size={14} />}
          {selectedCount === 0
            ? 'Select a function to create'
            : `Approve & create ${selectedCount} function${selectedCount === 1 ? '' : 's'}`}
        </button>
      )}

      {resolved && (
        <div className="mt-3 flex items-center gap-1.5 font-mono-sm text-[10px] text-secondary">
          <CheckCircle2 size={12} /> Proposal superseded or completed
        </div>
      )}
    </div>
  );
}
