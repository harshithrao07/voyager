import { useEffect } from 'react';
import { ReactFlow, Background, Controls, useNodesState, useEdgesState, Handle, Position } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import dagre from '@dagrejs/dagre';
import { aslToReactFlow } from '../utils/aslParser';

const dagreGraph = new dagre.graphlib.Graph();
dagreGraph.setDefaultEdgeLabel(() => ({}));

const getLayoutedElements = (nodes: any[], edges: any[], direction = 'TB') => {
  dagreGraph.setGraph({ rankdir: direction, nodesep: 100, ranksep: 100 });

  nodes.forEach((node) => {
    const width = node.id === 'Start' || node.id === 'End' ? 48 : 256;
    const height = node.id === 'Start' || node.id === 'End' ? 48 : 88;
    dagreGraph.setNode(node.id, { width, height });
  });

  edges.forEach((edge) => {
    dagreGraph.setEdge(edge.source, edge.target);
  });

  dagre.layout(dagreGraph);

  const newNodes = nodes.map((node) => {
    const nodeWithPosition = dagreGraph.node(node.id);
    const width = node.id === 'Start' || node.id === 'End' ? 48 : 256;
    const height = node.id === 'Start' || node.id === 'End' ? 48 : 88;
    return {
      ...node,
      targetPosition: 'top',
      sourcePosition: 'bottom',
      position: {
        x: nodeWithPosition.x - width / 2,
        y: nodeWithPosition.y - height / 2,
      },
    };
  });

  return { nodes: newNodes, edges };
};

// Custom Nodes to hide default ReactFlow styles and handle edges perfectly
const CustomDefaultNode = ({ data }: any) => (
  <>
    <Handle type="target" position={Position.Top} style={{ visibility: 'hidden' }} />
    {data.label}
    <Handle type="source" position={Position.Bottom} style={{ visibility: 'hidden' }} />
  </>
);

const CustomInputNode = ({ data }: any) => (
  <>
    {data.label}
    <Handle type="source" position={Position.Bottom} style={{ visibility: 'hidden' }} />
  </>
);

const CustomOutputNode = ({ data }: any) => (
  <>
    <Handle type="target" position={Position.Top} style={{ visibility: 'hidden' }} />
    {data.label}
  </>
);

const nodeTypes = {
  default: CustomDefaultNode,
  input: CustomInputNode,
  output: CustomOutputNode,
};

interface Props {
  definition: any;
}

export function AslGraphViewer({ definition }: Props) {
  const [nodes, setNodes, onNodesChange] = useNodesState<any>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<any>([]);

  useEffect(() => {
    const { nodes: initialNodes, edges: initialEdges } = aslToReactFlow(definition);
    const { nodes: layoutedNodes, edges: layoutedEdges } = getLayoutedElements(initialNodes, initialEdges);
    setNodes(layoutedNodes);
    setEdges(layoutedEdges);
  }, [definition, setNodes, setEdges]);

  return (
    <div style={{ width: '100%', height: '100%' }}>
      <ReactFlow 
        nodes={nodes} 
        edges={edges} 
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        nodeTypes={nodeTypes}
        fitView 
        attributionPosition="bottom-right"
      >
        <Background color="var(--border-subtle)" gap={24} size={1} />
        <Controls style={{ background: 'var(--bg-elevated)', border: '1px solid var(--border-subtle)', borderRadius: '4px' }} />
      </ReactFlow>
    </div>
  );
}
