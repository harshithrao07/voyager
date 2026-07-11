import Editor from '@monaco-editor/react';
import { useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { AlertCircle, Braces, ListPlus, Loader2, PanelRightClose, PanelRightOpen, Save } from 'lucide-react';
import type { WorkflowPriorityDTO } from '../../api';
import type { DefinitionStatus, WorkflowPreview } from './types';
import { AslGraphViewer } from '../AslGraphViewer';
import { StateBuilderPanel } from './StateBuilderPanel';
import type { AslDefinition } from './stateBuilder';
import { WorkflowPreviewPanel } from './WorkflowPreviewPanel';
import { WorkflowMetadataForm } from './WorkflowMetadataForm';

type EditorView = 'code' | 'builder';

type Props = {
  definitionText: string;
  onDefinitionTextChange: (value: string) => void;
  definitionStatus: DefinitionStatus;
  definitionStats: WorkflowPreview;
  error: string | null;
  validationIssues: string[];
  saving: boolean;
  canSave: boolean;
  onSave: () => void;
  name: string;
  onNameChange: (value: string) => void;
  priority: WorkflowPriorityDTO;
  onPriorityChange: (value: WorkflowPriorityDTO) => void;
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
  reserveTopControlsSpace?: boolean;
};

export function ManualWorkflowEditor({
  definitionText,
  onDefinitionTextChange,
  definitionStatus,
  definitionStats,
  error,
  validationIssues,
  saving,
  canSave,
  onSave,
  name,
  onNameChange,
  priority,
  onPriorityChange,
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
  reserveTopControlsSpace,
}: Props) {
  const [selectedStateName, setSelectedStateName] = useState('');
  const [editorView, setEditorView] = useState<EditorView>('code');
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [sidebarWidth, setSidebarWidth] = useState(360);
  const [topPct, setTopPct] = useState(52);
  const bodyRef = useRef<HTMLDivElement>(null);
  const mainRef = useRef<HTMLDivElement>(null);

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

  const startVerticalResize = (event: ReactPointerEvent) => {
    event.preventDefault();
    const onMove = (moveEvent: PointerEvent) => {
      const rect = mainRef.current?.getBoundingClientRect();
      if (!rect || rect.height === 0) return;
      const pct = ((moveEvent.clientY - rect.top) / rect.height) * 100;
      setTopPct(Math.min(80, Math.max(20, pct)));
    };
    const onUp = () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    document.body.style.cursor = 'row-resize';
    document.body.style.userSelect = 'none';
  };

  const parsedDefinition = useMemo(() => {
    if (!definitionStatus.valid) return null;
    try {
      return JSON.parse(definitionText);
    } catch {
      return null;
    }
  }, [definitionText, definitionStatus.valid]);

  const handleBuilderChange = (nextDefinition: AslDefinition) => {
    onDefinitionTextChange(JSON.stringify(nextDefinition, null, 2));
  };

  const viewToggle = (
    <div className="flex items-center gap-1 rounded-DEFAULT border border-border-subtle p-0.5">
      {([
        { view: 'code', label: 'Code', icon: <Braces size={12} /> },
        { view: 'builder', label: 'Builder', icon: <ListPlus size={12} /> },
      ] as const).map(({ view, label, icon }) => (
        <button
          key={view}
          type="button"
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
      <div ref={bodyRef} className="flex min-h-0 flex-1 overflow-hidden">
        <main ref={mainRef} className="relative flex min-h-0 min-w-0 flex-1 flex-col bg-surface-base">
          <div className="flex min-h-0 shrink-0 flex-col" style={{ height: `${topPct}%` }}>
          <div className="flex h-14 shrink-0 items-center border-b border-border-subtle bg-surface-base px-6">
            <div className="flex items-center gap-4">
              <div className="flex items-center gap-2 font-mono-sm text-[13px] text-on-surface">
                <span className="material-symbols-outlined text-[18px]">description</span>
                definition.json
              </div>
              {viewToggle}
            </div>
          </div>
          <div className="flex min-h-9 shrink-0 items-center gap-x-4 gap-y-1 border-b border-border-subtle bg-surface-base px-6 py-2 font-mono-sm text-[12px] text-on-surface-variant">
            <span>{definitionStats.stateCount} states</span>
            <span>{definitionStats.taskCount} tasks</span>
            <span
              className={`min-w-0 flex-1 truncate ${definitionStatus.valid ? 'text-secondary' : 'text-status-error'}`}
              title={definitionStatus.message}
              aria-live="polite"
            >
              {definitionStatus.message}
            </span>
          </div>
          <div className="flex min-h-0 flex-1 flex-col">
            {editorView === 'code' ? (
              <Editor
                height="100%"
                defaultLanguage="json"
                theme="vs-dark"
                value={definitionText}
                onChange={(value) => onDefinitionTextChange(value || '')}
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
            ) : parsedDefinition ? (
              <StateBuilderPanel
                definition={parsedDefinition}
                onDefinitionChange={handleBuilderChange}
                selectedStateName={selectedStateName}
                onStateSelect={setSelectedStateName}
                fieldClass={fieldClass}
                monoFieldClass={monoFieldClass}
              />
            ) : (
              <div className="flex flex-1 items-center justify-center px-6 text-center font-mono-sm text-[12px] text-on-surface-variant">
                The definition JSON is invalid. Switch to Code view to fix it before using the builder.
              </div>
            )}
          </div>
          </div>

          <div
            role="separator"
            aria-orientation="horizontal"
            onPointerDown={startVerticalResize}
            className="group hidden h-1.5 shrink-0 cursor-row-resize items-center justify-center bg-border-subtle/40 transition-colors hover:bg-primary/50 xl:flex"
          >
            <span className="h-0.5 w-8 rounded-full bg-border-subtle transition-colors group-hover:bg-primary" />
          </div>

          <div className="flex min-h-0 flex-1 flex-col">
          <div className="flex h-14 shrink-0 items-center justify-between border-y border-border-subtle bg-surface-base px-6">
            <div className="flex items-center gap-2 font-mono-sm text-[13px] text-on-surface">
              <span className="material-symbols-outlined text-[18px]">account_tree</span>
              state machine
            </div>
          </div>
          <div className="min-h-0 flex-1">
            {parsedDefinition ? (
              <AslGraphViewer
                definition={parsedDefinition}
                selectedStateName={selectedStateName}
                onStateSelect={setSelectedStateName}
              />
            ) : (
              <div className="flex h-full items-center justify-center bg-surface-lowest px-6 text-center font-mono-sm text-[12px] text-on-surface-variant">
                Fix the definition JSON to render the state machine diagram.
              </div>
            )}
          </div>
          </div>

          {!sidebarOpen && (
            <button
              type="button"
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
          className="hidden min-h-0 shrink-0 flex-col overflow-y-auto border-l border-border-subtle bg-surface-base xl:flex"
          style={{ width: sidebarWidth }}
        >
          <div className={`border-b border-border-subtle p-6 ${reserveTopControlsSpace ? 'pt-[82px]' : ''}`}>
            <div className="mb-4 flex items-center justify-between">
              <span className="font-mono-sm text-[11px] uppercase tracking-[0.08em] text-on-surface-variant">Workflow</span>
              <button
                type="button"
                onClick={() => setSidebarOpen(false)}
                title="Collapse panel"
                className="flex h-7 w-7 items-center justify-center rounded-DEFAULT text-on-surface-variant transition-colors hover:bg-surface-container hover:text-on-surface"
              >
                <PanelRightClose size={16} />
              </button>
            </div>
            <button
              type="button"
              onClick={onSave}
              disabled={!canSave}
              className="flex h-10 w-full items-center justify-center gap-2 rounded-DEFAULT bg-primary px-4 font-body-sm text-[12px] font-medium text-on-primary shadow-[0_12px_30px_rgba(242,121,90,0.18)] transition-colors hover:bg-primary-fixed-dim disabled:cursor-not-allowed disabled:opacity-50"
            >
              {saving ? <Loader2 className="animate-spin" size={16} /> : <Save size={16} />}
              Save draft
            </button>
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
                Ready to save as a draft workflow.
              </div>
            )}
          </div>
          <div className="p-8">
            <WorkflowMetadataForm
              name={name}
              onNameChange={onNameChange}
              priority={priority}
              onPriorityChange={onPriorityChange}
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
          </div>
        </aside>
        )}
      </div>
    </div>
  );
}
