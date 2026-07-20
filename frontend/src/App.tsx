import { useCallback, useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';
import type { WorkflowSummary } from './components/WorkflowListView';
import { WorkspaceState } from './components/WorkspaceState';
import { WorkflowSettingsPanel } from './components/WorkflowSettingsPanel';
import { WorkflowsPage } from './pages/WorkflowsPage';
import { CreateWorkflowPage } from './pages/CreateWorkflowPage';
import { FunctionsPage } from './pages/FunctionsPage';
import { McpServersPage } from './pages/McpServersPage';
import { DocsPage } from './pages/DocsPage';
import { WorkflowDetailPage, type WorkflowRevision } from './pages/WorkflowDetailPage';
import {
  activateWorkflowRevision,
  deleteAllWorkflowAiDrafts,
  deleteAllWorkflowAiConversations,
  deleteWorkflowAiDraft,
  deleteWorkflowAiConversation,
  getWorkflow,
  getWorkflowRevisions,
  listWorkflowAiConversations,
  listWorkflowAiDrafts,
  listWorkflows,
  renameWorkflowAiConversation,
  renameWorkflowAiDraft,
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

type AppRoute =
  | { page: 'workflows' }
  | { page: 'create' }
  | { page: 'chat'; chatId: string }
  | { page: 'draft'; draftId: string }
  | { page: 'workflow'; workflowId: string }
  | { page: 'workflow-edit'; workflowId: string; revision: number }
  | { page: 'functions' }
  | { page: 'mcp' }
  | { page: 'docs'; slug?: string };

type WorkspaceDeleteTarget =
  | {
      kind: 'one';
      workspaceKind: 'chat' | 'draft';
      workspace: WorkflowAiConversationSummaryDTO;
    }
  | { kind: 'all'; workspaceKind: 'chat' | 'draft'; count: number };

type WorkspaceRenameTarget = {
  workspaceKind: 'chat' | 'draft';
  workspace: WorkflowAiConversationSummaryDTO;
};

function parseRoute(pathname: string): AppRoute {
  const normalized = pathname.replace(/\/+$/, '') || '/';
  if (normalized === '/') return { page: 'create' };
  if (normalized === '/workflows/new') return { page: 'create' };
  if (normalized === '/workflows') return { page: 'workflows' };
  if (normalized === '/functions') return { page: 'functions' };
  if (normalized === '/mcp') return { page: 'mcp' };
  if (normalized === '/docs') return { page: 'docs' };

  const chatMatch = normalized.match(/^\/c\/([^/]+)$/);
  if (chatMatch?.[1]) {
    return { page: 'chat', chatId: decodeURIComponent(chatMatch[1]) };
  }

  const draftMatch = normalized.match(/^\/draft\/([^/]+)$/);
  if (draftMatch?.[1]) {
    return { page: 'draft', draftId: decodeURIComponent(draftMatch[1]) };
  }

  const docsMatch = normalized.match(/^\/docs\/([^/]+)$/);
  if (docsMatch?.[1]) {
    return { page: 'docs', slug: decodeURIComponent(docsMatch[1]) };
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
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [chatsOpen, setChatsOpen] = useState(true);
  const [chatSearch, setChatSearch] = useState('');
  const [chatSummaries, setChatSummaries] = useState<WorkflowAiConversationSummaryDTO[]>([]);
  const [chatListLoading, setChatListLoading] = useState(true);
  const [chatListError, setChatListError] = useState<string | null>(null);
  const [deletingChatId, setDeletingChatId] = useState<string | null>(null);
  const [deletingAllChats, setDeletingAllChats] = useState(false);
  const [chatDeleteTarget, setChatDeleteTarget] = useState<WorkspaceDeleteTarget | null>(null);
  const [workspaceRenameTarget, setWorkspaceRenameTarget] = useState<WorkspaceRenameTarget | null>(null);
  const [workspaceRenameValue, setWorkspaceRenameValue] = useState('');
  const [renamingWorkspaceId, setRenamingWorkspaceId] = useState<string | null>(null);
  const [draftsOpen, setDraftsOpen] = useState(true);
  const [draftSearch, setDraftSearch] = useState('');
  const [draftSummaries, setDraftSummaries] = useState<WorkflowAiConversationSummaryDTO[]>([]);
  const [draftListLoading, setDraftListLoading] = useState(true);
  const [draftListError, setDraftListError] = useState<string | null>(null);
  const [deletingDraftId, setDeletingDraftId] = useState<string | null>(null);
  const [deletingAllDrafts, setDeletingAllDrafts] = useState(false);
  const [detailsPanelOpen, setDetailsPanelOpen] = useState(false);
  const [revisionPanelOpen, setRevisionPanelOpen] = useState(false);
  const [workflowSettingsOpen, setWorkflowSettingsOpen] = useState(false);
  const [newWorkflowResetKey, setNewWorkflowResetKey] = useState(0);
  const [route, setRoute] = useState<AppRoute>(() => parseRoute(window.location.pathname));
  const [functionsWorkbenchActive, setFunctionsWorkbenchActive] = useState(false);
  const [workflowPage, setWorkflowPage] = useState<WorkflowPageDTO | null>(null);
  const [workflowSummaries, setWorkflowSummaries] = useState<WorkflowSummary[]>([]);
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
      if (e.key === 'Escape') {
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

  const loadChats = useCallback(() => {
    setChatListLoading(true);
    setChatListError(null);
    listWorkflowAiConversations()
      .then(setChatSummaries)
      .catch((error: Error) => {
        console.warn('Workflow AI chat history unavailable.', error);
        setChatListError('Chat history is unavailable.');
      })
      .finally(() => setChatListLoading(false));
  }, []);

  const loadDrafts = useCallback(() => {
    setDraftListLoading(true);
    setDraftListError(null);
    listWorkflowAiDrafts()
      .then(setDraftSummaries)
      .catch((error: Error) => {
        console.warn('Manual workflow draft history unavailable.', error);
        setDraftListError('Draft history is unavailable.');
      })
      .finally(() => setDraftListLoading(false));
  }, []);

  const loadWorkspaceLists = useCallback(() => {
    loadChats();
    loadDrafts();
  }, [loadChats, loadDrafts]);

  useEffect(() => {
    loadWorkspaceLists();
  }, [loadWorkspaceLists]);

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

  const handleChatSelected = (chat: WorkflowAiConversationSummaryDTO) => {
    setSelectedWorkflow(null);
    setRevisionPanelOpen(false);
    setDetailsPanelOpen(false);
    navigate(`/c/${encodeURIComponent(chat.id)}`);
  };

  const handleDraftSelected = (draft: WorkflowAiConversationSummaryDTO) => {
    setSelectedWorkflow(null);
    setRevisionPanelOpen(false);
    setDetailsPanelOpen(false);
    navigate(`/draft/${encodeURIComponent(draft.id)}`);
  };

  const requestDeleteChat = (chat: WorkflowAiConversationSummaryDTO) => {
    if (deletingChatId || deletingAllChats) return;
    setChatDeleteTarget({ kind: 'one', workspaceKind: 'chat', workspace: chat });
  };

  const requestDeleteAllChats = () => {
    if (chatSummaries.length === 0 || deletingAllChats || deletingChatId) return;
    setChatDeleteTarget({ kind: 'all', workspaceKind: 'chat', count: chatSummaries.length });
  };

  const requestDeleteDraft = (draft: WorkflowAiConversationSummaryDTO) => {
    if (deletingDraftId || deletingAllDrafts) return;
    setChatDeleteTarget({ kind: 'one', workspaceKind: 'draft', workspace: draft });
  };

  const requestDeleteAllDrafts = () => {
    if (draftSummaries.length === 0 || deletingAllDrafts || deletingDraftId) return;
    setChatDeleteTarget({ kind: 'all', workspaceKind: 'draft', count: draftSummaries.length });
  };

  const requestRenameWorkspace = (
    workspaceKind: 'chat' | 'draft',
    workspace: WorkflowAiConversationSummaryDTO,
  ) => {
    if (renamingWorkspaceId || deletingChatId || deletingDraftId) return;
    setWorkspaceRenameTarget({ workspaceKind, workspace });
    setWorkspaceRenameValue(compactChatTitle(workspace));
  };

  const cancelRenameWorkspace = () => {
    if (renamingWorkspaceId) return;
    setWorkspaceRenameTarget(null);
    setWorkspaceRenameValue('');
  };

  const performRenameWorkspace = async () => {
    if (!workspaceRenameTarget || renamingWorkspaceId) return;
    const name = workspaceRenameValue.trim();
    if (!name) return;
    const { workspaceKind, workspace } = workspaceRenameTarget;
    setRenamingWorkspaceId(workspace.id);
    try {
      const renamed = workspaceKind === 'draft'
        ? await renameWorkflowAiDraft(workspace.id, name)
        : await renameWorkflowAiConversation(workspace.id, name);
      if (workspaceKind === 'draft') {
        setDraftSummaries((current) => current.map((item) => (
          item.id === renamed.id ? renamed : item
        )));
      } else {
        setChatSummaries((current) => current.map((item) => (
          item.id === renamed.id ? renamed : item
        )));
      }
      setWorkspaceRenameTarget(null);
      setWorkspaceRenameValue('');
      toast.success(`${workspaceKind === 'draft' ? 'Draft' : 'Chat'} renamed.`);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Could not rename workspace.');
    } finally {
      setRenamingWorkspaceId(null);
    }
  };

  const performDeleteChat = async (chat: WorkflowAiConversationSummaryDTO) => {
    setDeletingChatId(chat.id);
    try {
      await deleteWorkflowAiConversation(chat.id);
      setChatSummaries((current) => current.filter((item) => item.id !== chat.id));
      setChatDeleteTarget(null);
      toast.success('Chat deleted.');
      if (route.page === 'chat' && route.chatId === chat.id) {
        // Force a fresh builder so the deleted conversation's messages and draft can't autosave back.
        setNewWorkflowResetKey((key) => key + 1);
        navigate('/');
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Could not delete chat.');
    } finally {
      setDeletingChatId(null);
    }
  };

  const performDeleteAllChats = async () => {
    setDeletingAllChats(true);
    try {
      await deleteAllWorkflowAiConversations();
      setChatSummaries([]);
      setChatDeleteTarget(null);
      toast.success('All chats deleted.');
      if (route.page === 'chat') {
        // Force a fresh builder so a deleted conversation's messages and draft can't autosave back.
        setNewWorkflowResetKey((key) => key + 1);
        navigate('/');
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Could not delete chats.');
    } finally {
      setDeletingAllChats(false);
    }
  };

  const performDeleteDraft = async (draft: WorkflowAiConversationSummaryDTO) => {
    setDeletingDraftId(draft.id);
    try {
      await deleteWorkflowAiDraft(draft.id);
      setDraftSummaries((current) => current.filter((item) => item.id !== draft.id));
      setChatDeleteTarget(null);
      toast.success('Draft deleted.');
      if (route.page === 'draft' && route.draftId === draft.id) {
        setNewWorkflowResetKey((key) => key + 1);
        navigate('/');
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Could not delete draft.');
    } finally {
      setDeletingDraftId(null);
    }
  };

  const performDeleteAllDrafts = async () => {
    setDeletingAllDrafts(true);
    try {
      await deleteAllWorkflowAiDrafts();
      setDraftSummaries([]);
      setChatDeleteTarget(null);
      toast.success('All drafts deleted.');
      if (route.page === 'draft') {
        setNewWorkflowResetKey((key) => key + 1);
        navigate('/');
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Could not delete drafts.');
    } finally {
      setDeletingAllDrafts(false);
    }
  };

  const confirmChatDelete = () => {
    if (!chatDeleteTarget) return;
    if (chatDeleteTarget.workspaceKind === 'draft') {
      if (chatDeleteTarget.kind === 'one') {
        void performDeleteDraft(chatDeleteTarget.workspace);
      } else {
        void performDeleteAllDrafts();
      }
    } else if (chatDeleteTarget.kind === 'one') {
      void performDeleteChat(chatDeleteTarget.workspace);
    } else {
      void performDeleteAllChats();
    }
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
  }, [setSelectedWorkflow]);

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

  const createViewActive = route.page === 'create'
    || route.page === 'chat'
    || route.page === 'draft'
    || route.page === 'workflow-edit';
  const hideMainHeader = createViewActive || (route.page === 'functions' && functionsWorkbenchActive);
  const normalizedChatSearch = chatSearch.trim().toLocaleLowerCase();
  const sortedChatSummaries = [...chatSummaries]
    .sort((left, right) => chatSortTime(right) - chatSortTime(left));
  const filteredChatSummaries = normalizedChatSearch
    ? sortedChatSummaries.filter((chat) => (
        compactChatTitle(chat).toLocaleLowerCase().includes(normalizedChatSearch)
        || chat.initialInstruction?.toLocaleLowerCase().includes(normalizedChatSearch)
        || chat.modelDisplayName?.toLocaleLowerCase().includes(normalizedChatSearch)
      ))
    : sortedChatSummaries;
  const normalizedDraftSearch = draftSearch.trim().toLocaleLowerCase();
  const sortedDraftSummaries = [...draftSummaries]
    .sort((left, right) => chatSortTime(right) - chatSortTime(left));
  const filteredDraftSummaries = normalizedDraftSearch
    ? sortedDraftSummaries.filter((draft) => (
        compactChatTitle(draft).toLocaleLowerCase().includes(normalizedDraftSearch)
        || draft.initialInstruction?.toLocaleLowerCase().includes(normalizedDraftSearch)
        || draft.modelDisplayName?.toLocaleLowerCase().includes(normalizedDraftSearch)
      ))
    : sortedDraftSummaries;

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
            <div>
              <button
                type="button"
                onClick={() => {
                  if (!sidebarOpen) {
                    setSidebarOpen(true);
                    setChatsOpen(true);
                    return;
                  }
                  setChatsOpen((open) => !open);
                }}
                className={navItemClass(route.page === 'chat')}
                aria-label={!sidebarOpen ? 'Open chats' : chatsOpen ? 'Collapse chats' : 'Expand chats'}
                aria-expanded={sidebarOpen && chatsOpen}
                title="Chats"
              >
                <span className="material-symbols-outlined shrink-0 text-[18px]">forum</span>
                {route.page === 'chat' && navActiveBar}
                <span className={sidebarOpen ? 'inline min-w-0 flex-1 truncate text-left' : 'hidden'}>Chats</span>
                {sidebarOpen && (
                  <span className="flex shrink-0 items-center gap-1.5">
                    <span className="rounded-md border border-border-subtle px-1.5 py-0.5 font-mono-sm text-[9px] text-on-surface-variant">
                      {chatSummaries.length}
                    </span>
                    <span className="material-symbols-outlined text-[16px]">
                      {chatsOpen ? 'expand_more' : 'chevron_right'}
                    </span>
                  </span>
                )}
              </button>

              {sidebarOpen && chatsOpen && (
                <div className="mx-1 mt-1 rounded-lg border border-border-subtle bg-surface-container-lowest/65 p-1.5">
                  <div className="flex items-center gap-1.5">
                    <div className="relative flex-1">
                      <span className="material-symbols-outlined pointer-events-none absolute left-2 top-1/2 -translate-y-1/2 text-[15px] text-on-surface-variant">
                        search
                      </span>
                      <input
                        type="search"
                        value={chatSearch}
                        onChange={(event) => setChatSearch(event.target.value)}
                        placeholder="Search chats"
                        aria-label="Search chats"
                        className="h-8 w-full rounded-md border border-border-subtle bg-surface-container-low pl-7 pr-7 font-mono-sm text-[10px] text-on-surface outline-none placeholder:text-on-surface-variant/65 focus:border-secondary/55 focus:ring-1 focus:ring-secondary/20"
                      />
                      {chatSearch && (
                        <button
                          type="button"
                          onClick={() => setChatSearch('')}
                          aria-label="Clear chat search"
                          className="absolute right-1 top-1/2 flex h-6 w-6 -translate-y-1/2 items-center justify-center rounded text-on-surface-variant transition-colors hover:bg-surface-container hover:text-on-surface"
                        >
                          <span className="material-symbols-outlined text-[14px]">close</span>
                        </button>
                      )}
                    </div>
                    {chatSummaries.length > 0 && (
                      <button
                        type="button"
                        onClick={requestDeleteAllChats}
                        disabled={deletingAllChats || Boolean(deletingChatId)}
                        aria-label="Delete all chats"
                        title="Delete every chat and the workflow JSON and canvas built inside them"
                        className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-border-subtle text-on-surface-variant transition-colors hover:border-status-error/40 hover:bg-status-error/15 hover:text-status-error disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        <span className={`material-symbols-outlined text-[15px] ${deletingAllChats ? 'animate-spin' : ''}`}>
                          {deletingAllChats ? 'progress_activity' : 'delete_sweep'}
                        </span>
                      </button>
                    )}
                  </div>

                  <div className="mt-1.5 max-h-[min(42vh,360px)] space-y-0.5 overflow-y-auto pr-0.5" role="region" aria-label="Chat history">
                    {chatListLoading && chatSummaries.length === 0 ? (
                      <div className="px-2 py-3 text-center font-mono-sm text-[10px] text-on-surface-variant">Loading chats...</div>
                    ) : chatListError && chatSummaries.length === 0 ? (
                      <button
                        type="button"
                        onClick={loadChats}
                        className="w-full rounded-md px-2 py-3 text-center font-mono-sm text-[10px] text-status-error transition-colors hover:bg-surface-container-low"
                      >
                        {chatListError} Retry
                      </button>
                    ) : filteredChatSummaries.length === 0 ? (
                      <div className="px-2 py-3 text-center font-mono-sm text-[10px] text-on-surface-variant">
                        {chatSearch ? 'No matching chats' : 'No previous chats yet'}
                      </div>
                    ) : filteredChatSummaries.map((chat) => {
                      const active = route.page === 'chat' && route.chatId === chat.id;
                      const title = compactChatTitle(chat);
                      const deleting = deletingChatId === chat.id;
                      return (
                        <div
                          key={chat.id}
                          className={`group relative flex w-full min-w-0 items-center rounded-md transition-colors ${
                            active
                              ? 'bg-primary/10 text-on-surface'
                              : 'text-on-surface-variant hover:bg-surface-container-low hover:text-on-surface'
                          } ${deleting ? 'opacity-60' : ''}`}
                        >
                          {active && <span className="absolute left-0 top-1/2 h-5 w-[2px] -translate-y-1/2 rounded-r-full bg-secondary" />}
                          <button
                            type="button"
                            onClick={() => handleChatSelected(chat)}
                            aria-label={`Open chat ${title}`}
                            aria-current={active ? 'page' : undefined}
                            title={title}
                            className="flex min-w-0 flex-1 items-center gap-2 rounded-md px-2 py-1.5 text-left"
                          >
                            <span className={`material-symbols-outlined shrink-0 text-[15px] ${active ? 'text-secondary' : 'text-on-surface-variant group-hover:text-secondary'}`}>
                              chat_bubble
                            </span>
                            <span className="min-w-0 flex-1">
                              <span className="block truncate font-mono-sm text-[10px] font-medium leading-4">{title}</span>
                              <span className="block truncate font-mono-sm text-[9px] leading-3 text-on-surface-variant/70">
                                {formatDateTime(chat.updatedAt || chat.createdAt)}
                                {chat.modelDisplayName ? ` · ${chat.modelDisplayName}` : ''}
                              </span>
                            </span>
                          </button>
                          <button
                            type="button"
                            data-testid={`rename-chat-${chat.id}`}
                            onClick={() => requestRenameWorkspace('chat', chat)}
                            disabled={deleting || deletingAllChats || renamingWorkspaceId === chat.id}
                            aria-label={`Rename chat ${title}`}
                            title="Rename chat"
                            className={`flex h-6 w-6 shrink-0 items-center justify-center rounded text-on-surface-variant transition-opacity hover:bg-secondary/10 hover:text-secondary focus-visible:opacity-100 group-hover:opacity-100 disabled:cursor-not-allowed ${active || renamingWorkspaceId === chat.id ? 'opacity-100' : 'opacity-0'}`}
                          >
                            <span className={`material-symbols-outlined text-[14px] ${renamingWorkspaceId === chat.id ? 'animate-spin' : ''}`}>
                              {renamingWorkspaceId === chat.id ? 'progress_activity' : 'edit'}
                            </span>
                          </button>
                          <button
                            type="button"
                            onClick={() => requestDeleteChat(chat)}
                            disabled={deleting || deletingAllChats}
                            aria-label={`Delete chat ${title}`}
                            title="Delete chat"
                            className={`mr-1 flex h-6 w-6 shrink-0 items-center justify-center rounded text-on-surface-variant transition-opacity hover:bg-status-error/15 hover:text-status-error focus-visible:opacity-100 group-hover:opacity-100 disabled:cursor-not-allowed ${active || deleting ? 'opacity-100' : 'opacity-0'}`}
                          >
                            <span className={`material-symbols-outlined text-[14px] ${deleting ? 'animate-spin' : ''}`}>
                              {deleting ? 'progress_activity' : 'delete'}
                            </span>
                          </button>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
            <div>
              <button
                type="button"
                onClick={() => {
                  if (!sidebarOpen) {
                    setSidebarOpen(true);
                    setDraftsOpen(true);
                    return;
                  }
                  setDraftsOpen((open) => !open);
                }}
                className={navItemClass(route.page === 'draft')}
                aria-label={!sidebarOpen ? 'Open drafts' : draftsOpen ? 'Collapse drafts' : 'Expand drafts'}
                aria-expanded={sidebarOpen && draftsOpen}
                title="Drafts"
              >
                <span className="material-symbols-outlined shrink-0 text-[18px]">edit_document</span>
                {route.page === 'draft' && navActiveBar}
                <span className={sidebarOpen ? 'inline min-w-0 flex-1 truncate text-left' : 'hidden'}>Drafts</span>
                {sidebarOpen && (
                  <span className="flex shrink-0 items-center gap-1.5">
                    <span className="rounded-md border border-border-subtle px-1.5 py-0.5 font-mono-sm text-[9px] text-on-surface-variant">
                      {draftSummaries.length}
                    </span>
                    <span className="material-symbols-outlined text-[16px]">
                      {draftsOpen ? 'expand_more' : 'chevron_right'}
                    </span>
                  </span>
                )}
              </button>

              {sidebarOpen && draftsOpen && (
                <div className="mx-1 mt-1 rounded-lg border border-border-subtle bg-surface-container-lowest/65 p-1.5">
                  <div className="flex items-center gap-1.5">
                    <div className="relative flex-1">
                      <span className="material-symbols-outlined pointer-events-none absolute left-2 top-1/2 -translate-y-1/2 text-[15px] text-on-surface-variant">
                        search
                      </span>
                      <input
                        type="search"
                        value={draftSearch}
                        onChange={(event) => setDraftSearch(event.target.value)}
                        placeholder="Search drafts"
                        aria-label="Search drafts"
                        className="h-8 w-full rounded-md border border-border-subtle bg-surface-container-low pl-7 pr-7 font-mono-sm text-[10px] text-on-surface outline-none placeholder:text-on-surface-variant/65 focus:border-secondary/55 focus:ring-1 focus:ring-secondary/20"
                      />
                      {draftSearch && (
                        <button
                          type="button"
                          onClick={() => setDraftSearch('')}
                          aria-label="Clear draft search"
                          className="absolute right-1 top-1/2 flex h-6 w-6 -translate-y-1/2 items-center justify-center rounded text-on-surface-variant transition-colors hover:bg-surface-container hover:text-on-surface"
                        >
                          <span className="material-symbols-outlined text-[14px]">close</span>
                        </button>
                      )}
                    </div>
                    {draftSummaries.length > 0 && (
                      <button
                        type="button"
                        onClick={requestDeleteAllDrafts}
                        disabled={deletingAllDrafts || Boolean(deletingDraftId)}
                        aria-label="Delete all drafts"
                        title="Delete every unsaved manual workflow draft"
                        className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-border-subtle text-on-surface-variant transition-colors hover:border-status-error/40 hover:bg-status-error/15 hover:text-status-error disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        <span className={`material-symbols-outlined text-[15px] ${deletingAllDrafts ? 'animate-spin' : ''}`}>
                          {deletingAllDrafts ? 'progress_activity' : 'delete_sweep'}
                        </span>
                      </button>
                    )}
                  </div>

                  <div className="mt-1.5 max-h-[min(32vh,280px)] space-y-0.5 overflow-y-auto pr-0.5" role="region" aria-label="Draft history">
                    {draftListLoading && draftSummaries.length === 0 ? (
                      <div className="px-2 py-3 text-center font-mono-sm text-[10px] text-on-surface-variant">Loading drafts...</div>
                    ) : draftListError && draftSummaries.length === 0 ? (
                      <button
                        type="button"
                        onClick={loadDrafts}
                        className="w-full rounded-md px-2 py-3 text-center font-mono-sm text-[10px] text-status-error transition-colors hover:bg-surface-container-low"
                      >
                        {draftListError} Retry
                      </button>
                    ) : filteredDraftSummaries.length === 0 ? (
                      <div className="px-2 py-3 text-center font-mono-sm text-[10px] text-on-surface-variant">
                        {draftSearch ? 'No matching drafts' : 'No manual drafts yet'}
                      </div>
                    ) : filteredDraftSummaries.map((draft) => {
                      const active = route.page === 'draft' && route.draftId === draft.id;
                      const title = compactChatTitle(draft);
                      const deleting = deletingDraftId === draft.id;
                      return (
                        <div
                          key={draft.id}
                          className={`group relative flex w-full min-w-0 items-center rounded-md transition-colors ${
                            active
                              ? 'bg-primary/10 text-on-surface'
                              : 'text-on-surface-variant hover:bg-surface-container-low hover:text-on-surface'
                          } ${deleting ? 'opacity-60' : ''}`}
                        >
                          {active && <span className="absolute left-0 top-1/2 h-5 w-[2px] -translate-y-1/2 rounded-r-full bg-secondary" />}
                          <button
                            type="button"
                            onClick={() => handleDraftSelected(draft)}
                            aria-label={`Open draft ${title}`}
                            aria-current={active ? 'page' : undefined}
                            title={title}
                            className="flex min-w-0 flex-1 items-center gap-2 rounded-md px-2 py-1.5 text-left"
                          >
                            <span className={`material-symbols-outlined shrink-0 text-[15px] ${active ? 'text-secondary' : 'text-on-surface-variant group-hover:text-secondary'}`}>
                              draft
                            </span>
                            <span className="min-w-0 flex-1">
                              <span className="block truncate font-mono-sm text-[10px] font-medium leading-4">{title}</span>
                              <span className="block truncate font-mono-sm text-[9px] leading-3 text-on-surface-variant/70">
                                {formatDateTime(draft.updatedAt || draft.createdAt)}
                                {draft.modelDisplayName ? ` · ${draft.modelDisplayName}` : ''}
                              </span>
                            </span>
                          </button>
                          <button
                            type="button"
                            data-testid={`rename-draft-${draft.id}`}
                            onClick={() => requestRenameWorkspace('draft', draft)}
                            disabled={deleting || deletingAllDrafts || renamingWorkspaceId === draft.id}
                            aria-label={`Rename draft ${title}`}
                            title="Rename draft"
                            className={`flex h-6 w-6 shrink-0 items-center justify-center rounded text-on-surface-variant transition-opacity hover:bg-secondary/10 hover:text-secondary focus-visible:opacity-100 group-hover:opacity-100 disabled:cursor-not-allowed ${active || renamingWorkspaceId === draft.id ? 'opacity-100' : 'opacity-0'}`}
                          >
                            <span className={`material-symbols-outlined text-[14px] ${renamingWorkspaceId === draft.id ? 'animate-spin' : ''}`}>
                              {renamingWorkspaceId === draft.id ? 'progress_activity' : 'edit'}
                            </span>
                          </button>
                          <button
                            type="button"
                            onClick={() => requestDeleteDraft(draft)}
                            disabled={deleting || deletingAllDrafts}
                            aria-label={`Delete draft ${title}`}
                            title="Delete draft"
                            className={`mr-1 flex h-6 w-6 shrink-0 items-center justify-center rounded text-on-surface-variant transition-opacity hover:bg-status-error/15 hover:text-status-error focus-visible:opacity-100 group-hover:opacity-100 disabled:cursor-not-allowed ${active || deleting ? 'opacity-100' : 'opacity-0'}`}
                          >
                            <span className={`material-symbols-outlined text-[14px] ${deleting ? 'animate-spin' : ''}`}>
                              {deleting ? 'progress_activity' : 'delete'}
                            </span>
                          </button>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
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
            <button
              onClick={() => navigate('/docs')}
              className={navItemClass(route.page === 'docs')}
              aria-label="Docs"
              title="Docs"
            >
              <span className="material-symbols-outlined shrink-0 text-[18px]">description</span>
              {route.page === 'docs' && navActiveBar}
              <span className={sidebarOpen ? 'inline truncate' : 'hidden'}>Docs</span>
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
                {route.page === 'functions' ? 'Functions' : route.page === 'mcp' ? 'MCP Servers' : route.page === 'docs' ? 'Docs' : 'Workflow Executions'}
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
                    data-testid="workflow-revision-history"
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
              ) : route.page === 'create' || route.page === 'chat' || route.page === 'draft' ? (
                <CreateWorkflowPage
                  key={newWorkflowResetKey}
                  routeChatId={route.page === 'chat' ? route.chatId : undefined}
                  routeDraftId={route.page === 'draft' ? route.draftId : undefined}
                  onWorkflowCreated={handleWorkflowCreated}
                  onNavigate={navigate}
                  onConversationUpdated={loadWorkspaceLists}
                />
              ) : route.page === 'functions' ? (
                <FunctionsPage onWorkbenchModeChange={setFunctionsWorkbenchActive} />
              ) : route.page === 'mcp' ? (
                <McpServersPage />
              ) : route.page === 'docs' ? (
                <DocsPage slug={route.slug} onNavigate={navigate} />
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
      {chatDeleteTarget && (
        <ChatDeleteDialog
          target={chatDeleteTarget}
          busy={chatDeleteTarget.workspaceKind === 'draft'
            ? Boolean(deletingDraftId) || deletingAllDrafts
            : Boolean(deletingChatId) || deletingAllChats}
          onCancel={() => setChatDeleteTarget(null)}
          onConfirm={confirmChatDelete}
        />
      )}
      {workspaceRenameTarget && (
        <WorkspaceRenameDialog
          target={workspaceRenameTarget}
          value={workspaceRenameValue}
          busy={renamingWorkspaceId === workspaceRenameTarget.workspace.id}
          onValueChange={setWorkspaceRenameValue}
          onCancel={cancelRenameWorkspace}
          onConfirm={() => void performRenameWorkspace()}
        />
      )}
    </div>
  );
}

function WorkspaceRenameDialog({
  target,
  value,
  busy,
  onValueChange,
  onCancel,
  onConfirm,
}: {
  target: WorkspaceRenameTarget;
  value: string;
  busy: boolean;
  onValueChange: (value: string) => void;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const noun = target.workspaceKind === 'draft' ? 'draft' : 'chat';
  const valid = value.trim().length > 0 && value.trim().length <= 120;

  return (
    <div
      className="fixed inset-0 z-[100] flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-labelledby="workspace-rename-title"
      onClick={busy ? undefined : onCancel}
    >
      <form
        className="w-full max-w-[440px] rounded-xl border border-border-subtle bg-surface-container-lowest p-5 shadow-[0_30px_80px_rgba(0,0,0,0.5)]"
        onClick={(event) => event.stopPropagation()}
        onSubmit={(event) => {
          event.preventDefault();
          if (valid && !busy) onConfirm();
        }}
      >
        <div className="flex items-start gap-3">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-secondary/35 bg-secondary/10 text-secondary">
            <span className="material-symbols-outlined text-[18px]">edit</span>
          </div>
          <div className="min-w-0 flex-1">
            <h3 id="workspace-rename-title" className="text-[15px] font-semibold text-on-surface">
              Rename {noun}
            </h3>
            <p className="mt-1.5 text-[12px] leading-5 text-on-surface-variant">
              Give this {noun} a memorable name for search and navigation.
            </p>
          </div>
        </div>
        <label className="mt-4 block text-[11px] font-medium text-on-surface-variant" htmlFor="workspace-rename-input">
          Name
        </label>
        <input
          id="workspace-rename-input"
          autoFocus
          maxLength={120}
          value={value}
          onChange={(event) => onValueChange(event.target.value)}
          aria-label={`${target.workspaceKind === 'draft' ? 'Draft' : 'Chat'} name`}
          className="mt-1.5 h-10 w-full rounded-lg border border-border-subtle bg-surface-container-low px-3 font-mono-sm text-[12px] text-on-surface outline-none placeholder:text-on-surface-variant/60 focus:border-secondary/60 focus:ring-1 focus:ring-secondary/20"
        />
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            disabled={busy}
            className="flex h-9 items-center rounded-lg border border-border-subtle px-4 text-[12px] text-on-surface-variant transition-colors hover:text-on-surface disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={busy || !valid}
            className="flex h-9 items-center gap-2 rounded-lg border border-secondary bg-secondary/90 px-4 text-[12px] font-semibold text-on-secondary transition-colors hover:bg-secondary disabled:cursor-not-allowed disabled:opacity-50"
          >
            {busy && <span className="material-symbols-outlined animate-spin text-[16px]">progress_activity</span>}
            Save name
          </button>
        </div>
      </form>
    </div>
  );
}

function ChatDeleteDialog({
  target,
  busy,
  onCancel,
  onConfirm,
}: {
  target: WorkspaceDeleteTarget;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const single = target.kind === 'one';
  const noun = target.workspaceKind === 'draft' ? 'draft' : 'chat';
  const title = single ? `Delete this ${noun}?` : `Delete all ${noun}s?`;
  const message = single
    ? `"${compactChatTitle(target.workspace)}" and the workflow JSON, canvas, settings, and AI messages inside it will be permanently deleted. Any workflow you already created from it is not affected.`
    : `All ${target.count} ${noun}${target.count === 1 ? '' : 's'} and their saved workspace data will be permanently deleted. Any workflows you already created are not affected.`;
  const confirmLabel = single
    ? `Delete ${noun}`
    : `Delete ${target.count} ${noun}${target.count === 1 ? '' : 's'}`;

  return (
    <div
      className="fixed inset-0 z-[100] flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      onClick={busy ? undefined : onCancel}
    >
      <div
        className="w-full max-w-[440px] rounded-xl border border-border-subtle bg-surface-container-lowest p-5 shadow-[0_30px_80px_rgba(0,0,0,0.5)]"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-start gap-3">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-status-error/35 bg-status-error/10 text-status-error">
            <span className="material-symbols-outlined text-[18px]">warning</span>
          </div>
          <div className="min-w-0">
            <h3 className="text-[15px] font-semibold text-on-surface">{title}</h3>
            <p className="mt-1.5 text-[12px] leading-5 text-on-surface-variant">{message}</p>
          </div>
        </div>
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            disabled={busy}
            className="flex h-9 items-center rounded-lg border border-border-subtle px-4 text-[12px] text-on-surface-variant transition-colors hover:text-on-surface disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={busy}
            className="flex h-9 items-center gap-2 rounded-lg border border-status-error bg-status-error/90 px-4 text-[12px] font-semibold text-white transition-colors hover:bg-status-error disabled:cursor-not-allowed disabled:opacity-50"
          >
            {busy && <span className="material-symbols-outlined animate-spin text-[16px]">progress_activity</span>}
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

export default App;
