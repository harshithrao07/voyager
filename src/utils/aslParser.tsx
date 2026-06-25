import type { Node, Edge } from '@xyflow/react';

export function aslToReactFlow(asl: any): { nodes: Node[]; edges: Edge[] } {
  const nodes: Node[] = [];
  const edges: Edge[] = [];

  if (!asl || !asl.States) return { nodes, edges };

  // Create Start Node
  if (asl.StartAt) {
    nodes.push({
      id: 'Start',
      type: 'input',
      position: { x: 0, y: 0 },
      data: { 
        label: (
          <div className="w-12 h-12 rounded-full border-2 border-border-muted bg-surface-elevated flex items-center justify-center shadow-lg relative">
            <span className="w-4 h-4 rounded-full bg-status-success"></span>
          </div>
        )
      },
      style: { 
        background: 'transparent',
        border: 'none',
        padding: 0,
        boxShadow: 'none'
      }
    });
    edges.push({
      id: `Start-${asl.StartAt}`,
      source: 'Start',
      target: asl.StartAt,
      type: 'smoothstep'
    });
  }

  for (const [stateName, state] of Object.entries<any>(asl.States)) {
    let borderColorClass = 'border-status-info'; // default Task (Indigo)
    let iconColorClass = 'text-status-info';
    let iconName = 'settings';
    let subtitle = state.Resource ? state.Resource.split(':').pop() : state.Type;

    if (state.Type === 'Choice') {
      borderColorClass = 'border-status-warning';
      iconColorClass = 'text-status-warning';
      iconName = 'call_split';
    } else if (state.Type === 'Pass') {
      borderColorClass = 'border-border-muted';
      iconColorClass = 'text-on-surface-variant';
      iconName = 'swap_horiz';
    } else if (state.Type === 'Wait') {
      borderColorClass = 'border-status-accent';
      iconColorClass = 'text-status-accent';
      iconName = 'schedule';
    } else if (state.Type === 'Succeed') {
      borderColorClass = 'border-status-success';
      iconColorClass = 'text-status-success';
      iconName = 'check_circle';
    } else if (state.Type === 'Fail') {
      borderColorClass = 'border-status-error';
      iconColorClass = 'text-status-error';
      iconName = 'cancel';
    } else if (state.Type === 'Map') {
      borderColorClass = 'border-status-info'; 
      iconColorClass = 'text-status-info';
      iconName = 'layers';
    } else if (state.Type === 'Parallel') {
      borderColorClass = 'border-status-accent';
      iconColorClass = 'text-status-accent';
      iconName = 'splitscreen';
    }

    nodes.push({
      id: stateName,
      type: 'default',
      position: { x: 0, y: 0 },
      data: { 
        label: (
          <div className={`bg-surface-elevated border ${borderColorClass} w-64 rounded-DEFAULT shadow-[0_8px_24px_rgba(0,0,0,0.4)] z-10 flex flex-col relative group cursor-pointer hover:border-primary transition-colors text-left`}>
            {/* Inner glow gradient */}
            <div className="absolute inset-0 bg-gradient-to-b from-white/[0.03] to-transparent rounded-DEFAULT pointer-events-none"></div>
            
            <div className="p-3 border-b border-border-subtle flex items-center gap-2">
              <span className={`material-symbols-outlined ${iconColorClass} text-[18px]`}>{iconName}</span>
              <span className="font-body-sm text-body-sm text-primary font-medium truncate">{stateName}</span>
            </div>
            
            <div className="p-3 bg-surface-base rounded-b-DEFAULT flex gap-2">
              {state.Type === 'Choice' ? (
                 <span className="text-mono-sm font-mono-sm bg-surface-container px-1.5 py-0.5 rounded text-on-surface-variant">Choice State</span>
              ) : (
                <div className="flex justify-between items-center w-full">
                  <span className="text-mono-sm font-mono-sm text-on-surface-variant truncate">{subtitle}</span>
                  {state.Type === 'Task' && (
                    <span className="text-mono-sm font-mono-sm text-on-surface-variant flex items-center gap-1 flex-shrink-0">
                      <span className="w-1.5 h-1.5 rounded-full bg-status-success"></span> 1.2s
                    </span>
                  )}
                </div>
              )}
            </div>
          </div>
        ) 
      },
      style: { 
        background: 'transparent', 
        border: 'none',
        borderRadius: '0',
        padding: 0,
        boxShadow: 'none',
        width: 256
      }
    });

    if (state.Next) {
      edges.push({
        id: `${stateName}-${state.Next}`,
        source: stateName,
        target: state.Next,
        type: 'smoothstep',
        style: { stroke: '#333333', strokeWidth: 2 },
        animated: false
      });
    }

    if (state.Choices) {
      for (const choice of state.Choices) {
        edges.push({
          id: `${stateName}-${choice.Next}`,
          source: stateName,
          target: choice.Next,
          type: 'step',
          style: { stroke: '#333333', strokeWidth: 2 }
        });
      }
    }

    if (state.Default) {
      edges.push({
        id: `${stateName}-${state.Default}`,
        source: stateName,
        target: state.Default,
        type: 'step',
        style: { stroke: '#333333', strokeWidth: 2, strokeDasharray: '4' }
      });
    }

    if (state.Catch) {
      for (const catcher of state.Catch) {
        edges.push({
          id: `${stateName}-catch-${catcher.Next}`,
          source: stateName,
          target: catcher.Next,
          type: 'smoothstep',
          style: { stroke: 'var(--status-error)', strokeWidth: 2, strokeDasharray: '4' }
        });
      }
    }
  }

  // Add a pseudo End node if End=true is found to match the screenshot bottom dot?
  // Actually, the screenshot has an End node, I'll add one if any state has End: true.
  const hasEnd = Object.values<any>(asl.States || {}).some(s => s.End);
  if (hasEnd) {
    nodes.push({
      id: 'End',
      type: 'output',
      position: { x: 0, y: 0 },
      data: {
        label: (
          <div className="w-12 h-12 rounded-full border-2 border-border-muted bg-surface-elevated flex items-center justify-center shadow-lg relative">
            <span className="w-4 h-4 rounded-full border-2 border-status-success"></span>
          </div>
        )
      },
      style: { 
        background: 'transparent',
        border: 'none',
        padding: 0,
        boxShadow: 'none'
      }
    });
    
    // Connect any End: true to 'End' node
    for (const [stateName, state] of Object.entries<any>(asl.States)) {
      if (state.End) {
        edges.push({
          id: `${stateName}-End`,
          source: stateName,
          target: 'End',
          type: 'smoothstep',
          style: { stroke: '#333333', strokeWidth: 2 }
        });
      }
    }
  }

  return { nodes, edges };
}
