import Editor from '@monaco-editor/react';
import { useEffect, useId, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react';
import { AlertCircle, ArrowRight, BookOpenText, Braces, ChevronRight, FileUp, FlaskConical, ListPlus, Loader2, PanelRightClose, PanelRightOpen, Plus, Save, ShieldAlert, Sliders, Sparkles, Undo2, Wand2, X } from 'lucide-react';
import { explainWorkflowDefinition } from '../../api';
import type { DefinitionStatus, WorkflowPreview } from './types';
import { useTemplateFilePicker, type TemplateImportHandler } from './useTemplateImport';
import { AslGraphViewer } from '../AslGraphViewer';
import { updateState, type AslDefinition } from './stateBuilder';
import { StateCanvasBuilder } from './StateCanvasBuilder';
import type { TaskResourceOption } from './StateEditorForm';
import {
  canvasPositionsForScope,
  createNestedMachine,
  getMachineAtPath,
  machinePathKey,
  mergeCanvasPositionsForScope,
  scopeSegmentLabel,
  updateMachineAtPath,
  type MachinePath,
  type MachinePathSegment,
} from './nestedMachine';
import { WorkflowPreviewPanel } from './WorkflowPreviewPanel';
import { WorkflowMetadataForm } from './WorkflowMetadataForm';
import { WorkflowDraftTestBench } from './WorkflowDraftTestBench';
import type { CanvasNodePositions } from '../../types/workflowCanvas';

type EditorView = 'code' | 'builder';

type DormantDiffLine = { kind: 'add' | 'remove' | 'context'; text: string };
type DormantSplitDiffRow = {
  left?: DormantDiffLine & { index: number };
  right?: DormantDiffLine & { index: number };
  context?: string;
  blockId?: number;
  firstInBlock?: boolean;
  collapsedContext?: number;
};

type SidebarTab = 'workflow' | 'ai';

type RevisionEditDetails = {
  baseRevision: number;
  recurring: boolean;
  baseRevisionActive: boolean;
  hasUnsavedChanges: boolean;
};

type Props = {
  definitionText: string;
  onDefinitionTextChange: (value: string) => void;
  definitionStatus: DefinitionStatus;
  definitionStats: WorkflowPreview;
  error: string | null;
  validationIssues: string[];
  saving: boolean;
  reviewingActivation: boolean;
  canSave: boolean;
  saveDisabledReasons: string[];
  onSave: () => void;
  onReviewActivation: () => void;
  onSaveWithoutActivation?: () => void;
  /** The workspace is already linked to a workflow, so Save creates another revision. */
  saveCreatesRevision?: boolean;
  revisionEdit?: RevisionEditDetails;
  onCancelRevisionEdit?: () => void;
  name: string;
  onNameChange: (value: string) => void;
  maxAttempts: number;
  onMaxAttemptsChange: (value: number) => void;
  idempotencyKey: string;
  onIdempotencyKeyChange: (value: string) => void;
  cronExpression: string;
  onCronExpressionChange: (value: string) => void;
  timezone: string;
  onTimezoneChange: (value: string) => void;
  fieldClass: string;
  monoFieldClass: string;
  taskResourceOptions?: TaskResourceOption[];
  reserveTopControlsSpace?: boolean;
  initialNodePositions?: CanvasNodePositions;
  onNodePositionsChange?: (positions: CanvasNodePositions) => void;
  onImportTemplate?: TemplateImportHandler;
  aiChatNode?: ReactNode;
  /** Arrive with the sidebar open on the AI tab, for when a conversation is already underway. */
  startInAiChat?: boolean;
};

export function ManualWorkflowEditor({
  definitionText,
  onDefinitionTextChange,
  definitionStatus,
  definitionStats,
  error,
  validationIssues,
  saving,
  reviewingActivation,
  canSave,
  saveDisabledReasons,
  onSave,
  onReviewActivation,
  onSaveWithoutActivation,
  saveCreatesRevision,
  revisionEdit,
  onCancelRevisionEdit,
  name,
  onNameChange,
  maxAttempts,
  onMaxAttemptsChange,
  idempotencyKey,
  onIdempotencyKeyChange,
  cronExpression,
  onCronExpressionChange,
  timezone,
  onTimezoneChange,
  fieldClass,
  monoFieldClass,
  taskResourceOptions,
  reserveTopControlsSpace,
  initialNodePositions,
  onNodePositionsChange,
  onImportTemplate,
  aiChatNode,
  startInAiChat,
}: Props) {
  const [selectedStateName, setSelectedStateName] = useState('');
  const [editorView, setEditorView] = useState<EditorView>('builder');
  const [sidebarTab, setSidebarTab] = useState<SidebarTab>(startInAiChat ? 'ai' : 'workflow');
  const [sidebarOpen, setSidebarOpen] = useState(Boolean(revisionEdit) || Boolean(startInAiChat));
  const [sidebarWidth, setSidebarWidth] = useState(360);
  const [layoutVersion, setLayoutVersion] = useState(0);
  const [testBenchOpen, setTestBenchOpen] = useState(false);
  const [machinePath, setMachinePath] = useState<MachinePath>([]);
  const [aiAuthoringAction, setAiAuthoringAction] = useState<'explain' | null>(null);
  const [aiAuthoringBusy, setAiAuthoringBusy] = useState(false);
  const [aiAuthoringResult, setAiAuthoringResult] = useState<{ title: string; lines: string[] } | null>(null);
  const [aiAuthoringError, setAiAuthoringError] = useState<string | null>(null);
  const aiAuthoringInstruction = '';
  const setAiAuthoringInstruction = (_value: string) => {};
  const [aiAuthoringPatch] = useState<null | { definition: AslDefinition; diff: DormantDiffLine[] }>(null);
  const [selectedDiffLines, setSelectedDiffLines] = useState<Set<number>>(new Set());
  const splitPatchRows: DormantSplitDiffRow[] = [];
  const [stagedPatch] = useState<null | { definition: AslDefinition | null; issues: string[]; parseError: string | null }>(null);
  const applyAiAuthoringPatch = () => {};
  const bodyRef = useRef<HTMLDivElement>(null);
  const saveDisabledTooltip = !canSave && saveDisabledReasons.length > 0
    ? `Cannot save yet:\n${saveDisabledReasons.map((reason) => `• ${reason}`).join('\n')}`
    : undefined;
  const validationTooltipId = useId();

  useEffect(() => {
    if (!saveCreatesRevision) return;
    setSidebarTab('workflow');
    setSidebarOpen(true);
  }, [saveCreatesRevision]);

  const uniqueValidationIssues = useMemo(() => (
    [...new Set(validationIssues.map((issue) => issue.trim()).filter(Boolean))]
  ), [validationIssues]);
  const definitionStatusDetails = useMemo(() => {
    if (uniqueValidationIssues.length > 0) return uniqueValidationIssues;
    if (!definitionStatus.valid && definitionStatus.message.trim()) {
      return [definitionStatus.message.trim()];
    }
    return [];
  }, [definitionStatus, uniqueValidationIssues]);
  const displayedDefinitionStatus = uniqueValidationIssues.length > 0
    ? `${uniqueValidationIssues.length} ASL validation ${uniqueValidationIssues.length === 1 ? 'issue' : 'issues'}`
    : definitionStatus.message;
  const definitionIsValid = definitionStatus.valid && uniqueValidationIssues.length === 0;
  const showDefinitionStatusDetails = !definitionIsValid && definitionStatusDetails.length > 0;

  const startSidebarResize = (event: ReactPointerEvent) => {
    event.preventDefault();
    const onMove = (moveEvent: PointerEvent) => {
      const rect = bodyRef.current?.getBoundingClientRect();
      if (!rect) return;
      setSidebarWidth(Math.min(620, Math.max(280, rect.right - moveEvent.clientX)));
    };
    const onUp = () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
  };

  const parsedDefinition = useMemo(() => {
    try {
      const parsed = JSON.parse(definitionText);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        return null;
      }
      const states = parsed.States && typeof parsed.States === 'object' && !Array.isArray(parsed.States)
        ? parsed.States
        : {};
      return { ...parsed, States: states };
    } catch {
      return null;
    }
  }, [definitionText]);

  const currentMachine = useMemo(() => (
    parsedDefinition ? getMachineAtPath(parsedDefinition, machinePath) : null
  ), [machinePath, parsedDefinition]);
  const currentScopeKey = machinePathKey(machinePath);
  const scopedNodePositions = useMemo(() => (
    currentMachine
      ? canvasPositionsForScope(initialNodePositions, machinePath, currentMachine)
      : {}
  ), [currentMachine, initialNodePositions, machinePath]);

  const navigateToMachine = (nextPath: MachinePath) => {
    if (!parsedDefinition) return;
    const machine = getMachineAtPath(parsedDefinition, nextPath);
    if (!machine) return;
    setMachinePath(nextPath);
    setSelectedStateName(machine.StartAt || Object.keys(machine.States || {})[0] || '');
    setTestBenchOpen(false);
  };

  const handleOpenNestedScope = (segment: MachinePathSegment) => {
    const nextPath = [...machinePath, segment];
    setMachinePath(nextPath);
    const nested = parsedDefinition ? getMachineAtPath(parsedDefinition, nextPath) : null;
    const createdStateName = segment.kind === 'parallel'
      ? `Branch${segment.branchIndex + 1}Start`
      : 'ProcessItem';
    setSelectedStateName(nested?.StartAt || Object.keys(nested?.States || {})[0] || createdStateName);
    setTestBenchOpen(false);
  };

  const handleDefinitionTextUpdate = (value: string) => {
    onDefinitionTextChange(value);
    try {
      const nextDefinition = JSON.parse(value) as AslDefinition;
      if (machinePath.length > 0 && !getMachineAtPath(nextDefinition, machinePath)) {
        setMachinePath([]);
        setSelectedStateName(nextDefinition.StartAt || Object.keys(nextDefinition.States || {})[0] || '');
      }
    } catch {
      // Keep the current scope while the code editor contains incomplete JSON.
    }
  };

  const handleBuilderChange = (nextMachine: AslDefinition) => {
    if (!parsedDefinition) return;
    const nextDefinition = updateMachineAtPath(parsedDefinition, machinePath, nextMachine);
    onDefinitionTextChange(JSON.stringify(nextDefinition, null, 2));
  };

  const handleScopedNodePositionsChange = (positions: CanvasNodePositions) => {
    onNodePositionsChange?.(mergeCanvasPositionsForScope(
      initialNodePositions,
      machinePath,
      positions,
    ));
  };

  const currentSegment = machinePath[machinePath.length - 1];
  const parentMachinePath = machinePath.slice(0, -1);
  const parentMachine = parsedDefinition && currentSegment
    ? getMachineAtPath(parsedDefinition, parentMachinePath)
    : null;
  const siblingBranches: unknown[] = currentSegment?.kind === 'parallel'
    ? (Array.isArray(parentMachine?.States?.[currentSegment.stateName]?.Branches)
      ? parentMachine.States[currentSegment.stateName].Branches
      : [])
    : [];

  const handleAddSiblingBranch = () => {
    if (!parsedDefinition || !parentMachine || currentSegment?.kind !== 'parallel') return;
    const ownerState = parentMachine.States?.[currentSegment.stateName];
    if (!ownerState) return;
    const branches = Array.isArray(ownerState.Branches) ? ownerState.Branches : [];
    const branchIndex = branches.length;
    const nextParent = updateState(parentMachine, currentSegment.stateName, {
      ...ownerState,
      Branches: [...branches, createNestedMachine(`Branch${branchIndex + 1}Start`)],
    });
    const nextDefinition = updateMachineAtPath(parsedDefinition, parentMachinePath, nextParent);
    onDefinitionTextChange(JSON.stringify(nextDefinition, null, 2));
    setMachinePath([
      ...parentMachinePath,
      { kind: 'parallel', stateName: currentSegment.stateName, branchIndex },
    ]);
    setSelectedStateName(`Branch${branchIndex + 1}Start`);
  };

  const handleImportedTemplate: TemplateImportHandler = (raw, fileName) => {
    const imported = onImportTemplate?.(raw, fileName);
    if (imported === false) return imported;
    setMachinePath([]);
    try {
      const parsed = JSON.parse(raw) as AslDefinition;
      setSelectedStateName(parsed.StartAt || Object.keys(parsed.States || {})[0] || '');
    } catch {
      setSelectedStateName('');
    }
    setTestBenchOpen(false);
    setLayoutVersion((version) => version + 1);
    return imported;
  };

  const { openTemplatePicker, templateFileInput } = useTemplateFilePicker(handleImportedTemplate);

  const handleFormat = () => {
    try {
      const parsed = JSON.parse(definitionText);
      onDefinitionTextChange(JSON.stringify(parsed, null, 2));
    } catch {
      // Keep invalid JSON untouched; the canvas layout can still be reset if a previous parse exists.
    }
    onNodePositionsChange?.({});
    setLayoutVersion((version) => version + 1);
  };

  const openAiAuthoring = () => {
    setAiAuthoringAction('explain');
    setAiAuthoringResult(null);
    setAiAuthoringError(null);
  };

  const runAiAuthoring = async () => {
    if (!parsedDefinition || !aiAuthoringAction) return;
    setAiAuthoringBusy(true);
    setAiAuthoringError(null);
    try {
      const response = await explainWorkflowDefinition(parsedDefinition);
      setAiAuthoringResult({
        title: 'Workflow explanation',
        lines: [response.summary, ...response.stateDetails],
      });
    } catch (authoringError) {
      setAiAuthoringError(authoringError instanceof Error ? authoringError.message : 'AI authoring failed.');
    } finally {
      setAiAuthoringBusy(false);
    }
  };

  const viewToggle = (
    <div className="flex items-center gap-1 rounded-DEFAULT border border-border-subtle p-0.5">
      {([
        { view: 'builder', label: 'Builder', icon: <ListPlus size={12} /> },
        { view: 'code', label: 'Code', icon: <Braces size={12} /> },
      ] as const).map(({ view, label, icon }) => (
        <button
          key={view}
          type="button"
          data-testid={`workflow-editor-${view}`}
          onClick={() => setEditorView(view)}
          className={`flex h-7 items-center gap-1.5 rounded-[3px] px-2.5 font-mono-sm text-[11px] transition-colors ${editorView === view
            ? 'bg-surface-container-highest text-on-surface'
            : 'text-on-surface-variant hover:text-on-surface'}`}
        >
          {icon}
          {label}
        </button>
      ))}
    </div>
  );

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-transparent">
      {templateFileInput}
      <div ref={bodyRef} className="flex min-h-0 flex-1 overflow-hidden">
        <main className="relative flex min-h-0 min-w-0 flex-1 flex-col bg-surface-base">
          <div className="flex min-h-0 flex-1 flex-col">
          <div className="flex h-14 shrink-0 items-center border-b border-border-subtle bg-surface-base px-6">
            <div className="flex items-center gap-4">
              <div className="flex items-center gap-2 font-mono-sm text-[13px] text-on-surface">
                <span className="material-symbols-outlined text-[18px]">description</span>
                {revisionEdit ? `revision-${revisionEdit.baseRevision}.json` : 'definition.json'}
                {revisionEdit && (
                  <span className="rounded-full border border-status-info/35 bg-status-info/10 px-2 py-0.5 text-[9px] uppercase tracking-[0.08em] text-status-info">
                    New revision
                  </span>
                )}
              </div>
              {revisionEdit && onCancelRevisionEdit && (
                <button
                  type="button"
                  onClick={onCancelRevisionEdit}
                  className="flex h-8 items-center gap-1.5 rounded-DEFAULT border border-border-subtle px-2.5 font-mono-sm text-[11px] text-on-surface-variant transition-colors hover:border-secondary hover:text-secondary"
                >
                  <span className="material-symbols-outlined text-[15px]">arrow_back</span>
                  Back
                </button>
              )}
              {viewToggle}
              {onImportTemplate && (
                <button
                  type="button"
                  data-testid="workflow-import-template"
                  onClick={openTemplatePicker}
                  className="flex h-8 items-center gap-1.5 rounded-DEFAULT border border-border-subtle px-3 font-mono-sm text-[11px] text-on-surface-variant transition-colors hover:border-secondary hover:text-secondary"
                  title="Replace the definition with an ASL JSON template"
                >
                  <FileUp size={13} />
                  Import
                </button>
              )}
              <button
                type="button"
                onClick={handleFormat}
                className="flex h-8 items-center gap-1.5 rounded-DEFAULT border border-border-subtle px-3 font-mono-sm text-[11px] text-on-surface-variant transition-colors hover:border-secondary hover:text-secondary"
                title="Format JSON and canvas"
              >
                <Wand2 size={13} />
                Format
              </button>
              <button
                type="button"
                onClick={() => setTestBenchOpen((open) => !open)}
                disabled={machinePath.length > 0}
                className={`flex h-8 items-center gap-1.5 rounded-DEFAULT border px-3 font-mono-sm text-[11px] transition-colors ${testBenchOpen
                  ? 'border-secondary/50 bg-secondary-container/35 text-secondary'
                  : 'border-border-subtle text-on-surface-variant hover:border-secondary hover:text-secondary'} disabled:cursor-not-allowed disabled:opacity-40`}
                title={machinePath.length > 0
                  ? 'Return to the workflow scope to test draft states'
                  : 'Test draft states without saving an execution'}
              >
                {testBenchOpen ? <X size={13} /> : <FlaskConical size={13} />}
                {testBenchOpen ? 'Close test' : 'Test draft'}
              </button>
              <button
                type="button"
                onClick={openAiAuthoring}
                disabled={!parsedDefinition}
                className="flex h-8 items-center gap-1.5 rounded-DEFAULT border border-border-subtle px-3 font-mono-sm text-[11px] text-on-surface-variant transition-colors hover:border-secondary hover:text-secondary disabled:opacity-40"
                title="Explain this workflow with AI"
              >
                <BookOpenText size={13} />
                Explain
              </button>
              <button
                type="button"
                data-testid="workflow-ai-activation-review"
                onClick={onReviewActivation}
                disabled={reviewingActivation || saving || !definitionStatus.valid || validationIssues.length > 0}
                className="flex h-8 items-center gap-1.5 rounded-DEFAULT border border-status-warning/45 bg-status-warning/5 px-3 font-mono-sm text-[11px] text-status-warning transition-colors hover:bg-status-warning/10 disabled:cursor-not-allowed disabled:opacity-40"
                title={!definitionStatus.valid || validationIssues.length > 0
                  ? 'Resolve ASL validation issues before requesting an AI review'
                  : 'Ask the default Chat model to review activation risks'}
              >
                {reviewingActivation
                  ? <Loader2 className="animate-spin" size={13} />
                  : <ShieldAlert size={13} />}
                {reviewingActivation ? 'Reviewing...' : 'AI review'}
              </button>
            </div>
          </div>
          <div className="flex min-h-9 shrink-0 items-center gap-x-4 gap-y-1 border-b border-border-subtle bg-surface-base px-6 py-2 font-mono-sm text-[12px] text-on-surface-variant">
            <span>{definitionStats.stateCount} states</span>
            <span>{definitionStats.taskCount} tasks</span>
            <div className="group relative min-w-0 flex-1">
              <span
                data-testid="workflow-definition-status"
                className={`block truncate rounded-[3px] outline-none ${definitionIsValid
                  ? 'text-secondary'
                  : 'cursor-help text-status-error focus-visible:ring-1 focus-visible:ring-status-error/60'}`}
                title={showDefinitionStatusDetails ? undefined : displayedDefinitionStatus}
                tabIndex={showDefinitionStatusDetails ? 0 : undefined}
                aria-describedby={showDefinitionStatusDetails ? validationTooltipId : undefined}
                aria-live="polite"
              >
                {displayedDefinitionStatus}
              </span>
              {showDefinitionStatusDetails && (
                <div
                  id={validationTooltipId}
                  role="tooltip"
                  data-testid="workflow-definition-issues-tooltip"
                  className="invisible absolute left-0 top-full z-[80] mt-2 w-[min(36rem,calc(100vw-3rem))] translate-y-1 overflow-hidden rounded-DEFAULT border border-status-error/30 bg-surface-container-highest text-on-surface opacity-0 shadow-[0_18px_55px_rgba(0,0,0,0.42)] transition-[opacity,transform,visibility] duration-150 group-hover:visible group-hover:translate-y-0 group-hover:opacity-100 group-focus-within:visible group-focus-within:translate-y-0 group-focus-within:opacity-100"
                >
                  <div className="flex items-center justify-between gap-3 border-b border-status-error/20 bg-status-error/10 px-4 py-3">
                    <span className="flex items-center gap-2 font-mono-sm text-[11px] font-semibold uppercase tracking-[0.08em] text-status-error">
                      <AlertCircle size={14} />
                      ASL validation {definitionStatusDetails.length === 1 ? 'issue' : 'issues'}
                    </span>
                    <span className="rounded-full border border-status-error/30 bg-status-error/10 px-2 py-0.5 font-mono-sm text-[10px] text-status-error">
                      {definitionStatusDetails.length}
                    </span>
                  </div>
                  <ol className="max-h-[min(20rem,60vh)] list-decimal space-y-2 overflow-y-auto px-4 py-3 pl-9 text-[11px] leading-5 text-on-surface-variant marker:font-mono-sm marker:text-status-error">
                    {definitionStatusDetails.map((issue) => (
                      <li key={issue} className="break-words pl-1">{issue}</li>
                    ))}
                  </ol>
                </div>
              )}
            </div>
          </div>
          {parsedDefinition && currentMachine && machinePath.length > 0 && (
            <div className="flex min-h-12 shrink-0 items-center gap-3 border-b border-secondary/20 bg-secondary-container/[0.08] px-5">
              <button
                type="button"
                onClick={() => navigateToMachine([])}
                className="flex h-8 items-center gap-1.5 rounded-DEFAULT px-2 font-mono-sm text-[10px] text-on-surface-variant transition-colors hover:bg-surface-container hover:text-on-surface"
              >
                <span className="material-symbols-outlined text-[15px]">account_tree</span>
                Workflow
              </button>
              {machinePath.map((segment, index) => (
                <div key={`${segment.stateName}-${index}`} className="flex min-w-0 items-center gap-2">
                  <ChevronRight size={12} className="shrink-0 text-on-surface-variant/55" />
                  <button
                    type="button"
                    onClick={() => navigateToMachine(machinePath.slice(0, index + 1))}
                    className={`max-w-[190px] truncate rounded-DEFAULT px-2 py-1.5 font-mono-sm text-[10px] transition-colors ${index === machinePath.length - 1
                      ? 'bg-secondary-container/35 text-secondary'
                      : 'text-on-surface-variant hover:bg-surface-container hover:text-on-surface'}`}
                    title={`${segment.stateName} · ${scopeSegmentLabel(segment)}`}
                  >
                    {segment.stateName} · {scopeSegmentLabel(segment)}
                  </button>
                </div>
              ))}
              <span className="ml-auto shrink-0 font-mono-sm text-[9px] uppercase tracking-[0.12em] text-secondary/75">
                Closed scope · {Object.keys(currentMachine.States || {}).length} states
              </span>
              {currentSegment?.kind === 'parallel' && (
                <div className="flex shrink-0 items-center gap-1 rounded-DEFAULT border border-border-subtle bg-surface-container-lowest p-1">
                  {siblingBranches.map((_, branchIndex) => (
                    <button
                      key={branchIndex}
                      type="button"
                      onClick={() => navigateToMachine([
                        ...parentMachinePath,
                        { kind: 'parallel', stateName: currentSegment.stateName, branchIndex },
                      ])}
                      className={`h-6 rounded-[3px] px-2 font-mono-sm text-[9px] transition-colors ${branchIndex === currentSegment.branchIndex
                        ? 'bg-secondary-container/40 text-secondary'
                        : 'text-on-surface-variant hover:text-on-surface'}`}
                    >
                      Branch {branchIndex + 1}
                    </button>
                  ))}
                  <button
                    type="button"
                    onClick={handleAddSiblingBranch}
                    className="flex h-6 w-6 items-center justify-center rounded-[3px] text-on-surface-variant transition-colors hover:bg-secondary-container/25 hover:text-secondary"
                    aria-label="Add parallel branch"
                    title="Add branch"
                  >
                    <Plus size={11} />
                  </button>
                </div>
              )}
            </div>
          )}
          <div className="flex min-h-0 flex-1 flex-col">
            {editorView === 'code' ? (
              <div className="grid min-h-0 flex-1 grid-cols-[minmax(360px,46%)_minmax(420px,1fr)]">
                <div className="min-h-0 border-r border-border-subtle">
                  <Editor
                    height="100%"
                    defaultLanguage="json"
                    theme="vs-dark"
                    value={definitionText}
                    onChange={(value) => handleDefinitionTextUpdate(value || '')}
                    options={{
                      minimap: { enabled: false },
                      scrollBeyondLastLine: false,
                      fontSize: 14,
                      fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
                      wordWrap: 'on',
                      tabSize: 2,
                      lineNumbersMinChars: 3,
                      padding: { top: 16, bottom: 16 },
                    }}
                  />
                </div>
                <div className="flex min-h-0 flex-col">
                  <div className="flex h-10 shrink-0 items-center gap-2 border-b border-border-subtle bg-surface-base px-4 font-mono-sm text-[12px] text-on-surface">
                    <span className="material-symbols-outlined text-[17px]">account_tree</span>
                    canvas
                  </div>
                  <div className="min-h-0 flex-1">
                    {currentMachine ? (
                      <AslGraphViewer
                        definition={currentMachine}
                        selectedStateName={selectedStateName}
                        onStateSelect={setSelectedStateName}
                        preserveNodePositions
                        initialNodePositions={scopedNodePositions}
                        onNodePositionsChange={handleScopedNodePositionsChange}
                        layoutVersion={layoutVersion}
                        onOpenNestedScope={handleOpenNestedScope}
                        fitViewKey={currentScopeKey}
                      />
                    ) : (
                      <div className="flex h-full items-center justify-center bg-surface-lowest px-6 text-center font-mono-sm text-[12px] text-on-surface-variant">
                        Fix the definition JSON to render the canvas.
                      </div>
                    )}
                  </div>
                </div>
              </div>
            ) : currentMachine ? (
              <StateCanvasBuilder
                key={currentScopeKey}
                definition={currentMachine}
                onDefinitionChange={handleBuilderChange}
                selectedStateName={selectedStateName}
                onStateSelect={setSelectedStateName}
                fieldClass={fieldClass}
                monoFieldClass={monoFieldClass}
                taskResourceOptions={taskResourceOptions}
                layoutVersion={layoutVersion}
                initialNodePositions={scopedNodePositions}
                onNodePositionsChange={handleScopedNodePositionsChange}
                onOpenNestedScope={handleOpenNestedScope}
                fitViewKey={currentScopeKey}
              />
            ) : (
              <div className="flex flex-1 items-center justify-center px-6 text-center font-mono-sm text-[12px] text-on-surface-variant">
                The definition JSON cannot be parsed. Switch to Code view to fix it before using the builder.
              </div>
            )}
          </div>
          {testBenchOpen && parsedDefinition && machinePath.length === 0 && (
            <WorkflowDraftTestBench
              definition={parsedDefinition}
              selectedStateName={selectedStateName}
              onStateSelect={setSelectedStateName}
            />
          )}
          </div>

          {!sidebarOpen && (
            <button
              type="button"
              data-testid="workflow-show-panel"
              onClick={() => setSidebarOpen(true)}
              title="Show panel"
              className="absolute right-0 top-1/2 z-20 hidden -translate-y-1/2 items-center rounded-l-DEFAULT border border-r-0 border-border-subtle bg-surface-container-highest px-1.5 py-3 text-on-surface-variant shadow-lg transition-colors hover:text-on-surface xl:flex"
            >
              <PanelRightOpen size={16} />
            </button>
          )}
        </main>

        {sidebarOpen && (
          <div
            role="separator"
            aria-orientation="vertical"
            onPointerDown={startSidebarResize}
            className="group hidden w-1.5 shrink-0 cursor-col-resize items-center justify-center bg-border-subtle/40 transition-colors hover:bg-primary/50 xl:flex"
          >
            <span className="h-8 w-0.5 rounded-full bg-border-subtle transition-colors group-hover:bg-primary" />
          </div>
        )}

        {sidebarOpen && (
        <aside
          className="hidden min-h-0 shrink-0 flex-col border-l border-border-subtle bg-surface-base xl:flex"
          style={{ width: sidebarWidth }}
        >
          <div className={`flex shrink-0 items-center justify-between gap-2 border-b border-border-subtle px-4 py-3 ${reserveTopControlsSpace ? 'pt-[82px]' : ''}`}>
            {aiChatNode ? (
              <div className="flex min-w-0 items-center gap-1 rounded-DEFAULT border border-border-subtle p-0.5">
                {([
                  { tab: 'workflow', label: 'Workflow', icon: <Sliders size={12} /> },
                  { tab: 'ai', label: 'AI Generator', icon: <Sparkles size={12} /> },
                ] as const).map(({ tab, label, icon }) => (
                  <button
                    key={tab}
                    type="button"
                    data-testid={`workflow-sidebar-${tab}`}
                    onClick={() => setSidebarTab(tab)}
                    className={`flex h-7 min-w-0 items-center gap-1.5 rounded-[3px] px-2.5 font-mono-sm text-[11px] transition-colors ${sidebarTab === tab
                      ? 'bg-surface-container-highest text-on-surface'
                      : 'text-on-surface-variant hover:text-on-surface'}`}
                  >
                    {icon}
                    <span className="truncate">{label}</span>
                  </button>
                ))}
              </div>
            ) : (
              <span className="font-mono-sm text-[11px] uppercase tracking-[0.08em] text-on-surface-variant">Workflow</span>
            )}
            <button
              type="button"
              onClick={() => setSidebarOpen(false)}
              title="Collapse panel"
              className="flex h-7 w-7 shrink-0 items-center justify-center rounded-DEFAULT text-on-surface-variant transition-colors hover:bg-surface-container hover:text-on-surface"
            >
              <PanelRightClose size={16} />
            </button>
          </div>

          {sidebarTab === 'ai' && aiChatNode ? aiChatNode : (
          <div className="min-h-0 flex-1 overflow-y-auto">
          <div className="border-b border-border-subtle p-6">
            {revisionEdit?.recurring && onSaveWithoutActivation && (
              <div title={saveDisabledTooltip} className="mb-2">
                <button
                  type="button"
                  data-testid="workflow-save-without-activation"
                  onClick={onSaveWithoutActivation}
                  disabled={!canSave}
                  className="flex h-10 w-full items-center justify-center gap-2 rounded-DEFAULT border border-border-subtle bg-surface-container-low px-4 font-body-sm text-[12px] font-medium text-on-surface transition-colors hover:border-secondary/55 hover:text-secondary disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {saving ? <Loader2 className="animate-spin" size={16} /> : <Save size={16} />}
                  Save revision
                </button>
              </div>
            )}
            <div title={saveDisabledTooltip} data-testid="workflow-save-tooltip">
              <button
                type="button"
                data-testid="workflow-save"
                onClick={onSave}
                disabled={!canSave}
                className="flex h-10 w-full items-center justify-center gap-2 rounded-DEFAULT bg-primary px-4 font-body-sm text-[12px] font-medium text-on-primary shadow-[0_12px_30px_rgba(242,121,90,0.18)] transition-colors hover:bg-primary-fixed-dim disabled:cursor-not-allowed disabled:opacity-50"
              >
                {saving ? <Loader2 className="animate-spin" size={16} /> : <Save size={16} />}
                {revisionEdit?.recurring
                  ? 'Save & activate'
                  : revisionEdit
                    ? 'Save revision'
                    : saveCreatesRevision
                      ? 'Save new revision'
                      : 'Save workflow'}
              </button>
            </div>
            {saveCreatesRevision && !revisionEdit && (
              <p
                data-testid="workflow-save-revision-note"
                className="mt-2 text-center font-mono-sm text-[10px] leading-4 text-on-surface-variant"
              >
                Creates a new immutable revision of the linked workflow.
              </p>
            )}
            {revisionEdit && !revisionEdit.hasUnsavedChanges && (
              <p className="mt-2 text-center font-mono-sm text-[10px] text-on-surface-variant">
                Change the definition or canvas before saving.
              </p>
            )}
            <WorkflowPreviewPanel
              preview={definitionStats}
              className="-mx-6 mt-6 border-t"
            />

            {(error || validationIssues.length > 0 || !definitionStatus.valid) ? (
              <div className="mt-5 rounded-DEFAULT border border-status-error/25 bg-status-error/10 p-4 text-body-sm text-status-error">
                <div className="flex items-start gap-2">
                  <AlertCircle className="mt-0.5 shrink-0" size={16} />
                  <div>
                    {error || definitionStatus.message}
                    {validationIssues.length > 0 && (
                      <ul className="mt-2 list-disc space-y-1 pl-4">
                        {validationIssues.map((issue) => (
                          <li key={issue}>{issue}</li>
                        ))}
                      </ul>
                    )}
                  </div>
                </div>
              </div>
            ) : definitionStats.stateCount === 0 ? (
              <div className="mt-5 rounded-DEFAULT border border-border-subtle bg-surface-container-low p-4 text-body-sm text-on-surface-variant">
                <div className="flex items-start gap-2">
                  <ListPlus className="mt-0.5 shrink-0" size={16} />
                  <div>Add at least one state to build your workflow.</div>
                </div>
              </div>
            ) : (
              <div className="mt-5 rounded-DEFAULT border border-secondary/35 bg-secondary-container/45 p-4 text-body-sm text-secondary-fixed">
                {saveCreatesRevision && !revisionEdit
                  ? 'Ready to create a new immutable revision of the linked workflow.'
                  : revisionEdit
                  ? revisionEdit.recurring
                    ? 'Ready to save as a draft revision or save and activate it.'
                    : 'Ready to save. Manual workflow revisions activate immediately.'
                  : cronExpression.trim()
                    ? 'Ready to save. Activate the schedule from workflow details.'
                    : 'Ready to save and execute manually.'}
                <span className="mt-1 block text-[11px] text-secondary-fixed/70">
                  ASL structure, graph, JSONata placement, and runtime-support checks pass. The server performs the final expression parse when saved.
                </span>
              </div>
            )}
          </div>
          <div className="p-8">
            {revisionEdit ? (
              <div>
                <div className="font-mono-sm text-[11px] uppercase tracking-[0.08em] text-on-surface-variant">
                  Revision source
                </div>
                <div className="mt-4 overflow-hidden rounded-DEFAULT border border-border-subtle bg-surface-container-low/55">
                  <div className="border-b border-border-subtle px-4 py-3">
                    <div className="text-[10px] uppercase tracking-[0.08em] text-on-surface-variant">Workflow</div>
                    <div className="mt-1 text-body-sm font-medium text-on-surface">{name}</div>
                  </div>
                  <div className="grid grid-cols-2 divide-x divide-border-subtle">
                    <div className="px-4 py-3">
                      <div className="text-[10px] uppercase tracking-[0.08em] text-on-surface-variant">Based on</div>
                      <div className="mt-1 font-mono-sm text-[11px] text-on-surface">
                        Rev {revisionEdit.baseRevision} · {revisionEdit.baseRevisionActive ? 'Active' : 'Inactive'}
                      </div>
                    </div>
                    <div className="px-4 py-3">
                      <div className="text-[10px] uppercase tracking-[0.08em] text-on-surface-variant">Schedule</div>
                      <div className="mt-1 font-mono-sm text-[11px] text-on-surface">
                        {revisionEdit.recurring ? 'Recurring' : 'Manual'}
                      </div>
                    </div>
                  </div>
                </div>
                <p className="mt-3 text-[11px] leading-relaxed text-on-surface-variant">
                  The source revision stays unchanged. Workflow name, schedule, and retry attempts are managed separately from definition revisions.
                </p>
              </div>
            ) : (
              <WorkflowMetadataForm
                name={name}
                onNameChange={onNameChange}
                maxAttempts={maxAttempts}
                onMaxAttemptsChange={onMaxAttemptsChange}
                idempotencyKey={idempotencyKey}
                onIdempotencyKeyChange={onIdempotencyKeyChange}
                cronExpression={cronExpression}
                onCronExpressionChange={onCronExpressionChange}
                timezone={timezone}
                onTimezoneChange={onTimezoneChange}
                fieldClass={fieldClass}
                monoFieldClass={monoFieldClass}
              />
            )}
          </div>
          </div>
          )}
        </aside>
        )}
      </div>
      {aiAuthoringAction && (
        <div
          className="fixed inset-0 z-[120] flex items-center justify-center bg-black/60 p-6 backdrop-blur-sm"
          role="dialog"
          aria-modal="true"
          aria-label="AI workflow authoring"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget && !aiAuthoringBusy) setAiAuthoringAction(null);
          }}
        >
          <section className={`w-full overflow-hidden rounded-DEFAULT border border-border-subtle bg-surface-container-highest shadow-2xl ${aiAuthoringPatch ? 'max-w-4xl' : 'max-w-xl'}`}>
            <header className="flex items-center justify-between border-b border-border-subtle px-5 py-4">
              <div>
                <div className="font-mono-sm text-[12px] text-on-surface">
                  Explain workflow
                </div>
                <p className="mt-1 text-[11px] text-on-surface-variant">
                  Produces a read-only behavioral summary.
                </p>
              </div>
              <button
                type="button"
                onClick={() => setAiAuthoringAction(null)}
                disabled={aiAuthoringBusy}
                className="flex h-8 w-8 items-center justify-center rounded-DEFAULT text-on-surface-variant hover:bg-surface-container disabled:opacity-40"
                aria-label="Close AI authoring"
              >
                <X size={15} />
              </button>
            </header>
            <div className="space-y-4 p-5">
              {false && !aiAuthoringResult && (
                <label className="block">
                  <span className="mb-2 block font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant">
                    Describe the change
                  </span>
                  <textarea
                    autoFocus
                    value={aiAuthoringInstruction}
                    onChange={(event) => setAiAuthoringInstruction(event.target.value)}
                    maxLength={4000}
                    rows={5}
                    placeholder="Retry three times with exponential backoff, then catch failures and send an email."
                    className={`${monoFieldClass} w-full resize-y`}
                  />
                </label>
              )}
              {false && !aiAuthoringResult && !aiAuthoringError && !aiAuthoringPatch && (
                <div className="rounded-DEFAULT border border-status-error/30 bg-status-error/8 p-4">
                  <div className="font-mono-sm text-[10px] uppercase tracking-[0.08em] text-status-error">
                    Problems to repair
                  </div>
                  <ul className="mt-3 space-y-2 text-[11px] leading-5 text-on-surface-variant">
                    {uniqueValidationIssues.map((issue) => <li key={issue}>{issue}</li>)}
                  </ul>
                </div>
              )}
              {aiAuthoringResult && (
                <div className="rounded-DEFAULT border border-secondary/30 bg-secondary-container/20 p-4">
                  <div className="flex items-center gap-2 font-mono-sm text-[11px] text-secondary">
                    {aiAuthoringPatch && <span className="h-2 w-2 rounded-full bg-secondary" />}
                    {aiAuthoringResult.title}
                  </div>
                  <ul className="mt-3 space-y-2 text-[12px] leading-5 text-on-surface-variant">
                    {aiAuthoringResult.lines.map((line, index) => <li key={`${index}-${line}`}>{line}</li>)}
                  </ul>
                </div>
              )}
              {aiAuthoringPatch && (
                <div className="overflow-hidden rounded-DEFAULT border border-border-subtle bg-surface-lowest">
                  <div className="flex items-center justify-end border-b border-border-subtle px-4 py-2.5">
                    <div className="flex items-center gap-3">
                      <button
                        type="button"
                        onClick={() => setSelectedDiffLines(new Set(
                          aiAuthoringPatch.diff
                            .map((line, index) => line.kind === 'context' ? -1 : index)
                            .filter((index) => index >= 0),
                        ))}
                        className="font-mono-sm text-[10px] text-on-surface-variant hover:text-secondary"
                      >
                        Select all
                      </button>
                      <button
                        type="button"
                        onClick={() => setSelectedDiffLines(new Set())}
                        className="font-mono-sm text-[10px] text-on-surface-variant hover:text-secondary"
                      >
                        Select none
                      </button>
                      <span className="font-mono-sm text-[10px] text-secondary">Validated source patch</span>
                    </div>
                  </div>
                  <div className="grid grid-cols-[minmax(0,1fr)_5rem_minmax(0,1fr)] border-b border-border-subtle bg-surface-container-low/55 font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant">
                    <div className="border-r border-border-subtle px-4 py-2.5">Original · definition.json</div>
                    <div className="px-1 py-2.5 text-center text-on-surface-variant/45">Apply</div>
                    <div className="border-l border-border-subtle px-4 py-2.5">AI proposal · definition.json</div>
                  </div>
                  <div className="max-h-[50vh] overflow-auto font-mono-sm text-[11px] leading-[1.65]">
                    {splitPatchRows.map((row, rowIndex) => {
                      if (row.collapsedContext !== undefined) {
                        return (
                          <div
                            key={`collapsed-${rowIndex}`}
                            className="grid min-w-[900px] grid-cols-[minmax(0,1fr)_5rem_minmax(0,1fr)] border-b border-border-subtle/35 bg-surface-container-low/55 font-mono-sm text-[9px] uppercase tracking-[0.08em] text-on-surface-variant/55"
                          >
                            <div className="border-r border-border-subtle px-4 py-2 text-center">
                              {row.collapsedContext} unchanged lines
                            </div>
                            <div className="px-1 py-2 text-center">···</div>
                            <div className="border-l border-border-subtle px-4 py-2 text-center">
                              {row.collapsedContext} unchanged lines
                            </div>
                          </div>
                        );
                      }
                      const indices = [row.left?.index, row.right?.index]
                        .filter((index): index is number => index !== undefined);
                      const accepted = indices.length > 0 && indices.every((index) => selectedDiffLines.has(index));
                      const blockIndices = row.blockId === undefined ? [] : splitPatchRows
                        .filter((candidate) => candidate.blockId === row.blockId)
                        .flatMap((candidate) => [candidate.left?.index, candidate.right?.index])
                        .filter((index): index is number => index !== undefined);
                      const blockAccepted = blockIndices.length > 0
                        && blockIndices.every((index) => selectedDiffLines.has(index));
                      const toggleRow = () => setSelectedDiffLines((current) => {
                        const next = new Set(current);
                        indices.forEach((index) => {
                          if (accepted) next.delete(index); else next.add(index);
                        });
                        return next;
                      });
                      const toggleBlock = () => setSelectedDiffLines((current) => {
                        const next = new Set(current);
                        blockIndices.forEach((index) => {
                          if (blockAccepted) next.delete(index); else next.add(index);
                        });
                        return next;
                      });
                      return (
                        <div
                          key={`${rowIndex}-${row.context || row.left?.text || row.right?.text}`}
                          className="grid min-w-[900px] grid-cols-[minmax(0,1fr)_5rem_minmax(0,1fr)] border-b border-border-subtle/35 last:border-b-0"
                        >
                          <div className={`grid grid-cols-[3rem_minmax(0,1fr)] border-r border-border-subtle px-2 ${row.left
                            ? accepted ? 'bg-status-error/10 text-status-error' : 'text-on-surface-variant/45'
                            : 'text-on-surface-variant/70'}`}
                          >
                            <span className="select-none pr-2 text-right text-on-surface-variant/30">{rowIndex + 1}</span>
                            <code className={`whitespace-pre pr-3 ${row.left && !accepted ? 'line-through opacity-55' : ''}`}>
                              {row.context ?? row.left?.text ?? ' '}
                            </code>
                          </div>
                          <div className="flex items-center justify-center gap-1 bg-surface-container-lowest">
                            {indices.length > 0 && (
                              <button
                                type="button"
                                onClick={toggleRow}
                                className={`flex h-6 w-7 items-center justify-center rounded-[3px] border transition-colors ${accepted
                                  ? 'border-status-error/35 text-status-error hover:bg-status-error/10'
                                  : 'border-secondary/35 text-secondary hover:bg-secondary-container/25'}`}
                                title={accepted ? 'Revert this change' : 'Accept this change'}
                                aria-label={accepted ? `Revert change row ${rowIndex + 1}` : `Accept change row ${rowIndex + 1}`}
                              >
                                {accepted ? <Undo2 size={12} /> : <ArrowRight size={12} />}
                              </button>
                            )}
                            {row.firstInBlock && (
                              <button
                                type="button"
                                onClick={toggleBlock}
                                className={`flex h-6 min-w-8 items-center justify-center rounded-[3px] border px-1 font-mono-sm text-[8px] font-semibold uppercase tracking-wide transition-colors ${blockAccepted
                                  ? 'border-status-error/35 text-status-error hover:bg-status-error/10'
                                  : 'border-secondary/35 text-secondary hover:bg-secondary-container/25'}`}
                                title={blockAccepted ? 'Revert this entire change block' : 'Accept this entire change block'}
                                aria-label={blockAccepted ? `Revert change block ${row.blockId}` : `Accept change block ${row.blockId}`}
                              >
                                {blockAccepted ? <Undo2 size={11} /> : 'Block'}
                              </button>
                            )}
                          </div>
                          <div className={`grid grid-cols-[3rem_minmax(0,1fr)] border-l border-border-subtle px-2 ${row.right
                            ? accepted ? 'bg-secondary/10 text-secondary-fixed' : 'text-on-surface-variant/45'
                            : 'text-on-surface-variant/70'}`}
                          >
                            <span className="select-none pr-2 text-right text-on-surface-variant/30">{rowIndex + 1}</span>
                            <code className={`whitespace-pre pr-3 ${row.right && !accepted ? 'line-through opacity-55' : ''}`}>
                              {row.context ?? row.right?.text ?? ' '}
                            </code>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                  <div className={`border-t px-4 py-2.5 font-mono-sm text-[10px] ${stagedPatch?.parseError || (stagedPatch?.issues.length || 0) > 0
                    ? 'border-status-error/25 bg-status-error/8 text-status-error'
                    : 'border-secondary/25 bg-secondary-container/10 text-secondary'}`}
                  >
                    {stagedPatch?.parseError
                      ? `Selection is not valid JSON: ${stagedPatch.parseError}`
                      : stagedPatch && stagedPatch.issues.length > 0
                        ? `Selection has ${stagedPatch.issues.length} ASL validation ${stagedPatch.issues.length === 1 ? 'issue' : 'issues'}. Select dependent lines together.`
                        : `${selectedDiffLines.size} changed ${selectedDiffLines.size === 1 ? 'line' : 'lines'} staged and valid.`}
                  </div>
                </div>
              )}
              {aiAuthoringError && (
                <div className="whitespace-pre-wrap rounded-DEFAULT border border-status-error/30 bg-status-error/10 p-3 text-[11px] text-status-error">
                  {aiAuthoringError}
                </div>
              )}
              <div className="flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => setAiAuthoringAction(null)}
                  disabled={aiAuthoringBusy}
                  className="h-9 rounded-DEFAULT border border-border-subtle px-4 font-mono-sm text-[11px] text-on-surface-variant disabled:opacity-40"
                >
                  {aiAuthoringPatch ? 'Discard' : aiAuthoringResult ? 'Done' : 'Cancel'}
                </button>
                {aiAuthoringPatch ? (
                  <button
                    type="button"
                    data-testid="workflow-apply-ai-patch"
                    onClick={applyAiAuthoringPatch}
                    disabled={!stagedPatch?.definition || stagedPatch.issues.length > 0}
                    className="flex h-9 items-center gap-2 rounded-DEFAULT bg-secondary px-4 font-mono-sm text-[11px] text-on-secondary disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    <span className="material-symbols-outlined text-[15px]">difference</span>
                    Apply selected
                  </button>
                ) : !aiAuthoringResult && (
                  <button
                    type="button"
                    onClick={runAiAuthoring}
                    disabled={aiAuthoringBusy}
                    className="flex h-9 items-center gap-2 rounded-DEFAULT bg-primary px-4 font-mono-sm text-[11px] text-on-primary disabled:cursor-not-allowed disabled:opacity-45"
                  >
                    {aiAuthoringBusy ? <Loader2 size={13} className="animate-spin" /> : <Sparkles size={13} />}
                    {aiAuthoringBusy ? 'Working...' : 'Explain'}
                  </button>
                )}
              </div>
            </div>
          </section>
        </div>
      )}
    </div>
  );
}
