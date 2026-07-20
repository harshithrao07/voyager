import { useCallback, useEffect, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent, RefObject } from 'react';
import { ReactFlow, useNodesState, useEdgesState, Handle, Position, useReactFlow, BaseEdge, Background, BackgroundVariant, EdgeLabelRenderer, getBezierPath } from '@xyflow/react';
import type { Connection, EdgeProps, OnNodeDrag } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import dagre from '@dagrejs/dagre';
import { aslToReactFlow } from '../utils/aslParser';
import { LocateFixed, Maximize2, Minimize2, ZoomIn, ZoomOut } from 'lucide-react';
import type { CanvasNodePositions } from '../types/workflowCanvas';

const dagreGraph = new dagre.graphlib.Graph();
dagreGraph.setDefaultEdgeLabel(() => ({}));
const NODE_WIDTH = 200;
const NODE_HEIGHT = 72;
const NEW_NODE_X_GAP = 280;
const NEW_NODE_Y_GAP = 124;
type LayoutDirection = 'LR' | 'TB';

const getLayoutedElements = (nodes: any[], edges: any[], direction: LayoutDirection = 'TB') => {
  dagreGraph.setGraph({ rankdir: direction, nodesep: 92, ranksep: 92 });

  nodes.forEach((node) => {
    dagreGraph.setNode(node.id, { width: NODE_WIDTH, height: NODE_HEIGHT });
  });

  edges.forEach((edge) => {
    dagreGraph.setEdge(edge.source, edge.target);
  });

  dagre.layout(dagreGraph);

  const newNodes = nodes.map((node) => {
    const nodeWithPosition = dagreGraph.node(node.id);
    return {
      ...node,
      targetPosition: direction === 'TB' ? 'top' : 'left',
      sourcePosition: direction === 'TB' ? 'bottom' : 'right',
      data: { ...(node.data || {}), layoutDirection: direction },
      position: {
        x: nodeWithPosition.x - NODE_WIDTH / 2,
        y: nodeWithPosition.y - NODE_HEIGHT / 2,
      },
    };
  });

  const leftBranch = newNodes.find((node) => node.data?.branchSide === 'left');
  const rightBranch = newNodes.find((node) => node.data?.branchSide === 'right');
  if (leftBranch && rightBranch && leftBranch.position.x > rightBranch.position.x) {
    const leftX = leftBranch.position.x;
    leftBranch.position.x = rightBranch.position.x;
    rightBranch.position.x = leftX;
  }

  return { nodes: newNodes, edges };
};

type NodePosition = { x: number; y: number };

function applySavedPositions(nodes: any[], positions?: CanvasNodePositions) {
  if (!positions) return nodes;

  return nodes.map((node) => {
    const position = positions[node.id];
    return position ? { ...node, position } : node;
  });
}

function positionsFromNodes(nodes: any[]): CanvasNodePositions {
  return Object.fromEntries(nodes.map((node) => [
    node.id,
    { x: node.position.x, y: node.position.y },
  ]));
}

function overlaps(a: NodePosition, b: NodePosition) {
  return Math.abs(a.x - b.x) < NODE_WIDTH + 28 && Math.abs(a.y - b.y) < NODE_HEIGHT + 28;
}

function nextOpenPosition(existingPositions: NodePosition[], fallbackPosition: NodePosition, index: number, direction: LayoutDirection) {
  if (existingPositions.length === 0) {
    return fallbackPosition;
  }

  const maxX = Math.max(...existingPositions.map((position) => position.x));
  const minY = Math.min(...existingPositions.map((position) => position.y));
  const maxY = Math.max(...existingPositions.map((position) => position.y));
  const minX = Math.min(...existingPositions.map((position) => position.x));
  if (direction === 'TB') {
    const horizontalSlots = Math.max(3, Math.ceil(existingPositions.length / 2));
    let position = {
      x: minX + (index % horizontalSlots) * NEW_NODE_X_GAP,
      y: maxY + NEW_NODE_Y_GAP,
    };

    while (existingPositions.some((existing) => overlaps(position, existing))) {
      position = {
        x: position.x > maxX + NEW_NODE_X_GAP ? minX : position.x + NEW_NODE_X_GAP,
        y: position.x > maxX + NEW_NODE_X_GAP ? position.y + NEW_NODE_Y_GAP : position.y,
      };
    }

    return position;
  }

  const verticalSlots = Math.max(3, Math.ceil(existingPositions.length / 2));
  let position = {
    x: maxX + NEW_NODE_X_GAP,
    y: minY + (index % verticalSlots) * NEW_NODE_Y_GAP,
  };

  while (existingPositions.some((existing) => overlaps(position, existing))) {
    position = {
      x: position.y > maxY + NEW_NODE_Y_GAP ? position.x + NEW_NODE_X_GAP : position.x,
      y: position.y > maxY + NEW_NODE_Y_GAP ? minY : position.y + NEW_NODE_Y_GAP,
    };
  }

  return position;
}

