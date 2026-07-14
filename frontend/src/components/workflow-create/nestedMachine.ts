import type { CanvasNodePositions } from '../../types/workflowCanvas';
import { updateState, type AslDefinition, type AslState } from './stateBuilder.ts';

export type MachinePathSegment =
  | { kind: 'parallel'; stateName: string; branchIndex: number }
  | { kind: 'map'; stateName: string };

export type MachinePath = MachinePathSegment[];

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

export function isMachine(value: unknown): value is AslDefinition {
  return isRecord(value) && isRecord(value.States);
}

export function createNestedMachine(stateName: string): AslDefinition {
  return {
    StartAt: stateName,
    States: {
      [stateName]: { Type: 'Pass', End: true },
    },
  };
}

function nestedMachineForSegment(machine: AslDefinition, segment: MachinePathSegment): AslDefinition | null {
  const state = machine.States?.[segment.stateName];
  if (!state) return null;

  if (segment.kind === 'parallel') {
    const branches = Array.isArray(state.Branches) ? state.Branches : [];
    const branch = branches[segment.branchIndex];
    return isMachine(branch) ? branch : null;
  }

  return isMachine(state.ItemProcessor) ? state.ItemProcessor : null;
}

export function getMachineAtPath(root: AslDefinition, path: MachinePath): AslDefinition | null {
  let machine: AslDefinition = root;
  for (const segment of path) {
    const child = nestedMachineForSegment(machine, segment);
    if (!child) return null;
    machine = child;
  }
  return machine;
}

export function updateMachineAtPath(
  root: AslDefinition,
  path: MachinePath,
  replacement: AslDefinition,
): AslDefinition {
  if (path.length === 0) return replacement;

  const [segment, ...rest] = path;
  const state = root.States?.[segment.stateName];
  if (!state) return root;

  const child = nestedMachineForSegment(root, segment);
  if (!child) return root;
  const nextChild = updateMachineAtPath(child, rest, replacement);
  let nextState: AslState;

  if (segment.kind === 'parallel') {
    const branches = Array.isArray(state.Branches) ? [...state.Branches] : [];
    branches[segment.branchIndex] = nextChild;
    nextState = { ...state, Branches: branches };
  } else {
    nextState = { ...state, ItemProcessor: nextChild };
  }

  return updateState(root, segment.stateName, nextState);
}

function pointerEscape(value: string) {
  return value.replace(/~/g, '~0').replace(/\//g, '~1');
}

export function machinePointer(path: MachinePath) {
  return path.map((segment) => (
    segment.kind === 'parallel'
      ? `/States/${pointerEscape(segment.stateName)}/Branches/${segment.branchIndex}`
      : `/States/${pointerEscape(segment.stateName)}/ItemProcessor`
  )).join('');
}

export function machinePathKey(path: MachinePath) {
  return path.length === 0 ? 'root' : `@scope${machinePointer(path)}`;
}

export function canvasPositionKey(path: MachinePath, stateName: string) {
  if (path.length === 0) return stateName;
  return `@scope${machinePointer(path)}/States/${pointerEscape(stateName)}`;
}

export function canvasPositionsForScope(
  stored: CanvasNodePositions | undefined,
  path: MachinePath,
  machine: AslDefinition,
): CanvasNodePositions {
  const positions: CanvasNodePositions = {};
  for (const stateName of Object.keys(machine.States || {})) {
    const position = stored?.[canvasPositionKey(path, stateName)];
    if (position) positions[stateName] = position;
  }
  return positions;
}

export function mergeCanvasPositionsForScope(
  stored: CanvasNodePositions | undefined,
  path: MachinePath,
  positions: CanvasNodePositions,
): CanvasNodePositions {
  const next: CanvasNodePositions = { ...(stored || {}) };

  if (path.length === 0) {
    for (const key of Object.keys(next)) {
      if (!key.startsWith('@scope/')) delete next[key];
    }
  } else {
    const prefix = `@scope${machinePointer(path)}/States/`;
    for (const key of Object.keys(next)) {
      if (!key.startsWith(prefix)) continue;
      const remainder = key.slice(prefix.length);
      if (!remainder.includes('/')) delete next[key];
    }
  }

  for (const [stateName, position] of Object.entries(positions)) {
    next[canvasPositionKey(path, stateName)] = position;
  }
  return next;
}

export function collectCanvasPositionKeys(root: AslDefinition): Set<string> {
  const keys = new Set<string>();

  const visit = (machine: AslDefinition, path: MachinePath) => {
    for (const [stateName, state] of Object.entries(machine.States || {})) {
      keys.add(canvasPositionKey(path, stateName));
      if (state.Type === 'Parallel' && Array.isArray(state.Branches)) {
        state.Branches.forEach((branch: unknown, branchIndex: number) => {
          if (isMachine(branch)) {
            visit(branch, [...path, { kind: 'parallel', stateName, branchIndex }]);
          }
        });
      }
      if (state.Type === 'Map' && isMachine(state.ItemProcessor)) {
        visit(state.ItemProcessor, [...path, { kind: 'map', stateName }]);
      }
    }
  };

  visit(root, []);
  return keys;
}

export function filterCanvasPositionsForDefinition(
  root: AslDefinition,
  stored: CanvasNodePositions,
): CanvasNodePositions {
  const validKeys = collectCanvasPositionKeys(root);
  return Object.fromEntries(
    Object.entries(stored).filter(([key]) => validKeys.has(key)),
  );
}

export function scopeSegmentLabel(segment: MachinePathSegment) {
  return segment.kind === 'parallel'
    ? `Branch ${segment.branchIndex + 1}`
    : 'Item processor';
}
