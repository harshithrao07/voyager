import type { MouseEvent as ReactMouseEvent } from 'react';
import type { Edge, Node } from '@xyflow/react';
import { getStateVisual } from './stateVisuals';
import { NestedScopePreview } from '../components/NestedScopePreview';
import type { MachinePathSegment } from '../components/workflow-create/nestedMachine';

type AslToReactFlowOptions = {
  selectedStateName?: string;
  onStateSelect?: (stateName: string) => void;
  onStateContextMenu?: (stateName: string, event: ReactMouseEvent) => void;
  onOpenNestedScope?: (segment: MachinePathSegment) => void;
  connectable?: boolean;
};

export function aslToReactFlow(asl: any, options: AslToReactFlowOptions = {}): { nodes: Node[]; edges: Edge[] } {
  const nodes: Node[] = [];
  const edges: Edge[] = [];

  if (!asl || !asl.States) return { nodes, edges };

  const branchSides: Record<string, 'left' | 'right'> = {};
  for (const state of Object.values<any>(asl.States)) {
    if (state.Type === 'Choice') {
      for (const choice of state.Choices || []) {
        branchSides[choice.Next] = 'left';
      }
      if (state.Default) {
        branchSides[state.Default] = 'right';
      }
    }
  }

  const stateEntries = Object.entries<any>(asl.States);

  for (const [stateName, state] of stateEntries) {
    const visual = getStateVisual(state.Type);
    let subtitle = formatResourceLabel(state.Resource) || state.Type;
    const nodeStatus: 'active' | 'error' = state.Type === 'Fail' ? 'error' : 'active';

    if (state.Resource?.includes('ECS') || stateName.toLowerCase().includes('embedding')) {
      subtitle = formatResourceLabel(state.Resource) || 'ECS task';
    }

    const isSelected = options.selectedStateName === stateName;
    const isStart = asl.StartAt === stateName;
    nodes.push({
      id: stateName,
      type: 'default',
      position: { x: 0, y: 0 },
      data: { 
        branchSide: branchSides[stateName],
        connectable: Boolean(options.connectable),
        label: (
          <div
            role="button"
            tabIndex={0}
            onClick={(event) => {
              event.stopPropagation();
              options.onStateSelect?.(stateName);
            }}
            onKeyDown={(event) => {
              if (event.key !== 'Enter' && event.key !== ' ') return;
              event.preventDefault();
              event.stopPropagation();
              options.onStateSelect?.(stateName);
            }}
            onContextMenu={(event) => {
              if (!options.onStateContextMenu) return;
              event.preventDefault();
              event.stopPropagation();
              options.onStateContextMenu(stateName, event);
            }}
            className={`${visual.nodeBgClass} border ${isSelected ? `${visual.borderClass} ring-1 ${visual.selectedRingClass} shadow-[0_4px_24px_rgba(99,102,241,0.12)]` : visual.borderClass} w-[200px] rounded p-2 shadow-[0_4px_24px_rgba(0,0,0,0.5)] z-10 flex flex-col relative group cursor-pointer transition-[filter,box-shadow] hover:brightness-110 text-left overflow-hidden`}
          >
            <div className="absolute inset-0 bg-gradient-to-b from-white/[0.03] to-transparent rounded-DEFAULT pointer-events-none"></div>
            <div className={`absolute left-0 top-0 h-full w-1 ${visual.barClass}`}></div>
            
            <div className="flex items-center justify-between gap-2 pl-1">
              <div className="flex min-w-0 items-center gap-1.5">
                <span className={`material-symbols-outlined ${visual.textClass} text-[14px]`}>{visual.iconName}</span>
                <span className={`font-mono-sm text-mono-sm font-medium truncate ${nodeStatus === 'error' ? 'text-status-error' : 'text-primary'}`}>{stateName}</span>
              </div>
              <div className="flex shrink-0 items-center gap-1.5">
                {isStart && (
                  <span className="flex items-center gap-0.5 rounded-sm border border-secondary/40 bg-secondary-container/40 px-1 py-px font-mono-sm text-[8px] uppercase tracking-wide text-secondary-fixed">
                    <span className="material-symbols-outlined text-[10px]">play_arrow</span>
                    start
                  </span>
                )}
                <span className={`h-1.5 w-1.5 rounded-full ${visual.dotClass} animate-pulse`}></span>
              </div>
            </div>
            
            <div className="mt-2 flex items-end justify-between gap-2 pl-1">
              <span className={`min-w-0 truncate font-mono-sm text-[10px] ${nodeStatus === 'error' ? 'text-status-error/70' : 'text-on-surface-variant'}`} title={subtitle}>
                {subtitle}
              </span>
              <div className="flex shrink-0">
                <span className={`rounded border px-1 py-0.5 font-mono-sm text-[9px] ${visual.chipClass}`}>
                  {visual.label}
                </span>
              </div>
            </div>

            {(state.Type === 'Parallel' || state.Type === 'Map') && (
              <div className="pl-1">
                <NestedScopePreview
                  stateName={stateName}
                  state={state}
                  onOpenNestedScope={options.onOpenNestedScope}
                />
              </div>
            )}
          </div>
        )
      },
      style: { 
        background: 'transparent', 
        border: 'none',
        borderRadius: '0',
        padding: 0,
        boxShadow: 'none',
        width: 200
      }
    });

    if (state.Next) {
      edges.push({
        id: `${stateName}-next-${state.Next}`,
        source: stateName,
        target: state.Next,
        type: 'dataFlow',
        data: { status: 'normal', color: visual.stroke, label: 'Next', transitionKind: 'next' },
        style: { stroke: visual.stroke, strokeWidth: 2 }
      });
    }

    if (state.Choices) {
      for (const [choiceIndex, choice] of state.Choices.entries()) {
        if (!choice?.Next) continue;
        edges.push({
          id: `${stateName}-choice-${choiceIndex}-${choice.Next}`,
          source: stateName,
          target: choice.Next,
          type: 'dataFlow',
          data: { status: 'normal', color: visual.stroke, label: 'Condition', transitionKind: 'choice', transitionIndex: choiceIndex },
          style: { stroke: visual.stroke, strokeWidth: 2 }
        });
      }
    }

    if (state.Default) {
      edges.push({
        id: `${stateName}-${state.Default}`,
        source: stateName,
        target: state.Default,
        type: 'dataFlow',
        data: { status: 'error', label: 'Default', transitionKind: 'default' },
        style: { stroke: '#333333', strokeWidth: 2, strokeDasharray: '4' }
      });
    }

    if (state.Catch) {
      for (const [catcherIndex, catcher] of state.Catch.entries()) {
        if (!catcher?.Next) continue;
        edges.push({
          id: `${stateName}-catch-${catcherIndex}-${catcher.Next}`,
          source: stateName,
          target: catcher.Next,
          type: 'dataFlow',
          data: { status: 'error', label: 'Catch', transitionKind: 'catch', transitionIndex: catcherIndex },
          style: { stroke: 'var(--status-error)', strokeWidth: 2, strokeDasharray: '4' }
        });
      }
    }
  }

  return { nodes, edges };
}

function formatResourceLabel(resource: unknown) {
  if (typeof resource !== 'string' || !resource) return '';
  if (!resource.startsWith('voyager://')) return resource;

  const rest = resource.slice('voyager://'.length).replace(/^\/+/, '');
  return rest || resource;
}