// Custom Nodes to hide default ReactFlow styles and handle edges perfectly
const handleStyle = {
  width: 10,
  height: 10,
  background: '#f2795a',
  border: '2px solid rgba(7, 13, 24, 0.96)',
  boxShadow: '0 0 0 3px rgba(242, 121, 90, 0.16)',
};

const hiddenHandleStyle = { visibility: 'hidden' as const };

function ConnectionHandle({
  type,
  position,
  connectable,
}: {
  type: 'source' | 'target';
  position: Position;
  connectable: boolean;
}) {
  return (
    <Handle
      type={type}
      position={position}
      isConnectable={connectable}
      style={connectable ? handleStyle : hiddenHandleStyle}
    />
  );
}

const CustomDefaultNode = ({ data }: any) => (
  <>
    <ConnectionHandle
      type="target"
      position={data.layoutDirection === 'TB' ? Position.Top : Position.Left}
      connectable={Boolean(data.connectable)}
    />
    {data.label}
    <ConnectionHandle
      type="source"
      position={data.layoutDirection === 'TB' ? Position.Bottom : Position.Right}
      connectable={Boolean(data.connectable)}
    />
  </>
);

const CustomInputNode = ({ data }: any) => (
  <>
    {data.label}
    <ConnectionHandle
      type="source"
      position={data.layoutDirection === 'TB' ? Position.Bottom : Position.Right}
      connectable={Boolean(data.connectable)}
    />
  </>
);

const CustomOutputNode = ({ data }: any) => (
  <>
    <ConnectionHandle
      type="target"
      position={data.layoutDirection === 'TB' ? Position.Top : Position.Left}
      connectable={Boolean(data.connectable)}
    />
    {data.label}
  </>
);

