import { useState } from 'react';
import { getStateVisual } from '../utils/stateVisuals';
import { isMachine, type MachinePathSegment } from './workflow-create/nestedMachine';
import type { AslDefinition } from './workflow-create/stateBuilder';

/** Order a nested machine's states by following the `Next` chain from StartAt, then append any strays. */
function sequenceStateNames(machine: AslDefinition): string[] {
  const states = machine.States || {};
  const names = Object.keys(states);
  if (names.length === 0) return [];

  const ordered: string[] = [];
  const seen = new Set<string>();
  let cursor = machine.StartAt && states[machine.StartAt] ? machine.StartAt : names[0];
  while (cursor && states[cursor] && !seen.has(cursor)) {
    ordered.push(cursor);
    seen.add(cursor);
    const next = (states[cursor] as { Next?: unknown }).Next;
    cursor = typeof next === 'string' ? next : '';
  }
  for (const name of names) {
    if (!seen.has(name)) ordered.push(name);
  }
  return ordered;
}

const MAX_STATES_PER_LANE = 4;

function ScopeLane({
  label,
  machine,
  onOpen,
}: {
  label: string;
  machine: AslDefinition;
  onOpen?: () => void;
}) {
  const names = sequenceStateNames(machine);
  const shown = names.slice(0, MAX_STATES_PER_LANE);
  const extra = names.length - shown.length;
  const summary = names.length === 0 ? 'empty' : `${label}: ${names.join(' → ')}`;

  return (
    <div
      className="flex items-center gap-1.5 rounded-[3px] border border-border-subtle/60 bg-surface-container-lowest/60 px-1.5 py-1"
      title={summary}
    >
      <span className="shrink-0 font-mono-sm text-[8px] uppercase tracking-wide text-on-surface-variant/80">
        {label}
      </span>
      <div className="flex min-w-0 flex-1 items-center gap-0.5 overflow-hidden">
        {shown.length === 0 ? (
          <span className="font-mono-sm text-[9px] text-on-surface-variant/60">empty</span>
        ) : (
          shown.map((name, index) => {
            const dot = getStateVisual(machine.States?.[name]?.Type).stroke;
            return (
              <span key={name} className="flex min-w-0 items-center gap-0.5">
                {index > 0 && <span className="shrink-0 text-on-surface-variant/40">&rsaquo;</span>}
                <span className="h-1 w-1 shrink-0 rounded-full" style={{ background: dot }} />
                <span className="truncate font-mono-sm text-[9px] text-on-surface-variant">{name}</span>
              </span>
            );
          })
        )}
        {extra > 0 && (
          <span className="shrink-0 font-mono-sm text-[9px] text-on-surface-variant/55">+{extra}</span>
        )}
      </div>
      {onOpen && (
        <button
          type="button"
          onClick={(event) => {
            event.stopPropagation();
            onOpen();
          }}
          className="nodrag flex h-4 w-4 shrink-0 items-center justify-center rounded-[3px] text-on-surface-variant transition-colors hover:bg-surface-container hover:text-on-surface"
          title={`Open ${label}`}
          aria-label={`Open ${label}`}
        >
          <span className="material-symbols-outlined text-[11px]">open_in_full</span>
        </button>
      )}
    </div>
  );
}

const MAX_LANES = 3;

type Props = {
  stateName: string;
  state: { Type?: string; Branches?: unknown; ItemProcessor?: unknown };
  onOpenNestedScope?: (segment: MachinePathSegment) => void;
};

/** Compact in-node window into a Parallel state's branches or a Map state's item processor. */
export function NestedScopePreview({ stateName, state, onOpenNestedScope }: Props) {
  const [expanded, setExpanded] = useState(false);

  if (state.Type === 'Map') {
    const itemProcessor = state.ItemProcessor;
    if (!isMachine(itemProcessor)) {
      return (
        <div className="mt-2 rounded-[3px] border border-dashed border-border-subtle/60 px-1.5 py-1 font-mono-sm text-[9px] text-on-surface-variant/60">
          No item processor yet
        </div>
      );
    }
    return (
      <div className="mt-2 flex flex-col gap-1">
        <ScopeLane
          label="For each item"
          machine={itemProcessor}
          onOpen={onOpenNestedScope ? () => onOpenNestedScope({ kind: 'map', stateName }) : undefined}
        />
      </div>
    );
  }

  if (state.Type !== 'Parallel') return null;

  const branches = (Array.isArray(state.Branches) ? state.Branches : []).filter(isMachine);
  if (branches.length === 0) {
    return (
      <div className="mt-2 rounded-[3px] border border-dashed border-border-subtle/60 px-1.5 py-1 font-mono-sm text-[9px] text-on-surface-variant/60">
        No branches yet
      </div>
    );
  }

  const visibleCount = expanded ? branches.length : Math.min(MAX_LANES, branches.length);
  const hiddenCount = branches.length - visibleCount;

  return (
    <div className="mt-2 flex flex-col gap-1">
      {branches.slice(0, visibleCount).map((branch, branchIndex) => (
        <ScopeLane
          key={branchIndex}
          label={`Branch ${branchIndex + 1}`}
          machine={branch}
          onOpen={
            onOpenNestedScope
              ? () => onOpenNestedScope({ kind: 'parallel', stateName, branchIndex })
              : undefined
          }
        />
      ))}
      {branches.length > MAX_LANES && (
        <button
          type="button"
          onClick={(event) => {
            event.stopPropagation();
            setExpanded((value) => !value);
          }}
          className="nodrag self-start rounded-[3px] px-1 py-0.5 font-mono-sm text-[9px] text-on-surface-variant/70 transition-colors hover:text-on-surface"
        >
          {expanded ? 'Show fewer' : `+${hiddenCount} more ${hiddenCount === 1 ? 'branch' : 'branches'}`}
        </button>
      )}
    </div>
  );
}
