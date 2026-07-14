import { useEffect, useMemo, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent, PointerEvent as ReactPointerEvent } from 'react';
import { AlertTriangle, Flag, Trash2, X } from 'lucide-react';
import { getStateVisual } from '../../utils/stateVisuals';
import { AslGraphViewer } from '../AslGraphViewer';
import { StateEditorForm, type TaskResourceOption } from './StateEditorForm';
import type { MachinePathSegment } from './nestedMachine';
import type { CanvasNodePositions } from '../../types/workflowCanvas';
import {
  addState,
  connectStates,
  disconnectStates,
  danglingTargets,
  deleteState,
  renameState,
  setChoiceDefaultTransition,
  stateTypeOf,
  updateState,
  STATE_TYPES,
  STATE_TYPE_DESCRIPTIONS,
  type AslDefinition,
  type AslState,
  type StateType,
  type TransitionKind,
  type TransitionRef,
} from './stateBuilder';

type ContextMenuState = {
  stateName: string;
  x: number;
  y: number;
};

type EdgeContextMenuState = TransitionRef & {
  x: number;
  y: number;
};

type Props = {
  definition: AslDefinition;
  onDefinitionChange: (definition: AslDefinition) => void;
  selectedStateName: string;
  onStateSelect: (stateName: string) => void;
  fieldClass: string;
  monoFieldClass: string;
  taskResourceOptions?: TaskResourceOption[];
  layoutVersion?: number;
  initialNodePositions?: CanvasNodePositions;
  onNodePositionsChange?: (positions: CanvasNodePositions) => void;
  onOpenNestedScope?: (segment: MachinePathSegment) => void;
};