function DataFlowEdge({
  id,
  source,
  target,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  data,
}: EdgeProps) {
  const [edgePath, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
    curvature: 0.36,
  });
  const isError = data?.status === 'error';
  const stroke = isError ? '#f43f5e' : (typeof data?.color === 'string' ? data.color : '#3c4350');
  const label = typeof data?.label === 'string' ? data.label : '';
  const onContextMenu = typeof data?.onContextMenu === 'function' ? data.onContextMenu : undefined;

  return (
    <>
      <path
        d={edgePath}
        fill="none"
        stroke="transparent"
        strokeWidth={18}
        className="react-flow__edge-interaction"
        onContextMenu={(event) => {
          if (!onContextMenu || !source || !target) return;
          event.preventDefault();
          event.stopPropagation();
          onContextMenu({
            sourceName: source,
            targetName: target,
            kind: data?.transitionKind,
            index: data?.transitionIndex,
            x: event.clientX,
            y: event.clientY,
          });
        }}
      />
      <BaseEdge
        id={id}
        path={edgePath}
        style={{
          stroke,
          strokeWidth: 2,
          strokeDasharray: isError ? '6 7' : '4 7',
          opacity: isError ? 0.82 : 0.9,
          animation: 'workflow-edge-dash 18s linear infinite',
        }}
      />
      {label && (
        <EdgeLabelRenderer>
          <div
            className={`nodrag nopan pointer-events-auto absolute -translate-x-1/2 -translate-y-1/2 rounded border px-1.5 py-0.5 font-mono-sm text-[9px] uppercase tracking-[0.06em] shadow-[0_8px_24px_rgba(0,0,0,0.32)] ${
              isError
                ? 'border-status-error/35 bg-status-error/15 text-status-error'
                : 'border-border-subtle bg-surface-container-highest/90 text-on-surface-variant'
            }`}
            style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}
            onContextMenu={(event) => {
              if (!onContextMenu || !source || !target) return;
              event.preventDefault();
              event.stopPropagation();
              onContextMenu({
                sourceName: source,
                targetName: target,
                kind: data?.transitionKind,
                index: data?.transitionIndex,
                x: event.clientX,
                y: event.clientY,
              });
            }}
          >
            {label}
          </div>
        </EdgeLabelRenderer>
      )}
      {isError ? (
        <>
          <circle r="3.25" fill="#f43f5e" className="workflow-data-packet-error" opacity="0">
            <animate attributeName="opacity" values="0;1;1;0" dur="2.1s" repeatCount="indefinite" />
            <animateMotion dur="2.1s" repeatCount="indefinite" path={edgePath} />
          </circle>
          <circle r="2.5" fill="#fb7185" className="workflow-data-packet-error workflow-data-packet-error-secondary" opacity="0">
            <animate attributeName="opacity" values="0;1;1;0" dur="2.7s" begin="0.55s" repeatCount="indefinite" />
            <animateMotion dur="2.7s" begin="0.55s" repeatCount="indefinite" path={edgePath} />
          </circle>
        </>
      ) : (
        <>
          <circle r="3" fill="#6366f1" className="workflow-data-packet" opacity="0">
            <animate attributeName="opacity" values="0;1;1;0" dur="2.2s" repeatCount="indefinite" />
            <animateMotion dur="2.2s" repeatCount="indefinite" path={edgePath} />
          </circle>
          <circle r="2.5" fill="#10b981" className="workflow-data-packet workflow-data-packet-secondary" opacity="0">
            <animate attributeName="opacity" values="0;1;1;0" dur="2.8s" begin="0.8s" repeatCount="indefinite" />
            <animateMotion dur="2.8s" begin="0.8s" repeatCount="indefinite" path={edgePath} />
          </circle>
        </>
      )}
    </>
  );
}

const nodeTypes = {
  default: CustomDefaultNode,
  input: CustomInputNode,
  output: CustomOutputNode,
};

const edgeTypes = {
  dataFlow: DataFlowEdge,
};

function FitViewOnKey({ fitKey }: { fitKey?: string }) {
  const { fitView } = useReactFlow();
  const lastKeyRef = useRef<string | undefined>(undefined);

  useEffect(() => {
    // Recenter only when the scope changes (canvas switch / branch switch), never on
    // in-scope edits. Defer so the new scope's nodes are laid out and measured first.
    if (fitKey === undefined || lastKeyRef.current === fitKey) return;
    lastKeyRef.current = fitKey;
    const timeout = window.setTimeout(() => fitView({ padding: 0.22, maxZoom: 1.05, duration: 300 }), 130);
    return () => window.clearTimeout(timeout);
  }, [fitKey, fitView]);

  return null;
}

function CanvasControls() {
  const { fitView, zoomIn, zoomOut } = useReactFlow();

  return (
    <div className="glass-control absolute bottom-6 left-1/2 z-30 flex w-fit -translate-x-1/2 items-center gap-element-gap-md rounded-full border border-border-muted bg-surface-container-highest/80 px-6 py-3 shadow-lg backdrop-blur-xl">
      <button onClick={() => zoomIn()} className="flex flex-col items-center gap-1 text-on-surface opacity-60 transition-all hover:scale-110 hover:opacity-100" aria-label="Zoom in">
        <ZoomIn size={20} />
        <span className="text-mono-sm font-mono-sm text-[10px]">Zoom In</span>
      </button>
      <button onClick={() => zoomOut()} className="flex flex-col items-center gap-1 text-on-surface opacity-60 transition-all hover:scale-110 hover:opacity-100" aria-label="Zoom out">
        <ZoomOut size={20} />
        <span className="text-mono-sm font-mono-sm text-[10px]">Zoom Out</span>
      </button>
      <button onClick={() => fitView({ padding: 0.2 })} className="flex scale-110 flex-col items-center gap-1 text-status-accent transition-all hover:scale-110" aria-label="Reset pan">
        <LocateFixed size={20} />
        <span className="text-mono-sm font-mono-sm text-[10px]">Reset Pan</span>
      </button>
    </div>
  );
}

