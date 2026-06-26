import type { Edge, Node } from '@xyflow/react';
import { getStateVisual } from './stateVisuals';

type AslToReactFlowOptions = {
  selectedStateName?: string;
  onStateSelect?: (stateName: string) => void;
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

  for (const [index, [stateName, state]] of stateEntries.entries()) {
    const visual = getStateVisual(state.Type);
    let subtitle = state.Resource || state.Type;
    const nodeStatus: 'active' | 'error' = state.Type === 'Fail' ? 'error' : 'active';

    if (state.Resource?.includes('ECS') || stateName.toLowerCase().includes('embedding')) {
      subtitle = state.Resource || 'ECS task';
    }

    const isSelected = options.selectedStateName === stateName;
    const stateId = getStateRuntimeId(stateName, index);
    const latency = getStateLatency(stateName, state);
    const retries = nodeStatus === 'error' ? '3r' : '0r';

    nodes.push({
      id: stateName,
      type: 'default',
      position: { x: 0, y: 0 },
      data: { 
        branchSide: branchSides[stateName],
        label: (
          <button
            type="button"
            onClick={() => options.onStateSelect?.(stateName)}
            className={`${nodeStatus === 'error' ? 'bg-[#1a0f12]' : 'bg-surface-elevated'} border ${visual.softBgClass} ${isSelected ? `${visual.borderClass} ring-1 ${visual.selectedRingClass} shadow-[0_4px_24px_rgba(99,102,241,0.12)]` : visual.borderClass} w-[200px] rounded p-2 shadow-[0_4px_24px_rgba(0,0,0,0.5)] z-10 flex flex-col relative group cursor-pointer transition-[filter,box-shadow] hover:brightness-110 text-left overflow-hidden`}
          >
            <div className="absolute inset-0 bg-gradient-to-b from-white/[0.03] to-transparent rounded-DEFAULT pointer-events-none"></div>
            <div className={`absolute left-0 top-0 h-full w-1 ${visual.barClass}`}></div>
            
            <div className="flex items-center justify-between gap-2 pl-1">
              <div className="flex min-w-0 items-center gap-1.5">
                <span className={`material-symbols-outlined ${visual.textClass} text-[14px]`}>{visual.iconName}</span>
                <span className={`font-mono-sm text-mono-sm font-medium truncate ${nodeStatus === 'error' ? 'text-status-error' : 'text-primary'}`}>{stateName}</span>
              </div>
              <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${visual.dotClass} animate-pulse`}></span>
            </div>
            
            <div className="mt-2 flex items-end justify-between gap-2 pl-1">
              <span className={`font-mono-sm text-[10px] truncate ${nodeStatus === 'error' ? 'text-status-error/70' : 'text-on-surface-variant'}`}>
                ID: {stateId}
              </span>
              <div className="flex shrink-0 gap-1">
                <span className={`rounded border px-1 py-0.5 font-mono-sm text-[9px] ${visual.chipClass}`}>
                  {visual.label}
                </span>
                <span className={`${nodeStatus === 'error' ? 'bg-error-container/20 text-status-error' : 'bg-surface-container text-on-surface-variant'} rounded px-1 py-0.5 font-mono-sm text-[9px]`} title={subtitle}>
                  {latency}
                </span>
                <span className={`${nodeStatus === 'error' ? 'bg-error-container/20 text-status-error' : 'bg-surface-container text-on-surface-variant'} rounded px-1 py-0.5 font-mono-sm text-[9px]`}>
                  {retries}
                </span>
              </div>
            </div>
          </button>
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
        id: `${stateName}-${state.Next}`,
        source: stateName,
        target: state.Next,
        type: 'dataFlow',
        data: { status: 'normal' },
        style: { stroke: '#333333', strokeWidth: 2 }
      });
    }

    if (state.Choices) {
      for (const choice of state.Choices) {
        edges.push({
          id: `${stateName}-${choice.Next}`,
          source: stateName,
          target: choice.Next,
          type: 'dataFlow',
          data: { status: 'normal' },
          style: { stroke: '#333333', strokeWidth: 2 }
        });
      }
    }

    if (state.Default) {
      edges.push({
        id: `${stateName}-${state.Default}`,
        source: stateName,
        target: state.Default,
        type: 'dataFlow',
        data: { status: 'error' },
        style: { stroke: '#333333', strokeWidth: 2, strokeDasharray: '4' }
      });
    }

    if (state.Catch) {
      for (const catcher of state.Catch) {
        edges.push({
          id: `${stateName}-catch-${catcher.Next}`,
          source: stateName,
          target: catcher.Next,
          type: 'dataFlow',
          data: { status: 'error' },
          style: { stroke: 'var(--status-error)', strokeWidth: 2, strokeDasharray: '4' }
        });
      }
    }
  }

  return { nodes, edges };
}

function getStateRuntimeId(stateName: string, index: number) {
  const key = stateName.toLowerCase();
  if (key.includes('fetch') || key.includes('ingest')) return 'src_01a';
  if (key.includes('valid') || key.includes('embed') || key.includes('transform')) return 'trf_09x';
  if (key.includes('process') || key.includes('write')) return 'snk_11b';
  if (key.includes('fail') || key.includes('dlq') || key.includes('error')) return 'err_01z';
  return `st_${String(index + 1).padStart(2, '0')}x`;
}

function getStateLatency(stateName: string, state: any) {
  const key = stateName.toLowerCase();
  if (state.TimeoutSeconds) return `${state.TimeoutSeconds}s`;
  if (key.includes('valid') || key.includes('embed') || key.includes('transform')) return '45ms';
  if (key.includes('process') || key.includes('write')) return '12ms';
  if (key.includes('fail') || key.includes('dlq') || key.includes('error')) return 'Timeout';
  return '2ms';
}