export function StateCanvasBuilder({
  definition,
  onDefinitionChange,
  selectedStateName,
  onStateSelect,
  fieldClass,
  monoFieldClass,
  taskResourceOptions,
  layoutVersion = 0,
  initialNodePositions,
  onNodePositionsChange,
  onOpenNestedScope,
}: Props) {
  const [inspectorOpen, setInspectorOpen] = useState(false);
  const [connectionNotice, setConnectionNotice] = useState<{ tone: 'ok' | 'warn'; message: string } | null>(null);
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);
  const [edgeContextMenu, setEdgeContextMenu] = useState<EdgeContextMenuState | null>(null);
  const pointerDownRef = useRef<{ x: number; y: number } | null>(null);
  const states = useMemo(() => (
    definition.States && typeof definition.States === 'object' ? definition.States : {}
  ), [definition]);
  const stateNames = Object.keys(states);
  const dangling = useMemo(() => danglingTargets(definition), [definition]);
  const activeName = selectedStateName && selectedStateName in states ? selectedStateName : '';
  const activeState: AslState | undefined = activeName ? states[activeName] : undefined;

  const handleAddState = (type: StateType) => {
    const { definition: next, name } = addState(definition, type);
    onDefinitionChange(next);
    onStateSelect(name);
    setInspectorOpen(false);
    setContextMenu(null);
    setEdgeContextMenu(null);
  };

  const selectState = (stateName: string) => {
    onStateSelect(stateName);
    setInspectorOpen(true);
    setContextMenu(null);
    setEdgeContextMenu(null);
  };

  const handleConnectStates = (sourceName: string, targetName: string) => {
    const result = connectStates(definition, sourceName, targetName);
    if (result.changed) {
      onDefinitionChange(result.definition);
      onStateSelect(sourceName);
      setInspectorOpen(false);
    }
    if (result.message) {
      setConnectionNotice({ tone: result.changed ? 'ok' : 'warn', message: result.message });
    }
    setContextMenu(null);
    setEdgeContextMenu(null);
  };

  const handleRename = (nextName: string) => {
    if (!activeName) return;
    onDefinitionChange(renameState(definition, activeName, nextName));
    onStateSelect(nextName);
  };

  const handleDelete = () => {
    if (!activeName) return;
    const next = deleteState(definition, activeName);
    onDefinitionChange(next);
    onStateSelect('');
    setInspectorOpen(false);
    setContextMenu(null);
    setEdgeContextMenu(null);
  };

  const handleClearCanvas = () => {
    onDefinitionChange({ ...definition, StartAt: '', States: {} });
    onStateSelect('');
    setInspectorOpen(false);
    setContextMenu(null);
    setEdgeContextMenu(null);
    setConnectionNotice({ tone: 'ok', message: 'Canvas cleared.' });
  };

  const handleStateContextMenu = (stateName: string, event: ReactMouseEvent) => {
    onStateSelect(stateName);
    setInspectorOpen(false);
    setContextMenu({ stateName, x: event.clientX, y: event.clientY });
    setEdgeContextMenu(null);
  };

  const handleEdgeContextMenu = (transition: {
    sourceName: string;
    targetName: string;
    kind: unknown;
    index?: unknown;
    x: number;
    y: number;
  }) => {
    if (!['next', 'choice', 'default', 'catch'].includes(String(transition.kind))) {
      return;
    }
    setInspectorOpen(false);
    setContextMenu(null);
    setEdgeContextMenu({
      sourceName: transition.sourceName,
      targetName: transition.targetName,
      kind: transition.kind as TransitionKind,
      index: typeof transition.index === 'number' ? transition.index : undefined,
      x: transition.x,
      y: transition.y,
    });
  };

  const handleSetStart = (stateName: string) => {
    onDefinitionChange({ ...definition, StartAt: stateName });
    onStateSelect(stateName);
    setInspectorOpen(false);
    setContextMenu(null);
    setEdgeContextMenu(null);
    setConnectionNotice({ tone: 'ok', message: `${stateName} is now the start state.` });
  };

  const handleDeleteState = (stateName: string) => {
    const next = deleteState(definition, stateName);
    onDefinitionChange(next);
    if (activeName === stateName) {
      onStateSelect('');
      setInspectorOpen(false);
    }
    setContextMenu(null);
    setEdgeContextMenu(null);
    setConnectionNotice({ tone: 'ok', message: `${stateName} deleted.` });
  };

  const handleSetEdgeAsDefault = (transition: TransitionRef) => {
    const result = setChoiceDefaultTransition(definition, transition);
    if (result.changed) {
      onDefinitionChange(result.definition);
    }
    setEdgeContextMenu(null);
    if (result.message) {
      setConnectionNotice({ tone: result.changed ? 'ok' : 'warn', message: result.message });
    }
  };

  const handleDisconnectEdge = (transition: TransitionRef) => {
    const result = disconnectStates(definition, transition);
    if (result.changed) {
      onDefinitionChange(result.definition);
    }
    setEdgeContextMenu(null);
    if (result.message) {
      setConnectionNotice({ tone: result.changed ? 'ok' : 'warn', message: result.message });
    }
  };

  const handleCanvasPointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    pointerDownRef.current = { x: event.clientX, y: event.clientY };
  };

  const handleCanvasClick = (event: ReactMouseEvent<HTMLDivElement>) => {
    const pointerDown = pointerDownRef.current;
    pointerDownRef.current = null;

    if (pointerDown) {
      const dragged = Math.hypot(event.clientX - pointerDown.x, event.clientY - pointerDown.y) > 6;
      if (dragged) return;
    }

    if (window.getSelection()?.toString().trim()) return;

    setContextMenu(null);
    setEdgeContextMenu(null);
    setInspectorOpen(false);
  };

  useEffect(() => {
    if (!connectionNotice) return undefined;
    const timeout = window.setTimeout(() => setConnectionNotice(null), 2800);
    return () => window.clearTimeout(timeout);
  }, [connectionNotice]);

  return (
    <div
      className="relative min-h-0 flex-1 overflow-hidden bg-surface-lowest"
      onPointerDown={handleCanvasPointerDown}
      onClick={handleCanvasClick}
    >
      <AslGraphViewer
        definition={definition}
        selectedStateName={activeName}
        onStateSelect={selectState}
        connectable
        onConnectStates={handleConnectStates}
        preserveNodePositions
        initialNodePositions={initialNodePositions}
        onNodePositionsChange={onNodePositionsChange}
        layoutVersion={layoutVersion}
        onStateContextMenu={handleStateContextMenu}
        onEdgeContextMenu={handleEdgeContextMenu}
      />

      {connectionNotice && (
        <div
          className={`pointer-events-none absolute left-1/2 top-4 z-40 max-w-[360px] -translate-x-1/2 rounded-DEFAULT border px-3 py-2 text-center font-mono-sm text-[11px] shadow-[0_12px_40px_rgba(0,0,0,0.38)] backdrop-blur-xl ${
            connectionNotice.tone === 'ok'
              ? 'border-secondary/35 bg-secondary-container/85 text-secondary-fixed'
              : 'border-status-warning/35 bg-surface-container-highest/90 text-status-warning'
          }`}
          role="status"
        >
          {connectionNotice.message}
        </div>
      )}

      <aside
        className="absolute bottom-4 left-4 top-4 z-30 flex w-[250px] flex-col overflow-hidden rounded-DEFAULT border border-border-subtle bg-surface-base/90 shadow-[0_18px_60px_rgba(0,0,0,0.42)] backdrop-blur-xl"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="border-b border-border-subtle px-3 py-3">
          <div className="font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant">Add state</div>
          <div className="mt-3 grid grid-cols-2 gap-2">
            {STATE_TYPES.map((type) => {
              const visual = getStateVisual(type);
              return (
                <button
                  key={type}
                  type="button"
                  data-testid={`workflow-add-state-${type.toLowerCase()}`}
                  onClick={() => handleAddState(type)}
                  title={STATE_TYPE_DESCRIPTIONS[type]}
                  className={`flex h-[58px] flex-col items-start justify-center rounded-DEFAULT border px-2.5 text-left transition-colors hover:bg-surface-container ${visual.borderClass} ${visual.softBgClass}`}
                >
                  <span className={`material-symbols-outlined text-[15px] ${visual.textClass}`}>{visual.iconName}</span>
                  <span className="mt-1 font-mono-sm text-[11px] text-on-surface">{type}</span>
                </button>
              );
            })}
          </div>
        </div>

        <div className="flex min-h-0 flex-1 flex-col">
          <div className="flex items-center justify-between border-b border-border-subtle px-3 py-2">
            <div className="font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant">
              States
            </div>
            <button
              type="button"
              onClick={handleClearCanvas}
              disabled={stateNames.length === 0}
              className="flex h-7 items-center gap-1.5 rounded-DEFAULT border border-border-subtle px-2 font-mono-sm text-[10px] text-on-surface-variant transition-colors hover:border-status-error/45 hover:bg-status-error/10 hover:text-status-error disabled:cursor-not-allowed disabled:opacity-40"
              title="Clear canvas"
            >
              <Trash2 size={12} />
              Clear
            </button>
          </div>

        <div className="min-h-0 flex-1 overflow-y-auto p-2">
          {stateNames.length === 0 && (
            <div className="rounded-DEFAULT border border-dashed border-border-subtle px-3 py-6 text-center font-body-sm text-[11px] text-on-surface-variant">
              Add a state to start drawing the workflow.
            </div>
          )}
          {stateNames.map((stateName) => {
            const visual = getStateVisual(stateTypeOf(states[stateName]));
            const isActive = stateName === activeName;
            const isStart = definition.StartAt === stateName;
            const hasDangling = stateName in dangling;
            return (
              <button
                key={stateName}
                type="button"
                data-testid={`workflow-state-${stateName}`}
                onClick={() => selectState(stateName)}
                onContextMenu={(event) => {
                  event.preventDefault();
                  event.stopPropagation();
                  handleStateContextMenu(stateName, event);
                }}
                className={`group mt-1.5 flex w-full items-center gap-2 rounded-DEFAULT px-2.5 py-2 text-left transition-colors ${isActive ? 'bg-surface-container-highest text-on-surface' : 'text-on-surface-variant hover:bg-surface-container hover:text-on-surface'}`}
              >
                <span className={`h-7 w-1 shrink-0 rounded-full ${visual.barClass}`} />
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-mono-sm text-[12px]">{stateName}</span>
                  <span className="block font-mono-sm text-[9px] uppercase tracking-wide text-on-surface-variant/75">
                    {visual.label}
                    {isStart ? ' - start' : ''}
                  </span>
                </span>
                {hasDangling && (
                  <span title={`Missing target: ${dangling[stateName].join(', ')}`}>
                    <AlertTriangle size={13} className="shrink-0 text-status-warning" />
                  </span>
                )}
              </button>
            );
          })}
        </div>
        </div>
      </aside>

      {contextMenu && contextMenu.stateName in states && (
        <div
          className="fixed z-50 w-[178px] overflow-hidden rounded-DEFAULT border border-border-subtle bg-surface-container-highest py-1 shadow-[0_18px_50px_rgba(0,0,0,0.48)]"
          style={{ left: contextMenu.x, top: contextMenu.y }}
          onClick={(event) => event.stopPropagation()}
          onContextMenu={(event) => event.preventDefault()}
          role="menu"
        >
          <button
            type="button"
            onClick={() => handleSetStart(contextMenu.stateName)}
            disabled={definition.StartAt === contextMenu.stateName}
            className="flex h-9 w-full items-center gap-2 px-3 text-left font-mono-sm text-[11px] text-on-surface-variant transition-colors hover:bg-surface-container hover:text-on-surface disabled:cursor-not-allowed disabled:opacity-45"
            role="menuitem"
          >
            <Flag size={13} />
            Set as start
          </button>
          <button
            type="button"
            onClick={() => handleDeleteState(contextMenu.stateName)}
            className="flex h-9 w-full items-center gap-2 px-3 text-left font-mono-sm text-[11px] text-status-error transition-colors hover:bg-status-error/10"
            role="menuitem"
          >
            <Trash2 size={13} />
            Delete state
          </button>
        </div>
      )}

      {edgeContextMenu && edgeContextMenu.sourceName in states && edgeContextMenu.targetName in states && (
        <div
          className="fixed z-50 w-[218px] overflow-hidden rounded-DEFAULT border border-border-subtle bg-surface-container-highest py-1 shadow-[0_18px_50px_rgba(0,0,0,0.48)]"
          style={{ left: edgeContextMenu.x, top: edgeContextMenu.y }}
          onClick={(event) => event.stopPropagation()}
          onContextMenu={(event) => event.preventDefault()}
          role="menu"
        >
          <div className="border-b border-border-subtle px-3 py-2 font-mono-sm text-[10px] text-on-surface-variant">
            {edgeContextMenu.sourceName} to {edgeContextMenu.targetName}
          </div>
          {stateTypeOf(states[edgeContextMenu.sourceName]) === 'Choice' && edgeContextMenu.kind !== 'default' && (
            <button
              type="button"
              onClick={() => handleSetEdgeAsDefault(edgeContextMenu)}
              className="flex h-9 w-full items-center gap-2 px-3 text-left font-mono-sm text-[11px] text-on-surface-variant transition-colors hover:bg-surface-container hover:text-on-surface"
              role="menuitem"
            >
              <Flag size={13} />
              Set as Choice default
            </button>
          )}
          <button
            type="button"
            onClick={() => handleDisconnectEdge(edgeContextMenu)}
            className="flex h-9 w-full items-center gap-2 px-3 text-left font-mono-sm text-[11px] text-status-error transition-colors hover:bg-status-error/10"
            role="menuitem"
          >
            <Trash2 size={13} />
            Disconnect transition
          </button>
        </div>
      )}

      {activeState && inspectorOpen && (
        <section
          className="absolute bottom-4 right-4 top-4 z-30 flex w-[390px] min-w-0 flex-col overflow-hidden rounded-DEFAULT border border-border-subtle bg-surface-base/92 shadow-[0_18px_70px_rgba(0,0,0,0.5)] backdrop-blur-xl"
          onClick={(event) => event.stopPropagation()}
        >
          <div className="flex h-11 shrink-0 items-center justify-between border-b border-border-subtle px-4">
            <div className="min-w-0">
              <div className="truncate font-mono-sm text-[12px] text-on-surface">{activeName}</div>
              <div className="font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant">
                State inspector
              </div>
            </div>
            <button
              type="button"
              onClick={() => setInspectorOpen(false)}
              className="flex h-8 w-8 items-center justify-center rounded-DEFAULT text-on-surface-variant transition-colors hover:bg-surface-container hover:text-on-surface"
              aria-label="Close state inspector"
              title="Close"
            >
              <X size={15} />
            </button>
          </div>
          <StateEditorForm
            key={activeName}
            name={activeName}
            state={activeState}
            stateNames={stateNames}
            isStart={definition.StartAt === activeName}
            onChange={(nextState) => onDefinitionChange(updateState(definition, activeName, nextState))}
            onRename={handleRename}
            onDelete={handleDelete}
            onSetStart={() => onDefinitionChange({ ...definition, StartAt: activeName })}
            fieldClass={fieldClass}
            monoFieldClass={monoFieldClass}
            taskResourceOptions={taskResourceOptions}
            onOpenNestedScope={onOpenNestedScope}
          />
        </section>
      )}
    </div>
  );
}