function CanvasFullscreenButton({ targetRef }: { targetRef: RefObject<HTMLDivElement | null> }) {
  const { fitView } = useReactFlow();
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [fullscreenSupported] = useState(() => Boolean(document.fullscreenEnabled));

  const refitCanvas = useCallback(() => {
    window.setTimeout(() => fitView({ padding: 0.22, maxZoom: 1.05 }), 120);
  }, [fitView]);

  useEffect(() => {
    const handleFullscreenChange = () => {
      setIsFullscreen(document.fullscreenElement === targetRef.current);
      refitCanvas();
    };

    document.addEventListener('fullscreenchange', handleFullscreenChange);
    return () => document.removeEventListener('fullscreenchange', handleFullscreenChange);
  }, [refitCanvas, targetRef]);

  const handleFullscreenToggle = async () => {
    const fullscreenTarget = targetRef.current;
    if (!fullscreenTarget || !fullscreenSupported) {
      return;
    }

    try {
      if (document.fullscreenElement === fullscreenTarget) {
        await document.exitFullscreen();
      } else {
        await fullscreenTarget.requestFullscreen();
      }
      refitCanvas();
    } catch (error) {
      console.error('Unable to toggle workflow canvas fullscreen.', error);
    }
  };

  const label = isFullscreen ? 'Exit fullscreen' : 'Enter fullscreen';

  return (
    <button
      type="button"
      onClick={handleFullscreenToggle}
      disabled={!fullscreenSupported}
      className="glass-control nodrag nopan group absolute right-6 top-6 z-30 flex h-10 w-10 items-center justify-center rounded-full border border-border-muted bg-surface-container-highest/80 text-on-surface shadow-lg backdrop-blur-xl transition-all hover:scale-105 hover:border-status-info hover:text-primary focus:outline-none focus:ring-2 focus:ring-status-info/40 disabled:cursor-not-allowed disabled:opacity-40"
      aria-label={label}
      title={label}
    >
      {isFullscreen ? <Minimize2 size={18} /> : <Maximize2 size={18} />}
      <span className="pointer-events-none absolute right-0 top-12 whitespace-nowrap rounded border border-border-subtle bg-surface-container-highest px-2 py-1 font-mono-sm text-[10px] text-on-surface opacity-0 shadow-lg transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100">
        {label}
      </span>
    </button>
  );
}

interface Props {
  definition: any;
  selectedStateName?: string;
  onStateSelect: (stateName: string) => void;
  connectable?: boolean;
  onConnectStates?: (sourceStateName: string, targetStateName: string) => void;
  preserveNodePositions?: boolean;
  initialNodePositions?: CanvasNodePositions;
  onNodePositionsChange?: (positions: CanvasNodePositions) => void;
  layoutDirection?: LayoutDirection;
  layoutVersion?: number;
  onStateContextMenu?: (stateName: string, event: ReactMouseEvent) => void;
  onOpenNestedScope?: (segment: import('./workflow-create/nestedMachine').MachinePathSegment) => void;
  /** When this key changes the canvas recenters (fit view). Use it to reset pan on scope/branch switches. */
  fitViewKey?: string;
  onEdgeContextMenu?: (transition: {
    sourceName: string;
    targetName: string;
    kind: unknown;
    index?: unknown;
    x: number;
    y: number;
  }) => void;
}

