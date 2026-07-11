import { useCallback, useEffect, useRef, useState } from 'react';
import type { RefObject } from 'react';
import { ReactFlow, useNodesState, useEdgesState, Handle, Position, useReactFlow, BaseEdge, Background, BackgroundVariant, getBezierPath } from '@xyflow/react';
import type { EdgeProps } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import dagre from '@dagrejs/dagre';
import { aslToReactFlow } from '../utils/aslParser';
import { LocateFixed, Maximize2, Minimize2, ZoomIn, ZoomOut } from 'lucide-react';

const dagreGraph = new dagre.graphlib.Graph();
dagreGraph.setDefaultEdgeLabel(() => ({}));

const getLayoutedElements = (nodes: any[], edges: any[], direction = 'LR') => {
  dagreGraph.setGraph({ rankdir: direction, nodesep: 92, ranksep: 92 });

  nodes.forEach((node) => {
    const width = 200;
    const height = 72;
    dagreGraph.setNode(node.id, { width, height });
  });

  edges.forEach((edge) => {
    dagreGraph.setEdge(edge.source, edge.target);
  });

  dagre.layout(dagreGraph);

  const newNodes = nodes.map((node) => {
    const nodeWithPosition = dagreGraph.node(node.id);
    const width = 200;
    const height = 72;
    return {
      ...node,
      targetPosition: 'left',
      sourcePosition: 'right',
      position: {
        x: nodeWithPosition.x - width / 2,
        y: nodeWithPosition.y - height / 2,
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

// Custom Nodes to hide default ReactFlow styles and handle edges perfectly
const CustomDefaultNode = ({ data }: any) => (
  <>
    <Handle type="target" position={Position.Left} style={{ visibility: 'hidden' }} />
    {data.label}
    <Handle type="source" position={Position.Right} style={{ visibility: 'hidden' }} />
  </>
);

const CustomInputNode = ({ data }: any) => (
  <>
    {data.label}
    <Handle type="source" position={Position.Right} style={{ visibility: 'hidden' }} />
  </>
);

const CustomOutputNode = ({ data }: any) => (
  <>
    <Handle type="target" position={Position.Left} style={{ visibility: 'hidden' }} />
    {data.label}
  </>
);

function DataFlowEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  data,
}: EdgeProps) {
  const [edgePath] = getBezierPath({
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

  return (
    <>
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
}

export function AslGraphViewer({ definition, selectedStateName, onStateSelect }: Props) {
  const viewerRef = useRef<HTMLDivElement>(null);
  const [nodes, setNodes, onNodesChange] = useNodesState<any>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<any>([]);

  useEffect(() => {
    const { nodes: initialNodes, edges: initialEdges } = aslToReactFlow(definition, {
      selectedStateName,
      onStateSelect,
    });
    const { nodes: layoutedNodes, edges: layoutedEdges } = getLayoutedElements(initialNodes, initialEdges);
    setNodes(layoutedNodes);
    setEdges(layoutedEdges);
  }, [definition, selectedStateName, onStateSelect, setNodes, setEdges]);

  return (
    <div ref={viewerRef} className="relative h-full w-full bg-surface-lowest">
      <ReactFlow 
        nodes={nodes} 
        edges={edges} 
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
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
        <CanvasFullscreenButton targetRef={viewerRef} />
        <CanvasControls />
      </ReactFlow>
    </div>
  );
}
