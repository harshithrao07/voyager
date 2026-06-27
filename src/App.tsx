import { useEffect, useState } from 'react';
import { PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import type { WorkflowSummary } from './components/WorkflowListView';
import { WorkspaceState } from './components/WorkspaceState';
import { DashboardPage } from './pages/DashboardPage';
import { WorkflowsPage } from './pages/WorkflowsPage';
import { CreateWorkflowPage } from './pages/CreateWorkflowPage';
import { WorkflowDetailPage, type WorkflowRevision } from './pages/WorkflowDetailPage';
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

const emptyWorkflowPage: WorkflowPageDTO = {
  content: [],
  page: 0,
  size: 50,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
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

type AppRoute =
  | { page: 'dashboard' }
  | { page: 'workflows' }
  | { page: 'create' }
  | { page: 'workflow'; workflowId: string };

function parseRoute(pathname: string): AppRoute {
  const normalized = pathname.replace(/\/+$/, '') || '/';
  if (normalized === '/') return { page: 'create' };
  if (normalized === '/dashboard') return { page: 'dashboard' };
  if (normalized === '/workflows/new') return { page: 'create' };
  if (normalized === '/workflows') return { page: 'workflows' };

  const workflowMatch = normalized.match(/^\/workflows\/([^/]+)$/);
  if (workflowMatch?.[1]) {
    return { page: 'workflow', workflowId: decodeURIComponent(workflowMatch[1]) };
  }

  return { page: 'workflows' };
}

function workflowPath(workflowId: string) {
  return `/workflows/${encodeURIComponent(workflowId)}`;
}

function App() {
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [detailsPanelOpen, setDetailsPanelOpen] = useState(false);
  const [revisionPanelOpen, setRevisionPanelOpen] = useState(false);
  const [route, setRoute] = useState<AppRoute>(() => parseRoute(window.location.pathname));
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
  const navigate = (path: string) => {
    if (window.location.pathname !== path) {
      window.history.pushState({}, '', path);
    }
    setRoute(parseRoute(path));
  };

  useEffect(() => {
    const handlePopState = () => setRoute(parseRoute(window.location.pathname));
    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, []);

  const loadWorkflows = () => {
    setWorkflowListLoading(true);
    setWorkflowListError(null);

    listWorkflows({ page: 0, size: 50 })
      .then((page) => {
        setWorkflowPage(page);
        setWorkflowSummaries(page.content.map(mapWorkflowSummary));
      })
      .catch((error: Error) => {
        console.warn('Workflow API unavailable, showing empty workflow list.', error);
        setWorkflowPage(emptyWorkflowPage);
        setWorkflowSummaries([]);
        setWorkflowListError(null);
      })
      .finally(() => setWorkflowListLoading(false));
  };

  useEffect(() => {
    loadWorkflows();
  }, []);

  useEffect(() => {
    if (route.page !== 'workflow') {
      setSelectedWorkflow(null);
      setSelectedRevisionId('');
      setDetailsPanelOpen(false);
      setRevisionPanelOpen(false);
      return;
    }

    const workflowFromList = workflowSummaries.find((workflow) => workflow.id === route.workflowId);
    const fallbackWorkflow: WorkflowSummary = {
      id: route.workflowId,
      name: workflowDetail?.id === route.workflowId ? workflowDetail.name : 'Workflow',
      status: workflowDetail?.id === route.workflowId ? formatWorkflowStatus(workflowDetail.status) : 'Draft',
      schedule: workflowDetail?.id === route.workflowId ? formatSchedule(workflowDetail.cronExpression, workflowDetail.timezone) : '-',
      nextRun: workflowDetail?.id === route.workflowId && workflowDetail.nextRunAt ? `Next: ${formatDateTime(workflowDetail.nextRunAt)}` : '-',
      description: workflowDetail?.id === route.workflowId ? workflowDescription(workflowDetail) : 'Loading workflow detail.',
    };

    setSelectedWorkflow((current) => {
      const next = workflowFromList || fallbackWorkflow;
      if (
        current?.id === next.id &&
        current.name === next.name &&
        current.status === next.status &&
        current.schedule === next.schedule &&
        current.nextRun === next.nextRun &&
        current.description === next.description
      ) {
        return current;
      }
      return next;
    });
  }, [route, workflowSummaries, workflowDetail]);

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
    setSelectedRevisionId('');
    setDetailsPanelOpen(false);
    setRevisionPanelOpen(false);
    setActiveTab('visualizer');
    navigate(workflowPath(workflow.id));
  };

  const handleCreateWorkflow = () => {
    setSelectedWorkflow(null);
    setRevisionPanelOpen(false);
    setDetailsPanelOpen(false);
    navigate('/workflows/new');
  };

  const handleWorkflowCreated = (workflow: WorkflowResponseDTO) => {
    const summary = mapWorkflowSummary(workflow);
    setWorkflowPage((current) => current ? {
      ...current,
      content: [workflow, ...current.content.filter((item) => item.id !== workflow.id)],
      totalElements: current.totalElements + (current.content.some((item) => item.id === workflow.id) ? 0 : 1),
    } : {
      ...emptyWorkflowPage,
      content: [workflow],
      totalElements: 1,
      totalPages: 1,
      last: true,
    });
    setWorkflowSummaries((current) => [summary, ...current.filter((item) => item.id !== summary.id)]);
    setSelectedWorkflow(summary);
    setSelectedRevisionId('');
    setDetailsPanelOpen(false);
    setRevisionPanelOpen(false);
    setActiveTab('visualizer');
    navigate(workflowPath(summary.id));
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

  const createViewActive = route.page === 'create';

  return (
    <div className="font-body-sm text-body-sm overflow-hidden bg-surface-base selection:bg-primary/30 selection:text-primary">
      <div className="flex h-screen">
        <aside
          id="app-sidebar"
          className={`fixed left-0 top-0 z-40 hidden h-screen flex-col border-r border-border-subtle bg-surface-container-lowest transition-[width] duration-200 ease-out md:flex ${sidebarOpen ? 'w-sidebar-width' : 'w-16'}`}
          aria-label={sidebarOpen ? 'Expanded navigation' : 'Collapsed navigation'}
        >
          <div className={`flex border-b border-border-subtle px-3 ${sidebarOpen ? 'h-24 flex-col justify-center' : 'h-16 items-center justify-center px-2'}`}>
            {sidebarOpen ? (
              <>
                <button
                  type="button"
                  onClick={() => {
                    setSelectedWorkflow(null);
                    setRevisionPanelOpen(false);
                    navigate('/');
                  }}
                  className="flex items-center gap-1.5 text-left"
                  title="Voyager"
                >
                  <img src="/voyager-logo.svg" alt="" className="h-12 w-12 shrink-0" />
                  <span className="font-mono-sm text-[24px] font-semibold leading-none tracking-normal text-primary">Voyager</span>
                </button>
              </>
            ) : (
              <button
                type="button"
                onClick={() => setSidebarOpen(true)}
                className="flex h-9 w-9 items-center justify-center rounded-DEFAULT border border-border-subtle bg-surface-container-lowest"
                title="Expand sidebar"
              >
                <img src="/voyager-logo.svg" alt="" className="h-9 w-9" />
              </button>
            )}
          </div>
          <div className="flex-1 space-y-1 overflow-y-auto px-1 py-4">
            <button
              onClick={handleCreateWorkflow}
              className={`relative flex h-10 w-full items-center rounded-DEFAULT font-mono-sm text-label-mono transition-colors ${sidebarOpen ? 'gap-3 px-2' : 'justify-center px-0'} ${route.page === 'create' ? 'bg-surface-container-low text-on-surface' : 'text-on-surface-variant hover:bg-surface-container-low hover:text-on-surface'}`}
              aria-label="New Workflow"
              title="New Workflow"
              type="button"
            >
              <span className="material-symbols-outlined shrink-0 text-[20px]">add</span>
              {route.page === 'create' && <span className="absolute left-0 top-1/2 h-4 w-0.5 -translate-y-1/2 rounded-r-full bg-primary" />}
              <span className={sidebarOpen ? 'inline truncate' : 'hidden'}>New Workflow</span>
            </button>
            <button
              onClick={() => navigate('/dashboard')}
              className={`relative flex h-10 w-full items-center rounded-DEFAULT font-mono-sm text-label-mono transition-colors ${sidebarOpen ? 'gap-3 px-2' : 'justify-center px-0'} ${route.page === 'dashboard' ? 'bg-surface-container-low text-on-surface' : 'text-on-surface-variant hover:bg-surface-container-low hover:text-on-surface'}`}
              aria-label="Dashboard"
              title="Dashboard"
            >
              <span className="material-symbols-outlined shrink-0 text-[20px]">dashboard</span>
              {route.page === 'dashboard' && <span className="absolute left-0 top-1/2 h-4 w-0.5 -translate-y-1/2 rounded-r-full bg-primary" />}
              <span className={sidebarOpen ? 'inline truncate' : 'hidden'}>Dashboard</span>
            </button>
            <button
              onClick={() => navigate('/workflows')}
              className={`relative flex h-10 w-full items-center rounded-DEFAULT font-mono-sm text-label-mono transition-colors ${sidebarOpen ? 'gap-3 px-2' : 'justify-center px-0'} ${route.page === 'workflows' || route.page === 'workflow' ? 'bg-surface-container-low text-on-surface' : 'text-on-surface-variant hover:bg-surface-container-low hover:text-on-surface'}`}
              aria-label="Workflows"
              title="Workflows"
            >
              <span className="material-symbols-outlined shrink-0 text-[20px]">account_tree</span>
              {(route.page === 'workflows' || route.page === 'workflow') && <span className="absolute left-0 top-1/2 h-4 w-0.5 -translate-y-1/2 rounded-r-full bg-primary" />}
              <span className={sidebarOpen ? 'inline truncate' : 'hidden'}>Workflows</span>
            </button>
            <button
              onClick={() => selectedWorkflow && setActiveTab('executions')}
              className={`flex h-10 w-full items-center rounded-DEFAULT font-mono-sm text-label-mono text-on-surface-variant transition-colors hover:bg-surface-container-low hover:text-on-surface ${sidebarOpen ? 'gap-3 px-2' : 'justify-center px-0'}`}
              aria-label="Executions"
              title="Executions"
            >
              <span className="material-symbols-outlined shrink-0 text-[20px]">play_circle</span>
              <span className={sidebarOpen ? 'inline truncate' : 'hidden'}>Executions</span>
            </button>
            <button className={`flex h-10 w-full items-center rounded-DEFAULT font-mono-sm text-label-mono text-on-surface-variant transition-colors hover:bg-surface-container-low hover:text-on-surface ${sidebarOpen ? 'gap-3 px-2' : 'justify-center px-0'}`} aria-label="Workers" title="Workers">
              <span className="material-symbols-outlined shrink-0 text-[20px]">groups</span>
              <span className={sidebarOpen ? 'inline truncate' : 'hidden'}>Workers</span>
            </button>
            <button className={`flex h-10 w-full items-center rounded-DEFAULT font-mono-sm text-label-mono text-on-surface-variant transition-colors hover:bg-surface-container-low hover:text-on-surface ${sidebarOpen ? 'gap-3 px-2' : 'justify-center px-0'}`} aria-label="Schedules" title="Schedules">
              <span className="material-symbols-outlined shrink-0 text-[20px]">event_repeat</span>
              <span className={sidebarOpen ? 'inline truncate' : 'hidden'}>Schedules</span>
            </button>
            <div className="mx-2 my-3 h-px bg-border-subtle" />
            <button className={`flex h-10 w-full items-center rounded-DEFAULT font-mono-sm text-label-mono text-on-surface-variant transition-colors hover:bg-surface-container-low hover:text-on-surface ${sidebarOpen ? 'gap-3 px-2' : 'justify-center px-0'}`} aria-label="Docs" title="Docs">
              <span className="material-symbols-outlined shrink-0 text-[20px]">description</span>
              <span className={sidebarOpen ? 'inline truncate' : 'hidden'}>Docs</span>
            </button>
            <button className={`flex h-10 w-full items-center rounded-DEFAULT font-mono-sm text-label-mono text-on-surface-variant transition-colors hover:bg-surface-container-low hover:text-on-surface ${sidebarOpen ? 'gap-3 px-2' : 'justify-center px-0'}`} aria-label="Settings" title="Settings">
              <span className="material-symbols-outlined shrink-0 text-[20px]">settings</span>
              <span className={sidebarOpen ? 'inline truncate' : 'hidden'}>Settings</span>
            </button>
          </div>
          <div className="mt-auto space-y-1 border-t border-border-subtle px-1 py-4">
            <button className={`flex h-9 w-full items-center rounded-DEFAULT font-mono-sm text-[13px] text-on-surface-variant transition-colors hover:bg-surface-container-low hover:text-on-surface ${sidebarOpen ? 'gap-3 px-2' : 'justify-center px-0'}`} type="button">
              <span className="material-symbols-outlined shrink-0 text-[19px]">help</span>
              <span className={sidebarOpen ? 'inline truncate' : 'hidden'}>Help</span>
            </button>
            <button className={`flex h-9 w-full items-center rounded-DEFAULT font-mono-sm text-[13px] text-on-surface-variant transition-colors hover:bg-surface-container-low hover:text-on-surface ${sidebarOpen ? 'gap-3 px-2' : 'justify-center px-0'}`} type="button">
              <span className="material-symbols-outlined shrink-0 text-[19px]">monitoring</span>
              <span className={sidebarOpen ? 'inline truncate' : 'hidden'}>Status</span>
              {sidebarOpen && <span className="ml-auto h-2 w-2 rounded-full bg-secondary" />}
            </button>
            <button
              type="button"
              onClick={() => setSidebarOpen((open) => !open)}
              className={`flex h-9 w-full items-center rounded-DEFAULT font-mono-sm text-[13px] text-on-surface-variant transition-colors hover:bg-surface-container-low hover:text-on-surface ${sidebarOpen ? 'gap-3 px-2' : 'justify-center px-0'}`}
              aria-label={sidebarOpen ? 'Collapse sidebar' : 'Expand sidebar'}
              aria-controls="app-sidebar"
              aria-expanded={sidebarOpen}
              title={sidebarOpen ? 'Collapse sidebar' : 'Expand sidebar'}
            >
              {sidebarOpen ? <PanelLeftClose size={18} /> : <PanelLeftOpen size={18} />}
              <span className={sidebarOpen ? 'inline truncate' : 'hidden'}>admin</span>
            </button>
          </div>
        </aside>
        {/* Main Content Area */}
        <main className={`voyager-main-bg relative flex h-full w-full flex-1 flex-col transition-[margin] duration-200 ease-out ${sidebarOpen ? 'md:ml-[260px]' : 'md:ml-16'}`}>
          {!createViewActive && (
          <header className="glass-shell z-10 flex min-h-14 flex-shrink-0 items-center px-4 py-2">
            <div className="flex w-full min-w-0 items-center justify-between gap-3">
              <div className="flex min-w-0 items-center gap-3">
                {selectedWorkflow ? (
                  <div className="flex min-w-0 items-center gap-2">
                    <span className={`h-2 w-2 shrink-0 rounded-full ${selectedWorkflow.status === 'Failed' ? 'bg-status-error' : selectedWorkflow.status === 'Paused' || selectedWorkflow.status === 'Archived' ? 'bg-on-surface-variant' : selectedWorkflow.status === 'Draft' ? 'bg-status-info' : 'bg-status-success'}`}></span>
                    <h1 className="truncate font-display text-[20px] font-semibold leading-7 text-primary">
                      {selectedWorkflow.name}
                    </h1>
                  </div>
                ) : (
                  <span className="font-display text-[20px] font-semibold leading-7 text-primary">
                {route.page === 'dashboard' ? 'Dashboard' : 'Workflow Executions'}
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
          )}

          {route.page !== 'workflow' ? (
            <div className="flex-1 min-h-0 overflow-hidden">
              {route.page === 'create' ? (
                <CreateWorkflowPage onWorkflowCreated={handleWorkflowCreated} />
              ) : workflowListLoading ? (
                <WorkspaceState title="Loading workflows" message="Fetching workflow list from /app/v1/workflows." />
              ) : workflowListError ? (
                <WorkspaceState
                  title="Workflow API unavailable"
                  message={workflowListError}
                  action={{ label: 'Retry', onClick: loadWorkflows }}
                />
              ) : route.page === 'dashboard' ? (
                <DashboardPage
                  workflows={workflowSummaries}
                  totalWorkflows={workflowPage?.totalElements}
                  onSelect={handleWorkflowSelected}
                />
              ) : (
                <WorkflowsPage workflows={workflowSummaries} onSelect={handleWorkflowSelected} />
              )}
            </div>
          ) : (
          <WorkflowDetailPage
            workflowName={workflowDetail?.name || selectedWorkflow?.name || 'Workflow'}
            workflowDetail={workflowDetail}
            workflowRevisions={workflowRevisions}
            workflowDetailLoading={workflowDetailLoading}
            workflowDetailError={workflowDetailError}
            activeTab={activeTab}
            selectedRevision={selectedRevision}
            currentDefinition={currentDefinition}
            selectedStateName={selectedStateName}
            detailsPanelOpen={detailsPanelOpen}
            revisionPanelOpen={revisionPanelOpen}
            onRetry={() => selectedWorkflow && setSelectedWorkflow({ ...selectedWorkflow })}
            onStateSelect={handleStateSelect}
            onOpenDetails={() => setDetailsPanelOpen(true)}
            onCloseDetails={() => setDetailsPanelOpen(false)}
            onRevisionSelected={handleRevisionIdSelected}
            onCloseRevisionPanel={() => setRevisionPanelOpen(false)}
            onWorkflowGenerated={handleWorkflowGenerated}
          />
          )}
        </main>
      </div>
    </div>
  );
}

export default App;
