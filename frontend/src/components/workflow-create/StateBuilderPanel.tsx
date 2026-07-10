import { useEffect, useMemo, useRef, useState } from 'react';
import { AlertTriangle, Plus } from 'lucide-react';
import { getStateVisual } from '../../utils/stateVisuals';
import { StateEditorForm } from './StateEditorForm';
import {
  addState,
  danglingTargets,
  deleteState,
  renameState,
  stateTypeOf,
  updateState,
  STATE_TYPES,
  STATE_TYPE_DESCRIPTIONS,
  type AslDefinition,
  type AslState,
  type StateType,
} from './stateBuilder';

type Props = {
  definition: AslDefinition;
  onDefinitionChange: (definition: AslDefinition) => void;
  selectedStateName: string;
  onStateSelect: (stateName: string) => void;
  fieldClass: string;
  monoFieldClass: string;
};

export function StateBuilderPanel({
  definition,
  onDefinitionChange,
  selectedStateName,
  onStateSelect,
  fieldClass,
  monoFieldClass,
}: Props) {
  const [addMenuOpen, setAddMenuOpen] = useState(false);
  const addMenuRef = useRef<HTMLDivElement | null>(null);

  const states = useMemo(() => (
    definition.States && typeof definition.States === 'object' ? definition.States : {}
  ), [definition]);
  const stateNames = Object.keys(states);
  const dangling = useMemo(() => danglingTargets(definition), [definition]);

  const activeName = selectedStateName && selectedStateName in states
    ? selectedStateName
    : (definition.StartAt && definition.StartAt in states ? definition.StartAt : stateNames[0] || '');
  const activeState: AslState | undefined = activeName ? states[activeName] : undefined;

  useEffect(() => {
    if (!addMenuOpen) return;

    const handlePointerDown = (event: PointerEvent) => {
      if (addMenuRef.current?.contains(event.target as Node)) return;
      setAddMenuOpen(false);
    };

    document.addEventListener('pointerdown', handlePointerDown);
    return () => document.removeEventListener('pointerdown', handlePointerDown);
  }, [addMenuOpen]);

  const handleAddState = (type: StateType) => {
    const { definition: next, name } = addState(definition, type);
    onDefinitionChange(next);
    onStateSelect(name);
    setAddMenuOpen(false);
  };

  const handleRename = (nextName: string) => {
    onDefinitionChange(renameState(definition, activeName, nextName));
    onStateSelect(nextName);
  };

  const handleDelete = () => {
    const next = deleteState(definition, activeName);
    onDefinitionChange(next);
    onStateSelect(next.StartAt || Object.keys(next.States || {})[0] || '');
  };

  return (
    <div className="flex min-h-0 flex-1 overflow-hidden">
      <aside className="relative flex w-60 shrink-0 flex-col border-r border-border-subtle bg-surface-lowest" ref={addMenuRef}>
        <div className="border-b border-border-subtle p-3">
          <button
            type="button"
            onClick={() => setAddMenuOpen((open) => !open)}
            className="flex h-9 w-full items-center justify-center gap-1.5 rounded-DEFAULT border border-dashed border-border-subtle font-mono-sm text-[11px] text-on-surface-variant transition-colors hover:border-secondary hover:text-secondary"
          >
            <Plus size={13} />
            Add state
          </button>
        </div>

        {addMenuOpen && (
          <div className="absolute inset-x-3 top-[52px] z-30 max-h-[calc(100%-4rem)] overflow-y-auto rounded-DEFAULT border border-border-subtle bg-surface-container-highest shadow-lg">
            {STATE_TYPES.map((type) => {
              const visual = getStateVisual(type);
              return (
                <button
                  key={type}
                  type="button"
                  onClick={() => handleAddState(type)}
                  className="flex w-full items-start gap-2 px-3 py-2 text-left transition-colors hover:bg-surface-container"
                  title={STATE_TYPE_DESCRIPTIONS[type]}
                >
                  <span className={`material-symbols-outlined mt-0.5 text-[15px] ${visual.textClass}`}>{visual.iconName}</span>
                  <span>
                    <span className="block font-mono-sm text-[11px] text-on-surface">{type}</span>
                    <span className="block font-body-sm text-[10px] leading-snug text-on-surface-variant">
                      {STATE_TYPE_DESCRIPTIONS[type]}
                    </span>
                  </span>
                </button>
              );
            })}
          </div>
        )}

        <div className="min-h-0 flex-1 overflow-y-auto p-2">
          {stateNames.length === 0 && (
            <p className="px-2 py-3 font-body-sm text-[11px] text-on-surface-variant">
              No states yet. Add one to get started.
            </p>
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
                onClick={() => onStateSelect(stateName)}
                className={`group flex w-full items-center gap-2 rounded-DEFAULT px-2.5 py-2 text-left transition-colors ${isActive ? 'bg-surface-container-highest' : 'hover:bg-surface-container'}`}
              >
                <span className={`h-6 w-1 shrink-0 rounded-full ${visual.barClass}`}></span>
                <span className="min-w-0 flex-1">
                  <span className={`block truncate font-mono-sm text-[12px] ${isActive ? 'text-on-surface' : 'text-on-surface-variant group-hover:text-on-surface'}`}>
                    {stateName}
                  </span>
                  <span className="block font-mono-sm text-[9px] uppercase tracking-wide text-on-surface-variant/70">
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
      </aside>

      {activeState ? (
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
        />
      ) : (
        <div className="flex flex-1 items-center justify-center px-6 text-center font-mono-sm text-[12px] text-on-surface-variant">
          Add a state to start building the workflow.
        </div>
      )}
    </div>
  );
}
