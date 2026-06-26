import { useEffect, useState } from 'react';
import { ReactFlow, useNodesState, useEdgesState, Handle, Position, useReactFlow, BaseEdge, getBezierPath } from '@xyflow/react';
import type { EdgeProps } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import dagre from '@dagrejs/dagre';
import { aslToReactFlow } from '../utils/aslParser';
import { Grid2X2, LocateFixed, ZoomIn, ZoomOut } from 'lucide-react';

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
  const stroke = isError ? '#f43f5e' : '#333333';

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        style={{
          stroke,
          strokeWidth: 2,
          strokeDasharray: isError ? '6 7' : '4 7',
          opacity: isError ? 0.65 : 0.9,
          animation: 'workflow-edge-dash 18s linear infinite',
        }}
      />
      {!isError && (
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
  const [gridLocked, setGridLocked] = useState(true);

  return (
    <div className="absolute bottom-6 left-1/2 z-30 flex w-fit -translate-x-1/2 items-center gap-element-gap-md rounded-full border border-border-muted bg-surface-container-highest/80 px-6 py-3 shadow-lg backdrop-blur-xl">
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
      <button onClick={() => setGridLocked((value) => !value)} className={`flex flex-col items-center gap-1 transition-all hover:scale-110 ${gridLocked ? 'text-on-surface opacity-60 hover:opacity-100' : 'text-status-accent'}`} aria-label="Grid lock">
        <Grid2X2 size={20} />
        <span className="text-mono-sm font-mono-sm text-[10px]">Grid Lock</span>
      </button>
    </div>
  );
}

interface Props {
  definition: any;
  selectedStateName?: string;
  onStateSelect: (stateName: string) => void;
}

export function AslGraphViewer({ definition, selectedStateName, onStateSelect }: Props) {
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
    <div className="relative h-full w-full bg-surface-lowest">
      <div className="pointer-events-none absolute inset-0 workflow-dot-grid"></div>
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
        <CanvasControls />
      </ReactFlow>
    </div>
  );
}
