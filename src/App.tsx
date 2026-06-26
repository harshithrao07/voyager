import { useEffect, useState } from 'react';
import { PanelLeftClose, PanelLeftOpen, PanelRightOpen } from 'lucide-react';
import { WorkflowGeneratorPanel } from './components/WorkflowGeneratorPanel';
import { AslCodeViewer } from './components/AslCodeViewer';
import { AslGraphViewer } from './components/AslGraphViewer';
import { NodeDetailsPanel } from './components/NodeDetailsPanel';
import { RevisionHistoryPanel } from './components/RevisionHistoryPanel';
import { ExecutionStatusView, type ExecutionRun } from './components/ExecutionStatusView';
import { WorkflowListView, type WorkflowSummary } from './components/WorkflowListView';
import {
  getWorkflow,
  getWorkflowRevisions,
  listWorkflows,
  type WorkflowDefinitionResponseDTO,
  type WorkflowPageDTO,
  type WorkflowResponseDTO,
  type WorkflowStatusDTO,
} from './api';

const activeDefinition = {
  "StartAt": "Fetch Source Data",
  "States": {
    "Fetch Source Data": {
      "Type": "Task",
      "Resource": "Lambda::Invoke",
      "Next": "Data Validation"
    },
    "Data Validation": {
      "Type": "Choice",
      "Choices": [
        { "Next": "Process Embeddings" }
      ],
      "Default": "Log Failure"
    },
    "Log Failure": {
      "Type": "Task",
      "Resource": "SNS::Publish",
      "End": true
    },
    "Process Embeddings": {
      "Type": "Task",
      "Resource": "ECS::RunTask",
      "End": true
    }
  }
};

type WorkflowRevision = {
  id: string;
  label: string;
  timestamp: string;
  active?: boolean;
  note: string;
  definition: any;
  runs: ExecutionRun[];
};

function formatWorkflowStatus(status: WorkflowStatusDTO): WorkflowSummary['status'] {
  if (status === 'ACTIVE') return 'Active';
  if (status === 'PAUSED') return 'Paused';
  if (status === 'ARCHIVED') return 'Archived';
  return 'Draft';
}

