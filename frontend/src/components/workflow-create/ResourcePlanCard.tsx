import { useEffect, useMemo, useState } from 'react';
import { Boxes, Check, CheckCircle2, Code2, Library, Loader2, Plug, ShieldAlert } from 'lucide-react';
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
  /** Deep-link to the public MCP registry, pre-searched for a capability. */
  onDiscoverMcp?: (capability: string) => void;
};

function normalizedResourceText(value?: string | null) {
  return (value ?? '').trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
}

function normalizeFunctionName(name: string) {
  return normalizedResourceText(name);
}

function hasMcpRequirementDetails(requirement: WorkflowAiMcpRequirement | null | undefined) {
  return Boolean(
    requirement?.capability?.trim()
    || requirement?.suggestedToolName?.trim()
    || requirement?.reason?.trim()
    || requirement?.trustLevelHint?.trim(),
  );
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
  onDiscoverMcp,
}: Props) {
  // A live card renders what is still outstanding, not what was first proposed. Later turns add
  // requirements, and a card frozen at the first proposal leaves those unreachable — there would be
  // no button to create a function the assistant is actively asking for. Superseded cards stay
  // frozen, so history is still immutable where that matters.
  const live = !resolved && activePlan !== null;

  // Function names are registry-safe and stable across turns, so the two lists reconcile by name.
  // Union them: outstanding ones stay actionable, already-created ones remain visible as progress.
  const functions = useMemo(() => {
    const proposed = plan.functions ?? [];
    if (!live) {
      return proposed;
    }
    const outstanding = activePlan?.functions ?? [];
    const outstandingNames = new Set(outstanding.map((fn) => normalizeFunctionName(fn.name)));
    return [
      ...outstanding,
      ...proposed.filter((fn) => !outstandingNames.has(normalizeFunctionName(fn.name))),
    ];
  }, [plan.functions, activePlan?.functions, live]);

  // MCP capabilities cannot be reconciled the same way: they are free text the model rewords every
  // turn ("weather lookup by city" becomes "weather lookup by city name"), so matching on it marked
  // still-pending requirements as completed. While anything is outstanding, show exactly that; when
  // nothing is, the original request is by definition satisfied.
  const outstandingMcp = activePlan?.mcpRequirements ?? [];
  const mcpRequirements = live && outstandingMcp.length > 0
    ? outstandingMcp
    : plan.mcpRequirements ?? [];
  const visibleMcpRequirements = mcpRequirements.filter(hasMcpRequirementDetails);
  const malformedMcpRequirementCount = mcpRequirements.length - visibleMcpRequirements.length;

  const pendingFunctionNames = useMemo(() => new Set(
    (activePlan?.functions ?? []).map((fn) => normalizeFunctionName(fn.name)),
  ), [activePlan?.functions]);

  const functionIsPending = (fn: WorkflowAiProposedFunction) => (
    !resolved && (activePlan === null || pendingFunctionNames.has(normalizeFunctionName(fn.name)))
  );
  const mcpIsPending = () => !resolved && (activePlan === null || outstandingMcp.length > 0);

  // Local, editable copies so the user's corrections are what actually gets created.
  const [drafts, setDrafts] = useState<WorkflowAiProposedFunction[]>(() =>
    functions.map((fn) => ({ ...fn })),
  );
  const [selected, setSelected] = useState<Set<number>>(
    () => new Set(functions.map((_, index) => index)),
  );

  // Resync when the proposal gains or loses a function. Keyed on the names rather than the array so
  // an unrelated re-render cannot wipe out source edits the user is in the middle of making.
  const functionSignature = functions.map((fn) => normalizeFunctionName(fn.name)).join('|');
  useEffect(() => {
    setDrafts(functions.map((fn) => ({ ...fn })));
    setSelected(new Set(functions.map((_, index) => index)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [functionSignature]);

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

  // Approving functions and confirming an attached MCP server are separate turns that share one
  // in-flight flag from the parent. Remember which button started it so only that one spins; the
  // other still disables, because a second turn must not be sent concurrently.
  const [pendingAction, setPendingAction] = useState<'approve' | 'continue' | null>(null);

  useEffect(() => {
    if (!busy) {
      setPendingAction(null);
    }
  }, [busy]);

  const approve = () => {
    const chosen = drafts.filter((fn, index) => selected.has(index) && functionIsPending(fn));
    if (chosen.length === 0) return;
    setPendingAction('approve');
    onApprove(chosen);
  };

  const continueAfterMcp = () => {
    setPendingAction('continue');
    onContinue();
  };

  const selectedCount = drafts.filter(
    (fn, index) => selected.has(index) && functionIsPending(fn),
  ).length;
  const pendingMcpCount = visibleMcpRequirements.filter(() => mcpIsPending()).length;

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
          {visibleMcpRequirements.map((requirement: WorkflowAiMcpRequirement, index) => {
            const pending = mcpIsPending();
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
              {pending && onDiscoverMcp && requirement.capability?.trim() && (
                <button
                  type="button"
                  onClick={() => onDiscoverMcp(requirement.capability)}
                  disabled={busy}
                  className="mt-1.5 flex h-6 items-center gap-1 rounded-[3px] border border-secondary/40 px-1.5 font-mono-sm text-[10px] text-secondary transition-colors hover:bg-secondary-container/20 disabled:opacity-50"
                >
                  <Library size={11} /> Find a server →
                </button>
              )}
            </div>
            );
          })}
          {malformedMcpRequirementCount > 0 && (
            <div className="rounded-[4px] border border-status-warning/35 bg-status-warning/10 px-2.5 py-2 text-[11px] leading-snug text-status-warning">
              MCP requirement details were missing from this reply. Retry it to regenerate the
              server capabilities.
            </div>
          )}
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
              onClick={continueAfterMcp}
              disabled={busy}
              className="flex h-7 items-center gap-1.5 rounded-DEFAULT border border-secondary/40 bg-secondary-container/20 px-2.5 font-mono-sm text-[11px] text-secondary transition-colors hover:bg-secondary-container/35 disabled:opacity-50"
            >
              {busy && pendingAction === 'continue'
                ? <Loader2 className="animate-spin" size={12} />
                : <Check size={12} />}
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
          {busy && pendingAction === 'approve'
            ? <Loader2 className="animate-spin" size={14} />
            : <Check size={14} />}
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
