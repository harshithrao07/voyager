import { useCallback, useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';
import type { WorkflowSummary } from './components/WorkflowListView';
import { WorkspaceState } from './components/WorkspaceState';
import { WorkflowSettingsPanel } from './components/WorkflowSettingsPanel';
import { WorkflowsPage } from './pages/WorkflowsPage';
import { CreateWorkflowPage } from './pages/CreateWorkflowPage';
import { SettingsPage } from './pages/SettingsPage';
import { FunctionsPage } from './pages/FunctionsPage';
import { McpServersPage } from './pages/McpServersPage';
import { WorkflowDetailPage, type WorkflowRevision } from './pages/WorkflowDetailPage';
import {
  activateWorkflowRevision,
  getWorkflow,
  getWorkflowRevisions,
  listWorkflows,
  listWorkflowAiConversations,
  updateWorkflowCanvasLayout,
  type WorkflowAiConversationSummaryDTO,
  type WorkflowDefinitionResponseDTO,
  type WorkflowPageDTO,
  type WorkflowResponseDTO,
  type WorkflowStatusDTO,
} from './api';
import type { CanvasNodePositions } from './types/workflowCanvas';
import { mergeCanvasPositionsForScope } from './components/workflow-create/nestedMachine';

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

function formatChatTime(value?: string | null) {
  if (!value) return '';

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';

  return new Intl.DateTimeFormat(undefined, {
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

function formatChatDate(value?: string | null) {
  if (!value) return 'Unknown date';

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Unknown date';

  return new Intl.DateTimeFormat(undefined, {
    weekday: 'short',
    month: 'short',
    day: '2-digit',
    year: 'numeric',
  }).format(date);
}

function formatChatDateTime(value?: string | null) {
  if (!value) return '';

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';

  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

function chatSortTime(chat: WorkflowAiConversationSummaryDTO) {
  const date = new Date(chat.updatedAt || chat.createdAt || '');
  return Number.isNaN(date.getTime()) ? 0 : date.getTime();
}

function compactChatTitle(chat: WorkflowAiConversationSummaryDTO) {
  const title = chat.name?.trim() || chat.initialInstruction?.trim() || 'New workflow chat';
  return title.replace(/\s+/g, ' ');
}

function formatSchedule(cronExpression?: string | null, timezone?: string | null) {
  if (!cronExpression) return 'Trigger-based';
  return timezone ? `${cronExpression} - ${timezone}` : cronExpression;
}

function workflowDescription(workflow: WorkflowResponseDTO) {
  const revision = workflow.activeDefinition?.revision ? `Rev ${workflow.activeDefinition.revision}` : 'No active revision';
  return `${workflow.maxAttempts} max attempts - ${revision}`;
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
    canvasLayout: revision.canvasLayout || {},
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
    canvasLayout: {},
  };
}

function ChatHistoryModal({
  chats,
  activeChatId,
  onClose,
  onSelect,
}: {
  chats: WorkflowAiConversationSummaryDTO[];
  activeChatId?: string;
  onClose: () => void;
  onSelect: (chat: WorkflowAiConversationSummaryDTO) => void;
}) {
  let currentDate = '';

  return (
    <div
      className="fixed inset-0 z-50 flex cursor-pointer items-start justify-center bg-background/80 px-4 pt-[12vh] backdrop-blur-sm"
      onClick={onClose}
    >
      <section
        className="glass-shell w-full max-w-2xl cursor-default overflow-hidden rounded-xl border border-border-subtle bg-surface-container-lowest shadow-[0_26px_90px_rgba(0,0,0,0.5)]"
        onClick={(event) => event.stopPropagation()}
        aria-modal="true"
        role="dialog"
        aria-label="All chats"
      >
        <header className="flex items-center justify-between border-b border-border-subtle px-4 py-3">
          <div>
            <h2 className="font-mono-sm text-[12px] font-semibold text-on-surface">All chats</h2>
            <p className="mt-0.5 text-body-sm text-on-surface-variant">Sorted by latest activity</p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="flex h-8 w-8 items-center justify-center rounded-lg border border-border-subtle bg-surface-container-low text-on-surface-variant transition-colors hover:text-on-surface"
            aria-label="Close all chats"
            title="Close"
          >
            <span className="material-symbols-outlined text-[17px]">close</span>
          </button>
        </header>
        <div className="max-h-[68vh] overflow-y-auto px-2 py-2">
          {chats.length === 0 ? (
            <div className="px-4 py-10 text-center text-body-sm text-on-surface-variant">No chats yet.</div>
          ) : chats.map((chat) => {
            const activityAt = chat.updatedAt || chat.createdAt;
            const dateLabel = formatChatDate(activityAt);
            const showDate = dateLabel !== currentDate;
            currentDate = dateLabel;
            const title = compactChatTitle(chat);
            const active = chat.id === activeChatId;

            return (
              <div key={chat.id}>
                {showDate && (
                  <div className="px-2 pb-1 pt-3 font-mono-sm text-[10px] uppercase tracking-[0.12em] text-on-surface-variant">
                    {dateLabel}
                  </div>
                )}
                <button
                  type="button"
                  onClick={() => onSelect(chat)}
                  className={`group flex min-h-[52px] w-full items-center gap-3 rounded-lg px-3 py-2 text-left transition-colors ${
                    active
                      ? 'bg-primary/10 text-on-surface'
                      : 'text-on-surface-variant hover:bg-surface-container-low hover:text-on-surface'
                  }`}
                  title={title}
                >
                  <span className="material-symbols-outlined shrink-0 text-[17px] text-secondary">smart_toy</span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-mono-sm text-[11px] text-on-surface">{title}</span>
                    <span className="mt-0.5 block truncate text-body-sm text-on-surface-variant">
                      {chat.modelDisplayName || 'AI model'}
                    </span>
                  </span>
                  <span className="shrink-0 font-mono-sm text-[10px] text-on-surface-variant">
                    {formatChatDateTime(activityAt)}
                  </span>
                </button>
              </div>
            );
          })}
        </div>
      </section>
    </div>
  );
}

type AppRoute =
  | { page: 'workflows' }
  | { page: 'create' }
  | { page: 'chat'; chatId: string }
  | { page: 'workflow'; workflowId: string }
  | { page: 'workflow-edit'; workflowId: string; revision: number }
  | { page: 'settings' }
  | { page: 'functions' }
  | { page: 'mcp' };

function parseRoute(pathname: string): AppRoute {
  const normalized = pathname.replace(/\/+$/, '') || '/';
  if (normalized === '/') return { page: 'create' };
  if (normalized === '/workflows/new') return { page: 'create' };
  if (normalized === '/workflows') return { page: 'workflows' };
  if (normalized === '/settings') return { page: 'settings' };
  if (normalized === '/functions') return { page: 'functions' };
  if (normalized === '/mcp') return { page: 'mcp' };

  const chatMatch = normalized.match(/^\/c\/([^/]+)$/);
  if (chatMatch?.[1]) {
    return { page: 'chat', chatId: decodeURIComponent(chatMatch[1]) };
  }

  const workflowEditMatch = normalized.match(/^\/workflows\/([^/]+)\/revisions\/(\d+)\/edit$/);
  if (workflowEditMatch?.[1] && workflowEditMatch[2]) {
    return {
      page: 'workflow-edit',
      workflowId: decodeURIComponent(workflowEditMatch[1]),
      revision: Number(workflowEditMatch[2]),
    };
  }

  const workflowMatch = normalized.match(/^\/workflows\/([^/]+)$/);
  if (workflowMatch?.[1]) {
    return { page: 'workflow', workflowId: decodeURIComponent(workflowMatch[1]) };
  }

  return { page: 'workflows' };
}

function workflowPath(workflowId: string) {
  return `/workflows/${encodeURIComponent(workflowId)}`;
}

function workflowRevisionEditPath(workflowId: string, revision: number) {
  return `${workflowPath(workflowId)}/revisions/${revision}/edit`;
}

function canonicalPath(pathname: string) {
  const normalized = pathname.replace(/\/+$/, '') || '/';
  if (normalized === '/workflows/new') return '/';
  if (normalized === '/dashboard') return '/workflows';
  return pathname;
}

function App() {
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [chatsOpen, setChatsOpen] = useState(false);
  const [chatHistoryModalOpen, setChatHistoryModalOpen] = useState(false);
  const [searchModalOpen, setSearchModalOpen] = useState(false);
  const [detailsPanelOpen, setDetailsPanelOpen] = useState(false);
  const [revisionPanelOpen, setRevisionPanelOpen] = useState(false);
  const [workflowSettingsOpen, setWorkflowSettingsOpen] = useState(false);
  const [newWorkflowResetKey, setNewWorkflowResetKey] = useState(0);
  const [route, setRoute] = useState<AppRoute>(() => parseRoute(window.location.pathname));
  const [functionsWorkbenchActive, setFunctionsWorkbenchActive] = useState(false);
  const [workflowPage, setWorkflowPage] = useState<WorkflowPageDTO | null>(null);
  const [workflowSummaries, setWorkflowSummaries] = useState<WorkflowSummary[]>([]);
  const [chatSummaries, setChatSummaries] = useState<WorkflowAiConversationSummaryDTO[]>([]);
  const [workflowListLoading, setWorkflowListLoading] = useState(true);
  const [workflowListError, setWorkflowListError] = useState<string | null>(null);
  const [workflowDetail, setWorkflowDetail] = useState<WorkflowResponseDTO | null>(null);
  const [workflowRevisions, setWorkflowRevisions] = useState<WorkflowRevision[]>([]);
  const canvasLayoutSaveQueueRef = useRef<Promise<void>>(Promise.resolve());
  const workflowEditDirtyRef = useRef(false);
  const workflowSettingsDirtyRef = useRef(false);
  const preferredWorkflowRevisionRef = useRef<{ workflowId: string; revisionId: string } | null>(null);
  const currentPathRef = useRef(canonicalPath(window.location.pathname));
  const [workflowDetailLoading, setWorkflowDetailLoading] = useState(false);
  const [workflowDetailError, setWorkflowDetailError] = useState<string | null>(null);
  const [workflowActionBusy, setWorkflowActionBusy] = useState(false);
  const [workflowDef, setWorkflowDef] = useState<any>(activeDefinition);
  const [activeTab, setActiveTab] = useState<'visualizer' | 'definition' | 'executions'>('visualizer');
  const [selectedStateName, setSelectedStateName] = useState('Fetch Source Data');
  const [selectedWorkflow, setSelectedWorkflow] = useState<WorkflowSummary | null>(null);
  const selectedWorkflowId = selectedWorkflow?.id ?? null;
  const [selectedRevisionId, setSelectedRevisionId] = useState('');
  const selectedRevision =
    workflowRevisions.find((revision) => revision.id === selectedRevisionId) ||
    workflowRevisions.find((revision) => revision.active) ||
    workflowRevisions[0] ||
    null;
  const revisionEditBase = route.page === 'workflow-edit'
    ? workflowRevisions.find((revision) => revision.id === String(route.revision)) || null
    : null;
  const currentDefinition = selectedRevision?.definition || workflowDetail?.activeDefinition?.definition || workflowDef;
  const handleWorkflowEditDirtyChange = useCallback((dirty: boolean) => {
    workflowEditDirtyRef.current = dirty;
  }, []);
  const handleWorkflowSettingsDirtyChange = useCallback((dirty: boolean) => {
    workflowSettingsDirtyRef.current = dirty;
  }, []);
  const navigate = (path: string, options: { replace?: boolean } = {}) => {
    const nextPath = canonicalPath(path);
    if (workflowEditDirtyRef.current && currentPathRef.current !== nextPath) {
      const discard = window.confirm('Discard the unsaved workflow revision changes?');
      if (!discard) return;
      workflowEditDirtyRef.current = false;
    }
    if (workflowSettingsDirtyRef.current && currentPathRef.current !== nextPath) {
      const discard = window.confirm('Discard the unsaved workflow settings?');
      if (!discard) return;
      workflowSettingsDirtyRef.current = false;
      setWorkflowSettingsOpen(false);
    }
    if (window.location.pathname !== nextPath) {
      if (options.replace) {
        window.history.replaceState({}, '', nextPath);
      } else {
        window.history.pushState({}, '', nextPath);
      }
    }
    currentPathRef.current = nextPath;
    setRoute(parseRoute(nextPath));
  };

  useEffect(() => {
    const currentPath = canonicalPath(window.location.pathname);
    if (currentPath !== window.location.pathname) {
      window.history.replaceState({}, '', currentPath);
      setRoute(parseRoute(currentPath));
    }
    currentPathRef.current = currentPath;

    const handlePopState = () => {
      const path = canonicalPath(window.location.pathname);
      if (workflowEditDirtyRef.current && currentPathRef.current !== path) {
        const discard = window.confirm('Discard the unsaved workflow revision changes?');
        if (!discard) {
          window.history.pushState({}, '', currentPathRef.current);
          return;
        }
        workflowEditDirtyRef.current = false;
      }
      if (workflowSettingsDirtyRef.current && currentPathRef.current !== path) {
        const discard = window.confirm('Discard the unsaved workflow settings?');
        if (!discard) {
          window.history.pushState({}, '', currentPathRef.current);
          return;
        }
        workflowSettingsDirtyRef.current = false;
        setWorkflowSettingsOpen(false);
      }
      if (path !== window.location.pathname) {
        window.history.replaceState({}, '', path);
      }
      currentPathRef.current = path;
      setRoute(parseRoute(path));
    };
    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, []);

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setSearchModalOpen(true);
        return;
      }

      if (e.key === 'Escape') {
        setSearchModalOpen(false);
        setChatHistoryModalOpen(false);
        setDetailsPanelOpen(false);
        setRevisionPanelOpen(false);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
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

  const loadChats = () => {
    listWorkflowAiConversations()
      .then(setChatSummaries)
      .catch((error: Error) => {
        console.warn('Workflow AI chat history unavailable.', error);
      });
  };

  useEffect(() => {
    loadChats();
  }, []);

  useEffect(() => {
    if (route.page !== 'functions') {
      setFunctionsWorkbenchActive(false);
    }
  }, [route.page]);

  useEffect(() => {
    if (route.page !== 'workflow') {
      setWorkflowSettingsOpen(false);
    }
  }, [route.page]);

  useEffect(() => {
    if (route.page !== 'workflow' && route.page !== 'workflow-edit') {
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
    if (!selectedWorkflowId) {
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
      getWorkflow({ workflowId: selectedWorkflowId }),
      getWorkflowRevisions({ workflowId: selectedWorkflowId }),
    ])
      .then(([detail, revisionDtos]) => {
        if (cancelled) return;

        const revisions = revisionDtos.map(mapWorkflowRevision);
        const requestedRevision = route.page === 'workflow-edit'
          ? revisions.find((revision) => revision.id === String(route.revision)) || null
          : null;
        const preferredRevision = preferredWorkflowRevisionRef.current?.workflowId === selectedWorkflowId
          ? revisions.find((revision) => revision.id === preferredWorkflowRevisionRef.current?.revisionId) || null
          : null;
        const displayedRevision =
          requestedRevision ||
          preferredRevision ||
          revisions.find((revision) => revision.active) ||
          (detail.activeDefinition ? mapWorkflowRevision(detail.activeDefinition) : null) ||
          revisions[0] ||
          null;
        const nextDefinition = displayedRevision?.definition || detail.activeDefinition?.definition || activeDefinition;

        if (preferredRevision) {
          preferredWorkflowRevisionRef.current = null;
        }

        setWorkflowDetail(detail);
        setWorkflowRevisions(revisions.length > 0 ? revisions : displayedRevision ? [displayedRevision] : []);
        setSelectedRevisionId(displayedRevision?.id || '');
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
  }, [selectedWorkflowId, route]);

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
    setNewWorkflowResetKey((key) => key + 1);
    navigate('/');
  };

  const upsertChatSummary = (
    previousId: string | null,
    chat: WorkflowAiConversationSummaryDTO,
  ) => {
    setChatSummaries((current) => {
      const filtered = current.filter((item) => (
        item.id !== chat.id && (!previousId || item.id !== previousId)
      ));
      return [chat, ...filtered].sort((left, right) => (
        new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime()
      ));
    });
  };

  const handleChatSelected = (chat: WorkflowAiConversationSummaryDTO) => {
    setSelectedWorkflow(null);
    setRevisionPanelOpen(false);
    setDetailsPanelOpen(false);
    setChatHistoryModalOpen(false);
    navigate(`/c/${chat.id}`);
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

  const handleWorkflowUpdated = useCallback((updatedWorkflow: WorkflowResponseDTO) => {
    const summary = mapWorkflowSummary(updatedWorkflow);
    setWorkflowDetail(updatedWorkflow);
    setSelectedWorkflow(summary);
    setWorkflowSummaries((current) => current.map((workflow) => (
      workflow.id === summary.id ? summary : workflow
    )));
    setWorkflowPage((current) => current ? {
      ...current,
      content: current.content.map((workflow) => (
        workflow.id === updatedWorkflow.id ? updatedWorkflow : workflow
      )),
    } : current);
  }, []);

  const handleWorkflowArchived = useCallback((archivedWorkflow: WorkflowResponseDTO) => {
    handleWorkflowUpdated(archivedWorkflow);
    workflowSettingsDirtyRef.current = false;
    setWorkflowSettingsOpen(false);
  }, [handleWorkflowUpdated]);

  const handleWorkflowRevisionSaved = async (
    workflowId: string,
    savedRevision: WorkflowDefinitionResponseDTO,
  ) => {
    const locallyMappedRevision = mapWorkflowRevision(savedRevision);
    preferredWorkflowRevisionRef.current = {
      workflowId,
      revisionId: locallyMappedRevision.id,
    };
    setWorkflowRevisions((current) => [
      locallyMappedRevision,
      ...current
        .filter((revision) => revision.id !== locallyMappedRevision.id)
        .map((revision) => savedRevision.active ? { ...revision, active: false } : revision),
    ]);
    setSelectedRevisionId(locallyMappedRevision.id);
    setWorkflowDef(locallyMappedRevision.definition);
    setSelectedStateName(getStartState(locallyMappedRevision.definition));
    setDetailsPanelOpen(false);
    setRevisionPanelOpen(false);
    setActiveTab('visualizer');

    try {
      const [detail, revisionDtos] = await Promise.all([
        getWorkflow({ workflowId }),
        getWorkflowRevisions({ workflowId }),
      ]);
      const revisions = revisionDtos.map(mapWorkflowRevision);
      const refreshedRevision = revisions.find((revision) => revision.id === String(savedRevision.revision))
        || locallyMappedRevision;
      const summary = mapWorkflowSummary(detail);

      setWorkflowDetail(detail);
      setWorkflowRevisions(revisions);
      setSelectedRevisionId(refreshedRevision.id);
      setWorkflowDef(refreshedRevision.definition);
      setSelectedStateName(getStartState(refreshedRevision.definition));
      setSelectedWorkflow(summary);
      setWorkflowSummaries((current) => [summary, ...current.filter((item) => item.id !== summary.id)]);
      setWorkflowPage((current) => current ? {
        ...current,
        content: current.content.map((workflow) => workflow.id === detail.id ? detail : workflow),
      } : current);
    } catch (error) {
      toast.error(error instanceof Error
        ? `Revision saved, but workflow details could not be refreshed: ${error.message}`
        : 'Revision saved, but workflow details could not be refreshed.');
    }
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

  const handleCanvasLayoutChange = useCallback((positions: CanvasNodePositions) => {
    if (!selectedRevision) return;

    const selectedRevisionNumber = Number(selectedRevision.id);
    const mergedPositions = mergeCanvasPositionsForScope(
      selectedRevision.canvasLayout,
      [],
      positions,
    );
    setWorkflowRevisions((current) => current.map((revision) => (
      revision.id === selectedRevision.id ? { ...revision, canvasLayout: mergedPositions } : revision
    )));

    if (!workflowDetail || !Number.isSafeInteger(selectedRevisionNumber) || selectedRevisionNumber < 1) {
      return;
    }

    const workflowId = workflowDetail.id;
    canvasLayoutSaveQueueRef.current = canvasLayoutSaveQueueRef.current
      .catch(() => undefined)
      .then(async () => {
        try {
          await updateWorkflowCanvasLayout(workflowId, selectedRevisionNumber, mergedPositions);
        } catch (error) {
          toast.error(error instanceof Error ? error.message : 'Could not save the canvas layout.');
        }
      });
  }, [selectedRevision, workflowDetail]);

  const handleActivateSchedule = async () => {
    if (!workflowDetail || !workflowDetail.cronExpression || !selectedRevision) {
      return;
    }

    const revision = Number(selectedRevision.id);
    if (!Number.isSafeInteger(revision) || revision < 1) {
      toast.error('Select a persisted revision before activating the schedule.');
      return;
    }

    setWorkflowActionBusy(true);
    try {
      await activateWorkflowRevision(workflowDetail.id, revision);
      const [detail, revisionDtos] = await Promise.all([
        getWorkflow({ workflowId: workflowDetail.id }),
        getWorkflowRevisions({ workflowId: workflowDetail.id }),
      ]);
      const revisions = revisionDtos.map(mapWorkflowRevision);
      const activeRevision = revisions.find((item) => item.active) || null;
      const nextDefinition = activeRevision?.definition || detail.activeDefinition?.definition || activeDefinition;
      const summary = mapWorkflowSummary(detail);

      setWorkflowDetail(detail);
      setWorkflowRevisions(revisions);
      setSelectedRevisionId(activeRevision?.id || selectedRevision.id);
      setWorkflowDef(nextDefinition);
      setSelectedStateName(getStartState(nextDefinition));
      setWorkflowSummaries((current) => [summary, ...current.filter((item) => item.id !== summary.id)]);
      setWorkflowPage((current) => current ? {
        ...current,
        content: current.content.map((item) => item.id === detail.id ? detail : item),
      } : current);
      setSelectedWorkflow(summary);
      toast.success('Schedule activated');
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Could not activate the schedule.');
    } finally {
      setWorkflowActionBusy(false);
    }
  };

  const handleStateSelect = (stateName: string) => {
    setSelectedStateName(stateName);
    setDetailsPanelOpen(true);
    setRevisionPanelOpen(false);
  };

  const createViewActive = route.page === 'create' || route.page === 'chat' || route.page === 'workflow-edit';
  const hideMainHeader = createViewActive || (route.page === 'functions' && functionsWorkbenchActive);
  const sortedChatSummaries = [...chatSummaries].sort((left, right) => chatSortTime(right) - chatSortTime(left));
  const sidebarChatSummaries = sortedChatSummaries.slice(0, 5);
  const hiddenChatCount = Math.max(sortedChatSummaries.length - sidebarChatSummaries.length, 0);
  const hasSidebarChats = sortedChatSummaries.length > 0;

  // Shared sidebar nav-item styling (design: Voyager.dc.html). Active item gets a
  // surface fill; the accent bar is rendered separately via `navActiveBar`.
  const navItemClass = (active: boolean) =>
    `relative flex h-10 w-full items-center rounded-lg font-mono-sm text-[13px] transition-colors ${
      sidebarOpen ? 'gap-3 px-3' : 'justify-center px-0'
    } ${
      active
        ? 'bg-surface-container-low text-on-surface'
        : 'text-on-surface-variant hover:bg-surface-container-low hover:text-on-surface'
    }`;
  const navActiveBar = (
    <span className="absolute left-0 top-1/2 h-6 w-[3px] -translate-y-1/2 rounded-r-full bg-primary" />
  );

  return (
    <div className="font-body-sm text-body-sm overflow-hidden bg-surface-base selection:bg-primary/30 selection:text-primary">
      <div className="flex h-screen">
        <aside
          id="app-sidebar"
          className={`voyager-sidebar-shell fixed left-0 top-0 z-40 hidden h-screen flex-col border-r border-border-subtle transition-[width] duration-200 ease-out md:flex ${sidebarOpen ? 'w-sidebar-width' : 'w-16'}`}
          aria-label={sidebarOpen ? 'Expanded navigation' : 'Collapsed navigation'}
        >
          <div className={`flex h-[72px] items-center border-b border-border-subtle ${sidebarOpen ? 'justify-between px-3.5' : 'justify-center px-0'}`}>
            {sidebarOpen ? (
              <>
                <button
                  type="button"
                  onClick={handleCreateWorkflow}
                  className="flex min-w-0 items-center gap-2 overflow-hidden text-left"
                  title="Voyager"
                >
                  <img src="/voyager-logo.svg" alt="" className="voyager-hero-wordmark h-[34px] w-[34px] shrink-0" />
                  <span className="font-mono-sm text-[18px] font-semibold leading-none tracking-[-0.01em] text-primary">Voyager</span>
                </button>
                <button
                  type="button"
                  onClick={() => setSidebarOpen(false)}
                  className="flex h-8 w-8 shrink-0 cursor-ew-resize items-center justify-center rounded-lg border border-border-subtle bg-surface-container-lowest text-on-surface-variant transition-colors hover:text-on-surface"
                  aria-label="Collapse sidebar"
                  aria-controls="app-sidebar"
                  aria-expanded={sidebarOpen}
                  title="Collapse sidebar"
                >
                  <span className="material-symbols-outlined text-[18px] leading-none">menu</span>
                </button>
              </>
            ) : (
              <button
                type="button"
                onClick={() => setSidebarOpen(true)}
                className="group relative h-[34px] w-[34px] cursor-ew-resize"
                aria-label="Expand sidebar"
                aria-controls="app-sidebar"
                aria-expanded={sidebarOpen}
                title="Expand sidebar"
              >
                <img src="/voyager-logo.svg" alt="" className="absolute inset-0 h-[34px] w-[34px] opacity-100 transition-opacity duration-200 group-hover:opacity-0" />
                <span className="material-symbols-outlined absolute inset-0 flex items-center justify-center text-[20px] leading-none text-primary opacity-0 transition-opacity duration-200 group-hover:opacity-100">left_panel_close</span>
              </button>
            )}
          </div>
          <div className="flex-1 space-y-1 overflow-y-auto px-1 py-4">
            <button
              onClick={handleCreateWorkflow}
              className={navItemClass(route.page === 'create')}
              aria-label="New Workflow"
              title="New Workflow"
              type="button"
            >
              <span className="material-symbols-outlined shrink-0 text-[18px]">add</span>
              {route.page === 'create' && navActiveBar}
              <span className={sidebarOpen ? 'inline truncate' : 'hidden'}>New Workflow</span>
            </button>
            <button
              onClick={() => setSearchModalOpen(true)}
              className={navItemClass(false)}
              aria-label="Search"
              title="Search (Ctrl+K)"
              type="button"
            >
              <span className="material-symbols-outlined shrink-0 text-[18px]">search</span>
              <span className={sidebarOpen ? 'inline flex-1 truncate text-left' : 'hidden'}>Search</span>
              {sidebarOpen && (
                <span className="rounded-md border border-border-subtle bg-surface-container-low px-1.5 py-0.5 font-mono-sm text-[10px] text-on-surface-variant">
                  Ctrl K
                </span>
              )}
            </button>
            {hasSidebarChats && (
              <div>
                <button
                  type="button"
                  onClick={() => {
                    if (!sidebarOpen) {
                      setSidebarOpen(true);
                      setChatsOpen(true);
                    } else {
                      setChatsOpen((open) => !open);
                    }
                  }}
                  className={navItemClass(false)}
                  title="Chats"
                  aria-label={chatsOpen ? 'Collapse chats' : 'Expand chats'}
                  aria-expanded={chatsOpen}
                >
                  <span className="material-symbols-outlined shrink-0 text-[18px]">chat_bubble</span>
                  <span className={sidebarOpen ? 'inline flex-1 truncate text-left' : 'hidden'}>Chats</span>
                  {sidebarOpen && (
                    <span className="material-symbols-outlined shrink-0 text-[16px]">
                      {chatsOpen ? 'expand_more' : 'chevron_right'}
                    </span>
                  )}
                </button>
                <div className="mt-0.5 space-y-0.5">
                  {sidebarOpen && chatsOpen && sidebarChatSummaries.map((chat) => {
                    const active = route.page === 'chat' && route.chatId === chat.id;
                    const title = compactChatTitle(chat);
                    const modelLabel = chat.modelDisplayName || 'AI model';
                    const timeLabel = formatChatTime(chat.updatedAt || chat.createdAt);
                    return (
                      <button
                        key={chat.id}
                        type="button"
                        onClick={() => handleChatSelected(chat)}
                        className={`group relative flex h-10 w-full min-w-0 items-center gap-2 rounded-lg px-2 font-mono-sm text-[12px] transition-colors ${active ? 'bg-primary/10 text-secondary' : 'text-on-surface-variant hover:bg-surface-container-low hover:text-secondary'}`}
                        aria-label={`Open chat ${title}`}
                        title={`${title} - ${modelLabel}`}
                      >
                        <span className="material-symbols-outlined shrink-0 text-[17px] text-on-surface-variant group-hover:text-secondary">smart_toy</span>
                        {active && navActiveBar}
                        <span className="min-w-0 flex-1 truncate text-left">
                          <span className="text-on-surface-variant">&gt;_</span>
                          <span className="ml-1.5 text-secondary">{title}</span>
                          {timeLabel && <span className="ml-1.5 text-on-surface-variant">&middot; {timeLabel}</span>}
                        </span>
                      </button>
                    );
                  })}
                  {sidebarOpen && chatsOpen && hiddenChatCount > 0 && (
                    <button
                      type="button"
                      onClick={() => setChatHistoryModalOpen(true)}
                      className="flex h-8 w-full items-center gap-2 rounded-lg px-2 font-mono-sm text-[10px] text-on-surface-variant transition-colors hover:bg-surface-container-low hover:text-on-surface"
                      aria-label="View all chats"
                      title="View all chats"
                    >
                      <span className="material-symbols-outlined shrink-0 text-[16px]">more_horiz</span>
                      <span className="min-w-0 flex-1 truncate text-left">View all chats</span>
                      <span className="rounded-md border border-border-subtle px-1.5 py-0.5 text-[9px]">{hiddenChatCount}</span>
                    </button>
                  )}
                </div>
              </div>
            )}
            <div className="mx-2 my-3 h-px bg-border-subtle" />
            <button
              onClick={() => navigate('/workflows')}
              className={navItemClass(route.page === 'workflows' || route.page === 'workflow')}
              aria-label="Workflows"
              title="Workflows"
            >
              <span className="material-symbols-outlined shrink-0 text-[18px]">account_tree</span>
              {(route.page === 'workflows' || route.page === 'workflow') && navActiveBar}
              <span className={sidebarOpen ? 'inline truncate' : 'hidden'}>Workflows</span>
            </button>
            <button
              onClick={() => navigate('/functions')}
              className={navItemClass(route.page === 'functions')}
              aria-label="Functions"
              title="Functions"
            >
              <span className="material-symbols-outlined shrink-0 text-[18px]">function</span>
              {route.page === 'functions' && navActiveBar}
              <span className={sidebarOpen ? 'inline truncate' : 'hidden'}>Functions</span>
            </button>
            <button
              onClick={() => navigate('/mcp')}
              className={navItemClass(route.page === 'mcp')}
              aria-label="MCP Servers"
              title="MCP Servers"
            >
              <span className="material-symbols-outlined shrink-0 text-[18px]">cable</span>
              {route.page === 'mcp' && navActiveBar}
              <span className={sidebarOpen ? 'inline truncate' : 'hidden'}>MCP Servers</span>
            </button>
            <div className="mx-2 my-3 h-px bg-border-subtle" />
            <button className={navItemClass(false)} aria-label="Docs" title="Docs">
              <span className="material-symbols-outlined shrink-0 text-[18px]">description</span>
              <span className={sidebarOpen ? 'inline truncate' : 'hidden'}>Docs</span>
            </button>
            <button onClick={() => navigate('/settings')} className={navItemClass(route.page === 'settings')} aria-label="Settings" title="Settings">
              <span className="material-symbols-outlined shrink-0 text-[18px]">settings</span>
              {route.page === 'settings' && navActiveBar}
              <span className={sidebarOpen ? 'inline truncate' : 'hidden'}>Settings</span>
            </button>
          </div>
          <div className="mt-auto border-border-subtle p-2.5">
            <button
              type="button"
              className={`flex w-full items-center gap-2.5 rounded-xl border border-border-subtle bg-surface-container-lowest p-2 text-left transition-colors hover:bg-surface-container-low ${sidebarOpen ? 'justify-start' : 'justify-center'}`}
              aria-label="Account"
              title="admin"
            >
              <span className="flex h-[34px] w-[34px] shrink-0 items-center justify-center rounded-[10px] bg-gradient-to-br from-primary to-primary-fixed font-mono-sm text-[12px] font-semibold text-on-primary">
                AD
              </span>
              {sidebarOpen && (
                  <span className="block truncate text-[12px] font-semibold text-on-surface">admin</span>
              )}
            </button>
          </div>
        </aside>
        {/* Main Content Area */}
        <main className={`voyager-polished-bg relative flex h-full w-full flex-1 flex-col transition-[margin] duration-200 ease-out ${route.page === 'functions' && functionsWorkbenchActive ? 'z-50' : 'z-0'} ${sidebarOpen ? 'md:ml-sidebar-width' : 'md:ml-16'}`}>
          {!hideMainHeader && (
          <header className="glass-shell z-10 flex min-h-14 flex-shrink-0 items-center px-4 py-2">
            <div className="flex w-full min-w-0 items-center justify-between gap-3">
              <div className="flex min-w-0 items-center gap-3">
                {selectedWorkflow ? (
                  <div className="flex min-w-0 items-center gap-2">
                    <span className={`h-2 w-2 shrink-0 rounded-full ${selectedWorkflow.status === 'Failed' ? 'bg-status-error' : selectedWorkflow.status === 'Paused' || selectedWorkflow.status === 'Archived' ? 'bg-on-surface-variant' : selectedWorkflow.status === 'Draft' ? 'bg-status-info' : 'bg-status-success'}`}></span>
                    <h1 className="truncate font-display text-[16px] font-semibold leading-6 text-primary">
                      {selectedWorkflow.name}
                    </h1>
                  </div>
                ) : (
                  <span className="font-display text-[16px] font-semibold leading-6 text-primary">
                {route.page === 'settings' ? 'Settings' : route.page === 'functions' ? 'Functions' : route.page === 'mcp' ? 'MCP Servers' : 'Workflow Executions'}
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
                    <button data-testid="workflow-tab-executions" onClick={() => setActiveTab('executions')} className={`relative z-10 w-24 py-1.5 font-body-sm text-body-sm transition-colors ${activeTab === 'executions' ? 'text-primary font-medium' : 'text-on-surface-variant hover:text-primary'}`}>
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
                  {workflowDetail && workflowDetail.status !== 'ARCHIVED' && (
                    <button
                      type="button"
                      data-testid="workflow-settings-open"
                      onClick={() => {
                        setRevisionPanelOpen(false);
                        setDetailsPanelOpen(false);
                        setWorkflowSettingsOpen(true);
                      }}
                      className="flex h-9 items-center gap-2 rounded-DEFAULT border border-border-subtle bg-surface-elevated px-3 font-body-sm text-body-sm text-primary transition-colors hover:border-secondary/55 hover:bg-surface-container"
                      title="Edit workflow metadata and lifecycle"
                    >
                      <span className="material-symbols-outlined text-[16px]">tune</span>
                      Settings
                    </button>
                  )}
                  {selectedRevision
                    && Number.isSafeInteger(Number(selectedRevision.id))
                    && workflowDetail?.status !== 'ARCHIVED' && (
                    <button
                      type="button"
                      data-testid="workflow-edit-revision"
                      onClick={() => navigate(workflowRevisionEditPath(
                        selectedWorkflow.id,
                        Number(selectedRevision.id),
                      ))}
                      className="flex h-9 items-center gap-2 rounded-DEFAULT border border-border-subtle bg-surface-elevated px-3 font-body-sm text-body-sm text-primary transition-colors hover:border-secondary/55 hover:bg-surface-container"
                      title={`Use ${selectedRevision.label} as the base for a new immutable revision`}
                    >
                      <span className="material-symbols-outlined text-[16px]">edit_note</span>
                      Edit as new revision
                    </button>
                  )}
                  {workflowDetail?.status === 'DRAFT' && workflowDetail.cronExpression ? (
                    <button
                      type="button"
                      data-testid="workflow-activate-schedule"
                      onClick={handleActivateSchedule}
                      disabled={workflowActionBusy || !selectedRevision}
                      className="flex h-9 items-center gap-2 rounded-DEFAULT border border-primary bg-primary px-3 font-body-sm text-body-sm font-medium text-surface-lowest transition-colors hover:bg-primary-fixed disabled:cursor-not-allowed disabled:opacity-60"
                    >
                      <span className="material-symbols-outlined text-[16px]">event_available</span>
                      {workflowActionBusy ? 'Activating...' : 'Activate schedule'}
                    </button>
                  ) : (workflowDetail?.status === 'ACTIVE' || workflowDetail?.status === 'PAUSED') ? (
                    <button onClick={() => setActiveTab('executions')} className="flex h-9 items-center gap-2 rounded-DEFAULT border border-primary bg-primary px-3 font-body-sm text-body-sm font-medium text-surface-lowest transition-colors hover:bg-primary-fixed">
                      <span className="material-symbols-outlined text-[16px]">play_arrow</span>
                      Execute
                    </button>
                  ) : null}
                </div>
              )}
            </div>
          </header>
          )}

          {route.page !== 'workflow' ? (
            <div className="flex-1 min-h-0 overflow-hidden">
              {route.page === 'workflow-edit' ? (
                workflowDetailLoading ? (
                  <WorkspaceState title="Loading revision" message="Preparing the selected workflow revision for editing." />
                ) : workflowDetailError ? (
                  <WorkspaceState title="Revision unavailable" message={workflowDetailError} />
                ) : !workflowDetail || !revisionEditBase ? (
                  <WorkspaceState
                    title="Revision not found"
                    message={`Revision ${route.revision} is not available for this workflow.`}
                    action={{ label: 'Back to workflow', onClick: () => navigate(workflowPath(route.workflowId)) }}
                  />
                ) : (
                  <CreateWorkflowPage
                    key={`${workflowDetail.id}:${revisionEditBase.id}:edit`}
                    onWorkflowCreated={handleWorkflowCreated}
                    onWorkflowRevisionSaved={handleWorkflowRevisionSaved}
                    onUnsavedChangesChange={handleWorkflowEditDirtyChange}
                    onNavigate={navigate}
                    revisionEdit={{
                      workflow: workflowDetail,
                      baseRevision: {
                        revision: Number(revisionEditBase.id),
                        definition: revisionEditBase.definition,
                        canvasLayout: revisionEditBase.canvasLayout,
                        active: Boolean(revisionEditBase.active),
                      },
                    }}
                  />
                )
              ) : route.page === 'create' || route.page === 'chat' ? (
                <CreateWorkflowPage
                  key={newWorkflowResetKey}
                  routeChatId={route.page === 'chat' ? route.chatId : undefined}
                  onWorkflowCreated={handleWorkflowCreated}
                  onNavigate={navigate}
                  onChatStarted={(chat) => upsertChatSummary(null, chat)}
                  onChatUpdated={upsertChatSummary}
                />
              ) : route.page === 'settings' ? (
                <SettingsPage />
              ) : route.page === 'functions' ? (
                <FunctionsPage onWorkbenchModeChange={setFunctionsWorkbenchActive} />
              ) : route.page === 'mcp' ? (
                <McpServersPage />
              ) : workflowListLoading ? (
                <WorkspaceState title="Loading workflows" message="Fetching workflow list from /app/v1/workflows." />
              ) : workflowListError ? (
                <WorkspaceState
                  title="Workflow API unavailable"
                  message={workflowListError}
                  action={{ label: 'Retry', onClick: loadWorkflows }}
                />
              ) : (
                <WorkflowsPage
                  workflows={workflowSummaries}
                  totalWorkflows={workflowPage?.totalElements}
                  onSelect={handleWorkflowSelected}
                />
              )}
            </div>
          ) : (
          <WorkflowDetailPage
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
            onCanvasLayoutChange={handleCanvasLayoutChange}
          />
          )}
        </main>
      </div>
      {route.page === 'workflow' && workflowSettingsOpen && workflowDetail && (
        <WorkflowSettingsPanel
          workflow={workflowDetail}
          onClose={() => setWorkflowSettingsOpen(false)}
          onDirtyChange={handleWorkflowSettingsDirtyChange}
          onWorkflowUpdated={handleWorkflowUpdated}
          onWorkflowArchived={handleWorkflowArchived}
        />
      )}
      {searchModalOpen && (
        <div
          className="fixed inset-0 z-50 flex cursor-pointer items-start justify-center bg-background/80 pt-[20vh] backdrop-blur-sm"
          onClick={() => setSearchModalOpen(false)}
        >
          <div
            className="w-full max-w-2xl cursor-default px-4"
            onClick={(e) => e.stopPropagation()}
          >
            <input
              autoFocus
              type="text"
              placeholder="Search conversations ..."
              className="w-full rounded-full border border-border-subtle bg-surface-container-highest px-6 py-4 font-mono-sm text-body-lg text-on-surface shadow-lg outline-none placeholder:text-on-surface-variant focus:border-primary/50 focus:ring-1 focus:ring-primary/50"
            />
          </div>
        </div>
      )}
      {chatHistoryModalOpen && (
        <ChatHistoryModal
          chats={sortedChatSummaries}
          activeChatId={route.page === 'chat' ? route.chatId : undefined}
          onClose={() => setChatHistoryModalOpen(false)}
          onSelect={handleChatSelected}
        />
      )}
    </div>
  );
}

export default App;