function formatDateTime(value?: string | null) {
  if (!value) return '-';

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;

  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

function formatSchedule(cronExpression?: string | null, timezone?: string | null) {
  if (!cronExpression) return 'Trigger-based';
  return timezone ? `${cronExpression} - ${timezone}` : cronExpression;
}

function workflowDescription(workflow: WorkflowResponseDTO) {
  const revision = workflow.activeDefinition?.revision ? `Rev ${workflow.activeDefinition.revision}` : 'No active revision';
  return `${workflow.priority} priority - ${workflow.maxAttempts} max attempts - ${revision}`;
}

function mapWorkflowSummary(workflow: WorkflowResponseDTO): WorkflowSummary {
  return {
    id: workflow.id,
    name: workflow.name,
    status: formatWorkflowStatus(workflow.status),
    schedule: formatSchedule(workflow.cronExpression, workflow.timezone),
    nextRun: workflow.nextRunAt ? `Next: ${formatDateTime(workflow.nextRunAt)}` : '-',
    description: workflowDescription(workflow),
  };
}

function mapWorkflowRevision(revision: WorkflowDefinitionResponseDTO): WorkflowRevision {
  const hash = revision.definitionHash ? revision.definitionHash.slice(0, 12) : 'unhashed';

  return {
    id: String(revision.revision),
    label: `Rev ${revision.revision}`,
    timestamp: formatDateTime(revision.createdAt),
    active: revision.active,
    note: `Definition hash ${hash}`,
    definition: revision.definition,
    runs: [],
  };
}

function getStartState(definition: any) {
  return definition?.StartAt || Object.keys(definition?.States || {})[0] || '';
}

function buildGeneratedRevision(definition: any): WorkflowRevision {
  return {
    id: 'generated',
    label: 'Generated',
    timestamp: 'Unsaved',
    active: false,
    note: 'Generated locally from the prompt. Save it as a backend revision when ready.',
    definition,
    runs: [],
  };
}

function workflowStatusBadgeClass(status: WorkflowSummary['status']) {
  if (status === 'Failed') return 'border-status-error/20 bg-status-error/10 text-status-error';
  if (status === 'Paused') return 'border-border-muted bg-surface-container text-on-surface-variant';
  if (status === 'Draft') return 'border-status-info/20 bg-status-info/10 text-status-info';
  if (status === 'Archived') return 'border-border-muted bg-surface-container-low text-on-surface-variant';
  return 'border-status-success/20 bg-status-success/10 text-status-success';
}

function WorkspaceState({
  title,
  message,
  action,
}: {
  title: string;
  message: string;
  action?: { label: string; onClick: () => void };
}) {
  return (
    <div className="flex h-full items-center justify-center bg-surface-lowest p-6">
      <div className="glass-card max-w-md rounded-DEFAULT border border-border-subtle bg-surface-container-lowest p-5 text-center">
        <div className="font-headline-md text-headline-md font-medium text-primary">{title}</div>
        <p className="mt-2 text-body-sm text-on-surface-variant">{message}</p>
        {action && (
          <button
            type="button"
            onClick={action.onClick}
            className="mt-4 rounded-DEFAULT border border-border-subtle bg-surface-elevated px-3 py-1.5 text-body-sm text-primary transition-colors hover:bg-surface-container"
          >
            {action.label}
          </button>
        )}
      </div>
    </div>
  );
}

function WorkflowDashboard({
  workflows,
  totalWorkflows,
  onSelect,
}: {
  workflows: WorkflowSummary[];
  totalWorkflows?: number;
  onSelect: (workflow: WorkflowSummary) => void;
}) {
  const activeCount = workflows.filter((workflow) => workflow.status === 'Active').length;
  const pausedCount = workflows.filter((workflow) => workflow.status === 'Paused').length;
  const archivedCount = workflows.filter((workflow) => workflow.status === 'Archived').length;

  return (
    <div className="flex h-full min-h-0 flex-col bg-surface-lowest">
      <div className="glass-shell grid shrink-0 grid-cols-1 border-b border-border-subtle bg-surface-base md:grid-cols-4">
        <DashboardMetric label="Total workflows" value={totalWorkflows ?? workflows.length} tone="text-primary" />
        <DashboardMetric label="Active workflows" value={activeCount} tone="text-status-success" />
        <DashboardMetric label="Paused workflows" value={pausedCount} tone="text-on-surface-variant" />
        <DashboardMetric label="Archived workflows" value={archivedCount} tone="text-status-info" />
      </div>

      <div className="flex-1 overflow-hidden p-4">
        <section className="glass-panel flex h-full min-h-0 flex-col overflow-hidden border border-border-subtle bg-surface">
          <div className="glass-shell flex h-11 shrink-0 items-center justify-between border-b border-border-subtle bg-surface-elevated px-4">
            <div>
              <h2 className="font-headline-md text-headline-md font-medium text-primary">Workflow Executions</h2>
            </div>
            <button className="rounded-DEFAULT border border-border-subtle bg-surface-lowest px-3 py-1.5 font-body-sm text-body-sm text-on-surface-variant transition-colors hover:text-primary">
              Saved view: Open
            </button>
          </div>
          <div className="grid grid-cols-[1fr_120px_150px_130px] border-b border-border-subtle bg-surface-container-lowest px-4 py-2 text-label-caps font-label-caps text-on-surface-variant">
            <div>Workflow</div>
            <div>Status</div>
            <div>Schedule</div>
            <div className="text-right">Next run</div>
          </div>
          <div className="flex-1 overflow-y-auto">
            {workflows.map((workflow) => (
              <button
                key={workflow.id}
                type="button"
                onClick={() => onSelect(workflow)}
                className="grid w-full grid-cols-[1fr_120px_150px_130px] items-center border-b border-border-subtle px-4 py-3 text-left transition-colors hover:bg-surface-container-low"
              >
                <div className="min-w-0">
                  <div className="truncate font-body-sm text-body-sm font-medium text-primary">{workflow.name}</div>
                  <div className="truncate font-mono-sm text-[11px] text-on-surface-variant">{workflow.id}</div>
                </div>
                <div>
                  <span className={`rounded border px-2 py-0.5 font-body-sm text-[11px] ${workflowStatusBadgeClass(workflow.status)}`}>
                    {workflow.status}
                  </span>
                </div>
                <div className="font-mono-sm text-[11px] text-on-surface-variant">{workflow.schedule}</div>
                <div className="text-right font-mono-sm text-[11px] text-on-surface-variant">{workflow.nextRun}</div>
              </button>
            ))}
          </div>
        </section>
      </div>
    </div>
  );
}

function DashboardMetric({ label, value, tone }: { label: string; value: string | number; tone: string }) {
  return (
    <div className="border-b border-border-subtle px-4 py-3 md:border-b-0 md:border-r">
      <div className="text-label-caps font-label-caps text-on-surface-variant">{label}</div>
      <div className={`mt-1 font-mono-sm text-[20px] font-semibold ${tone}`}>{value}</div>
    </div>
  );
}

function App() {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [detailsPanelOpen, setDetailsPanelOpen] = useState(false);
  const [revisionPanelOpen, setRevisionPanelOpen] = useState(false);
  const [activeShellView, setActiveShellView] = useState<'dashboard' | 'workflows'>('workflows');
  const [workflowPage, setWorkflowPage] = useState<WorkflowPageDTO | null>(null);
  const [workflowSummaries, setWorkflowSummaries] = useState<WorkflowSummary[]>([]);
  const [workflowListLoading, setWorkflowListLoading] = useState(true);
  const [workflowListError, setWorkflowListError] = useState<string | null>(null);
  const [workflowDetail, setWorkflowDetail] = useState<WorkflowResponseDTO | null>(null);
  const [workflowRevisions, setWorkflowRevisions] = useState<WorkflowRevision[]>([]);
  const [workflowDetailLoading, setWorkflowDetailLoading] = useState(false);
  const [workflowDetailError, setWorkflowDetailError] = useState<string | null>(null);
  const [workflowDef, setWorkflowDef] = useState<any>(activeDefinition);
  const [activeTab, setActiveTab] = useState<'visualizer' | 'definition' | 'executions'>('visualizer');
  const [selectedStateName, setSelectedStateName] = useState('Fetch Source Data');
  const [selectedWorkflow, setSelectedWorkflow] = useState<WorkflowSummary | null>(null);
  const [selectedRevisionId, setSelectedRevisionId] = useState('');
  const selectedRevision =
    workflowRevisions.find((revision) => revision.id === selectedRevisionId) ||
    workflowRevisions.find((revision) => revision.active) ||
    workflowRevisions[0] ||
    null;
  const currentDefinition = selectedRevision?.definition || workflowDetail?.activeDefinition?.definition || workflowDef;

  const loadWorkflows = () => {
    setWorkflowListLoading(true);
    setWorkflowListError(null);

    listWorkflows({ page: 0, size: 50 })
      .then((page) => {
        setWorkflowPage(page);
        setWorkflowSummaries(page.content.map(mapWorkflowSummary));
      })
      .catch((error: Error) => {
        setWorkflowPage(null);
        setWorkflowSummaries([]);
        setWorkflowListError(error.message);
      })
      .finally(() => setWorkflowListLoading(false));
  };

  useEffect(() => {
    loadWorkflows();
  }, []);

  useEffect(() => {
    if (!selectedWorkflow) {
      setWorkflowDetail(null);
      setWorkflowRevisions([]);
      setWorkflowDetailError(null);
      setWorkflowDetailLoading(false);
      return;
    }

    let cancelled = false;
    setWorkflowDetailLoading(true);
    setWorkflowDetailError(null);
    setWorkflowDetail(null);
    setWorkflowRevisions([]);

    Promise.all([
      getWorkflow({ workflowId: selectedWorkflow.id }),
      getWorkflowRevisions({ workflowId: selectedWorkflow.id }),
    ])
      .then(([detail, revisionDtos]) => {
        if (cancelled) return;

        const revisions = revisionDtos.map(mapWorkflowRevision);
        const activeRevision =
          revisions.find((revision) => revision.active) ||
          (detail.activeDefinition ? mapWorkflowRevision(detail.activeDefinition) : null) ||
          revisions[0] ||
          null;
        const nextDefinition = activeRevision?.definition || detail.activeDefinition?.definition || activeDefinition;

        setWorkflowDetail(detail);
        setWorkflowRevisions(revisions.length > 0 ? revisions : activeRevision ? [activeRevision] : []);
        setSelectedRevisionId(activeRevision?.id || '');
        setWorkflowDef(nextDefinition);
        setSelectedStateName(getStartState(nextDefinition));
      })
      .catch((error: Error) => {
        if (cancelled) return;
        setWorkflowDetailError(error.message);
      })
      .finally(() => {
        if (!cancelled) {
          setWorkflowDetailLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [selectedWorkflow]);

  const handleWorkflowGenerated = (definition: any) => {
    setWorkflowDef(definition);
    const generatedRevision = buildGeneratedRevision(definition);
    setWorkflowRevisions((current) => [generatedRevision, ...current.filter((revision) => revision.id !== generatedRevision.id)]);
    setSelectedRevisionId(generatedRevision.id);
    setSelectedStateName(getStartState(definition));
    setDetailsPanelOpen(false);
    setRevisionPanelOpen(false);
  };

  const handleWorkflowSelected = (workflow: WorkflowSummary) => {
    setSelectedWorkflow(workflow);
    setActiveShellView('workflows');
    setSelectedRevisionId('');
    setDetailsPanelOpen(false);
    setRevisionPanelOpen(false);
    setActiveTab('visualizer');
  };

  const handleRevisionSelected = (revision: WorkflowRevision) => {
    setSelectedRevisionId(revision.id);
    setSelectedStateName(getStartState(revision.definition));
    setDetailsPanelOpen(false);
  };

  const handleRevisionIdSelected = (revisionId: string) => {
    const revision = workflowRevisions.find((item) => item.id === revisionId);
    if (revision) {
      handleRevisionSelected(revision);
    }
  };

  const handleStateSelect = (stateName: string) => {
    setSelectedStateName(stateName);
    setDetailsPanelOpen(true);
    setRevisionPanelOpen(false);
  };

  return (
    <div className="font-body-sm text-body-sm overflow-hidden selection:bg-status-accent/30 selection:text-primary">
      {/* TopNavBar */}
      <nav className="glass-shell bg-surface-base dark:bg-surface-base border-b border-border-subtle dark:border-border-subtle fixed top-0 left-0 w-full z-50 flex h-11 items-center justify-between px-4">
        <div className="flex items-center gap-element-gap-md">
          <div className="font-headline-md text-[15px] font-extrabold leading-none text-primary dark:text-primary">
            Agentic Workflow
          </div>
          <button className="hidden h-7 items-center gap-2 rounded-DEFAULT border border-border-subtle bg-surface-elevated px-2.5 font-mono-sm text-[11px] text-on-surface-variant transition-colors hover:border-border-muted hover:text-primary md:flex">
            <span className="h-1.5 w-1.5 rounded-full bg-status-success"></span>
            default namespace
            <span className="material-symbols-outlined text-[14px]">expand_more</span>
          </button>
          <div className="ml-4 hidden h-full items-center gap-element-gap-sm xl:flex">
            <div className="relative group">
              <span className="material-symbols-outlined absolute left-2 top-1/2 -translate-y-1/2 text-on-surface-variant text-[16px]">search</span>
              <input className="h-8 w-80 rounded-DEFAULT border border-border-subtle bg-surface-elevated pl-8 pr-3 font-body-sm text-body-sm text-primary placeholder-on-surface-variant/50 transition-colors focus:border-status-info focus:outline-none focus:ring-1 focus:ring-status-info" placeholder="Search workflows, run IDs, task queues..." type="text" />
            </div>
          </div>
        </div>
        <div className="flex items-center gap-element-gap-md">
          <div className="flex items-center gap-element-gap-sm text-on-surface-variant">
            <button className="flex h-8 w-8 items-center justify-center rounded-DEFAULT transition-colors duration-200 hover:bg-surface-container hover:text-primary">
              <span className="material-symbols-outlined text-[18px]">notifications</span>
            </button>
          </div>
        </div>
      </nav>

      <div className="flex h-screen pt-11">
        <aside
          id="app-sidebar"
          className={`glass-shell fixed left-0 top-11 z-40 hidden h-[calc(100vh-2.75rem)] flex-col border-r border-border-subtle bg-surface-container-lowest py-3 transition-[width] duration-200 ease-out md:flex ${sidebarOpen ? 'w-sidebar-width' : 'w-16'}`}
          aria-label={sidebarOpen ? 'Expanded navigation' : 'Collapsed navigation'}
        >
          <div className={`mb-4 px-3 ${sidebarOpen ? '' : 'px-2'}`}>
            <button
              className={`flex h-10 w-full items-center rounded-DEFAULT border border-border-subtle bg-surface-elevated font-body-sm text-body-sm text-primary transition-colors hover:bg-surface-container ${sidebarOpen ? 'justify-center gap-2 px-3' : 'justify-center px-0'}`}
              aria-label="New Workflow"
              title="New Workflow"
            >
              <span className="material-symbols-outlined text-[18px]">add</span>
              <span className={sidebarOpen ? 'inline whitespace-nowrap' : 'hidden'}>New Workflow</span>
            </button>
          </div>
          <div className="flex-1 overflow-y-auto px-2 space-y-1">
            <button
              onClick={() => { setSelectedWorkflow(null); setRevisionPanelOpen(false); setActiveShellView('dashboard'); }}
              className={`flex h-10 w-full items-center rounded-DEFAULT transition-colors ${sidebarOpen ? 'gap-3 px-3' : 'justify-center px-0'} ${!selectedWorkflow && activeShellView === 'dashboard' ? 'border-l-2 border-status-info bg-surface-container-high text-primary' : 'text-on-surface-variant hover:bg-surface-container hover:text-primary'}`}
              aria-label="Dashboard"
              title="Dashboard"
            >
              <span className="material-symbols-outlined shrink-0 text-[20px]">dashboard</span>
              <span className={sidebarOpen ? 'inline truncate font-body-sm text-body-sm' : 'hidden'}>Dashboard</span>
            </button>
            <button
              onClick={() => { setSelectedWorkflow(null); setRevisionPanelOpen(false); setActiveShellView('workflows'); }}
              className={`flex h-10 w-full items-center rounded-DEFAULT transition-colors ${sidebarOpen ? 'gap-3 px-3' : 'justify-center px-0'} ${!selectedWorkflow && activeShellView === 'workflows' ? 'border-l-2 border-status-accent bg-surface-container-high text-primary' : 'text-on-surface-variant hover:bg-surface-container hover:text-primary'}`}
              aria-label="Workflows"
              title="Workflows"
            >
              <span className="material-symbols-outlined shrink-0 text-[20px]">schema</span>
              <span className={sidebarOpen ? 'inline truncate font-body-sm text-body-sm font-medium' : 'hidden'}>Workflows</span>
            </button>
            <button
              onClick={() => selectedWorkflow && setActiveTab('executions')}
              className={`flex h-10 w-full items-center rounded-DEFAULT text-on-surface-variant transition-colors hover:bg-surface-container hover:text-primary ${sidebarOpen ? 'gap-3 px-3' : 'justify-center px-0'}`}
              aria-label="Executions"
              title="Executions"
            >
              <span className="material-symbols-outlined shrink-0 text-[20px]">history</span>
              <span className={sidebarOpen ? 'inline truncate font-body-sm text-body-sm' : 'hidden'}>Executions</span>
            </button>
            <button className={`flex h-10 w-full items-center rounded-DEFAULT text-on-surface-variant transition-colors hover:bg-surface-container hover:text-primary ${sidebarOpen ? 'gap-3 px-3' : 'justify-center px-0'}`} aria-label="Workers" title="Workers">
              <span className="material-symbols-outlined shrink-0 text-[20px]">dns</span>
              <span className={sidebarOpen ? 'inline truncate font-body-sm text-body-sm' : 'hidden'}>Workers</span>
            </button>
            <button className={`flex h-10 w-full items-center rounded-DEFAULT text-on-surface-variant transition-colors hover:bg-surface-container hover:text-primary ${sidebarOpen ? 'gap-3 px-3' : 'justify-center px-0'}`} aria-label="Schedules" title="Schedules">
              <span className="material-symbols-outlined shrink-0 text-[20px]">calendar_clock</span>
              <span className={sidebarOpen ? 'inline truncate font-body-sm text-body-sm' : 'hidden'}>Schedules</span>
            </button>
            <button className={`flex h-10 w-full items-center rounded-DEFAULT text-on-surface-variant transition-colors hover:bg-surface-container hover:text-primary ${sidebarOpen ? 'gap-3 px-3' : 'justify-center px-0'}`} aria-label="Docs" title="Docs">
              <span className="material-symbols-outlined shrink-0 text-[20px]">description</span>
              <span className={sidebarOpen ? 'inline truncate font-body-sm text-body-sm' : 'hidden'}>Docs</span>
            </button>
            <button className={`flex h-10 w-full items-center rounded-DEFAULT text-on-surface-variant transition-colors hover:bg-surface-container hover:text-primary ${sidebarOpen ? 'gap-3 px-3' : 'justify-center px-0'}`} aria-label="Settings" title="Settings">
              <span className="material-symbols-outlined shrink-0 text-[20px]">settings_suggest</span>
              <span className={sidebarOpen ? 'inline truncate font-body-sm text-body-sm' : 'hidden'}>Settings</span>
            </button>
          </div>
          <div className="mt-auto px-2 pt-4 border-t border-border-subtle">
            <button
              type="button"
              onClick={() => setSidebarOpen((open) => !open)}
              className={`flex h-10 w-full items-center rounded-DEFAULT text-on-surface-variant transition-colors hover:bg-surface-container hover:text-primary ${sidebarOpen ? 'gap-3 px-3' : 'justify-center px-0'}`}
              aria-label={sidebarOpen ? 'Collapse sidebar' : 'Expand sidebar'}
              aria-controls="app-sidebar"
              aria-expanded={sidebarOpen}
              title={sidebarOpen ? 'Collapse sidebar' : 'Expand sidebar'}
            >
              {sidebarOpen ? <PanelLeftClose size={18} /> : <PanelLeftOpen size={18} />}
              <span className={sidebarOpen ? 'inline truncate font-body-sm text-body-sm' : 'hidden'}>Collapse</span>
            </button>
          </div>
        </aside>
        {/* Main Content Area */}
        <main className={`relative flex h-full w-full flex-1 flex-col bg-surface-lowest transition-[margin] duration-200 ease-out ${sidebarOpen ? 'md:ml-[240px]' : 'md:ml-16'}`}>
          <header className="glass-shell z-10 flex min-h-14 flex-shrink-0 items-center border-b border-border-subtle bg-surface-base/95 px-4 py-2">
            <div className="flex w-full min-w-0 items-center justify-between gap-3">
              <div className="flex min-w-0 items-center gap-3">
                {selectedWorkflow ? (
                  <div className="flex min-w-0 items-center gap-2">
                    <span className={`h-2 w-2 shrink-0 rounded-full ${selectedWorkflow.status === 'Failed' ? 'bg-status-error' : selectedWorkflow.status === 'Paused' || selectedWorkflow.status === 'Archived' ? 'bg-on-surface-variant' : selectedWorkflow.status === 'Draft' ? 'bg-status-info' : 'bg-status-success'}`}></span>
                    <h1 className="truncate text-headline-md font-headline-md font-semibold text-primary">
                      {selectedWorkflow.name}
                    </h1>
                  </div>
                ) : (
                  <span className="font-headline-md text-headline-md font-semibold text-primary">
                    {activeShellView === 'dashboard' ? 'Dashboard' : 'Workflow Executions'}
                  </span>
                )}
              </div>

              {selectedWorkflow && (
                <div className="flex shrink-0 items-center gap-2 overflow-x-auto">
                  <div className="glass-control relative flex items-center rounded-DEFAULT border border-border-subtle bg-surface-container-low p-1">
                    <div
                      className="pill-tab-bg absolute bottom-1 top-1 w-24 rounded-DEFAULT border border-border-subtle bg-surface-container shadow-sm"
                      style={{ left: activeTab === 'visualizer' ? '4px' : activeTab === 'definition' ? '100px' : '196px' }}
                    ></div>
                    <button onClick={() => setActiveTab('visualizer')} className={`relative z-10 w-24 py-1.5 font-body-sm text-body-sm font-medium transition-colors ${activeTab === 'visualizer' ? 'text-primary' : 'text-on-surface-variant hover:text-primary'}`}>
                      Canvas
                    </button>
                    <button onClick={() => setActiveTab('definition')} className={`relative z-10 w-24 py-1.5 font-body-sm text-body-sm transition-colors ${activeTab === 'definition' ? 'text-primary font-medium' : 'text-on-surface-variant hover:text-primary'}`}>
                      Definition
                    </button>
                    <button onClick={() => setActiveTab('executions')} className={`relative z-10 w-24 py-1.5 font-body-sm text-body-sm transition-colors ${activeTab === 'executions' ? 'text-primary font-medium' : 'text-on-surface-variant hover:text-primary'}`}>
                      Executions
                    </button>
                  </div>

                  <button
                    type="button"
                    onClick={() => setRevisionPanelOpen((open) => !open)}
                    className={`flex h-9 items-center gap-2 rounded-DEFAULT border px-3 font-body-sm text-body-sm transition-colors ${revisionPanelOpen ? 'border-status-info bg-surface-container-high text-primary' : 'border-border-subtle bg-surface-elevated text-primary hover:bg-surface-container'}`}
                    aria-pressed={revisionPanelOpen}
                    aria-controls="revision-history-panel"
                    title="Revision history"
                  >
                    <span className="material-symbols-outlined text-[16px]">history</span>
                    <span>Revision</span>
                    <span className="font-mono-sm text-[11px] text-on-surface-variant">{selectedRevision?.label || 'Loading'}</span>
                  </button>
                  <button onClick={() => setActiveTab('executions')} className="flex h-9 items-center gap-2 rounded-DEFAULT border border-primary bg-primary px-3 font-body-sm text-body-sm font-medium text-surface-lowest transition-colors hover:bg-primary-fixed">
                    <span className="material-symbols-outlined text-[16px]">play_arrow</span>
                    Execute
                  </button>
                </div>
              )}
            </div>
          </header>

          {!selectedWorkflow ? (
            <div className="flex-1 min-h-0 overflow-hidden">
              {workflowListLoading ? (
                <WorkspaceState title="Loading workflows" message="Fetching workflow list from /app/v1/workflows." />
              ) : workflowListError ? (
                <WorkspaceState
                  title="Workflow API unavailable"
                  message={workflowListError}
                  action={{ label: 'Retry', onClick: loadWorkflows }}
                />
              ) : activeShellView === 'dashboard' ? (
                <WorkflowDashboard
                  workflows={workflowSummaries}
                  totalWorkflows={workflowPage?.totalElements}
                  onSelect={handleWorkflowSelected}
                />
              ) : (
                <WorkflowListView workflows={workflowSummaries} onSelect={handleWorkflowSelected} />
              )}
            </div>
          ) : (
          <div className="flex-1 flex overflow-hidden">
            {/* Left Pane: Primary workspace */}
            <div className="flex-1 relative bg-surface-lowest overflow-hidden">
               {workflowDetailLoading ? (
                 <WorkspaceState title="Loading workflow" message="Fetching workflow detail and revision history." />
               ) : workflowDetailError ? (
                 <WorkspaceState
                   title="Could not load workflow"
                   message={workflowDetailError}
                   action={{ label: 'Retry', onClick: () => setSelectedWorkflow({ ...selectedWorkflow }) }}
                 />
               ) : activeTab === 'executions' ? (
                 <ExecutionStatusView workflowName={selectedWorkflow.name} revisionLabel={selectedRevision?.label || 'No revision'} runs={selectedRevision?.runs || []} />
               ) : activeTab === 'visualizer' ? (
                 <AslGraphViewer
                   definition={currentDefinition}
                   selectedStateName={selectedStateName}
                   onStateSelect={handleStateSelect}
                 />
               ) : (
                 <AslCodeViewer definition={currentDefinition} />
               )}
               {activeTab === 'visualizer' && !detailsPanelOpen && !revisionPanelOpen && (
                 <button
                   type="button"
                   onClick={() => setDetailsPanelOpen(true)}
                   className="absolute right-4 top-1/2 z-40 hidden h-10 w-10 -translate-y-1/2 items-center justify-center rounded-DEFAULT border border-border-subtle bg-surface-container-highest/90 text-on-surface-variant shadow-lg backdrop-blur-xl transition-colors hover:border-border-muted hover:bg-surface-container hover:text-primary lg:flex"
                   aria-label="Open node details"
                   title="Open node details"
                 >
                   <PanelRightOpen size={18} />
                 </button>
               )}
            </div>

            {/* Right Pane */}
            {(revisionPanelOpen || activeTab === 'definition' || (activeTab === 'visualizer' && detailsPanelOpen)) && (
            <div
              id={revisionPanelOpen ? 'revision-history-panel' : undefined}
              className="glass-panel z-20 hidden w-[360px] flex-col border-l border-border-subtle bg-surface-base shadow-[-8px_0_24px_rgba(0,0,0,0.2)] lg:flex"
            >
               {revisionPanelOpen ? (
                 workflowDetailLoading ? (
                   <WorkspaceState title="Loading revisions" message="Fetching /revisions for this workflow." />
                 ) : workflowRevisions.length === 0 ? (
                   <WorkspaceState title="No revisions" message="This workflow does not have revision records yet." />
                 ) : (
                   <RevisionHistoryPanel
                     revisions={workflowRevisions}
                     selectedRevisionId={selectedRevision?.id || ''}
                     onRevisionSelected={handleRevisionIdSelected}
                     onClose={() => setRevisionPanelOpen(false)}
                   />
                 )
               ) : activeTab === 'visualizer' ? (
                 <NodeDetailsPanel
                   definition={currentDefinition}
                   selectedStateName={selectedStateName}
                   onClose={() => setDetailsPanelOpen(false)}
                 />
               ) : (
                 <WorkflowGeneratorPanel onWorkflowGenerated={handleWorkflowGenerated} />
               )}
            </div>
            )}
          </div>
          )}
        </main>
      </div>
    </div>
  );
}

export default App;