export function AslGraphViewer({
  definition,
  selectedStateName,
  onStateSelect,
  connectable = false,
  onConnectStates,
  preserveNodePositions = false,
  initialNodePositions,
  onNodePositionsChange,
  layoutDirection = 'TB',
  layoutVersion = 0,
  onStateContextMenu,
  onOpenNestedScope,
  onEdgeContextMenu,
  fitViewKey,
}: Props) {
  const viewerRef = useRef<HTMLDivElement>(null);
  const lastLayoutVersionRef = useRef(layoutVersion);
  const lastInitialPositionsRef = useRef(initialNodePositions);
  const [nodes, setNodes, onNodesChange] = useNodesState<any>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<any>([]);

  const handleConnect = useCallback((connection: Connection) => {
    if (!connection.source || !connection.target || connection.source === connection.target) {
      return;
    }
    onConnectStates?.(connection.source, connection.target);
  }, [onConnectStates]);

  const handleNodeDragStop: OnNodeDrag = useCallback((_event, movedNode, draggedNodes) => {
    if (!onNodePositionsChange) return;

    const movedPositions = new Map(
      draggedNodes.map((node) => [node.id, node.position] as const),
    );
    movedPositions.set(movedNode.id, movedNode.position);
    onNodePositionsChange(positionsFromNodes(nodes.map((node) => ({
      ...node,
      position: movedPositions.get(node.id) || node.position,
    }))));
  }, [nodes, onNodePositionsChange]);

  useEffect(() => {
    const { nodes: initialNodes, edges: initialEdges } = aslToReactFlow(definition, {
      selectedStateName,
      onStateSelect,
      connectable,
      onStateContextMenu,
      onOpenNestedScope,
    });
    const edgesWithHandlers = initialEdges.map((edge) => ({
      ...edge,
      data: { ...(edge.data || {}), onContextMenu: onEdgeContextMenu },
    }));
    const { nodes: layoutedNodes, edges: layoutedEdges } = getLayoutedElements(initialNodes, edgesWithHandlers, layoutDirection);
    const savedPositionsChanged = lastInitialPositionsRef.current !== initialNodePositions;
    lastInitialPositionsRef.current = initialNodePositions;
    setNodes((currentNodes) => {
      const shouldRelayout = lastLayoutVersionRef.current !== layoutVersion;
      lastLayoutVersionRef.current = layoutVersion;
      if (shouldRelayout) {
        return layoutedNodes;
      }
      if (currentNodes.length === 0 || !preserveNodePositions) {
        return applySavedPositions(layoutedNodes, initialNodePositions);
      }
      if (savedPositionsChanged) {
        return applySavedPositions(layoutedNodes, initialNodePositions);
      }

      const currentPositionById = new Map(
        currentNodes.map((node) => [node.id, node.position] as const),
      );
      const nextIds = new Set(layoutedNodes.map((node) => node.id));
      const existingPositions = currentNodes
        .filter((node) => nextIds.has(node.id))
        .map((node) => node.position);

      return layoutedNodes.map((node, index) => {
        const currentPosition = currentPositionById.get(node.id);
        if (currentPosition) {
          return { ...node, position: currentPosition };
        }

        const position = nextOpenPosition(existingPositions, node.position, index, layoutDirection);
        existingPositions.push(position);
        return { ...node, position };
      });
    });
    setEdges(layoutedEdges);
  }, [connectable, definition, initialNodePositions, layoutDirection, layoutVersion, onEdgeContextMenu, onStateContextMenu, onOpenNestedScope, preserveNodePositions, selectedStateName, onStateSelect, setNodes, setEdges]);

  return (
    <div ref={viewerRef} className="relative h-full w-full bg-surface-lowest">
      <ReactFlow 
        nodes={nodes} 
        edges={edges} 
        onNodesChange={onNodesChange}
        onNodeDragStop={onNodePositionsChange ? handleNodeDragStop : undefined}
        onEdgesChange={onEdgesChange}
        onConnect={connectable ? handleConnect : undefined}
        nodesConnectable={connectable}
        isValidConnection={(connection) => Boolean(connection.source && connection.target && connection.source !== connection.target)}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        fitView 
        minZoom={0.45}
        maxZoom={1.1}
        fitViewOptions={{ padding: 0.22, maxZoom: 1.05 }}
        proOptions={{ hideAttribution: true }}
        defaultEdgeOptions={{ type: 'dataFlow' }}
      >
        <Background variant={BackgroundVariant.Dots} gap={24} size={1.4} color="rgba(135, 146, 172, 0.38)" />
        <FitViewOnKey fitKey={fitViewKey} />
        <CanvasFullscreenButton targetRef={viewerRef} />
        <CanvasControls />
      </ReactFlow>
    </div>
  );
}
