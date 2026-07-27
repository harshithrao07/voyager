import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  Braces,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Copy,
  Loader2,
  Play,
  RefreshCw,
  Search,
  Sparkles,
  Square,
  Wrench,
  X,
} from 'lucide-react';
import {
  cancelWorkflowExecution,
  getWorkflowExecution,
  listWorkflowExecutions,
  startWorkflowExecution,
  triageWorkflowExecution,
  type WorkflowExecutionDetailDTO,
  type WorkflowExecutionPageDTO,
  type WorkflowExecutionStatusDTO,
  type WorkflowExecutionTriggerDTO,
  type WorkflowResponseDTO,
  type WorkflowRuntimeStatusDTO,
  type WorkflowStateExecutionAttemptDTO,
  type WorkflowStateExecutionDTO,
  type WorkflowTriageResponse,
} from '../api';
import { setPendingTriagePatch } from './workflow-create/triagePatchStore';

const PAGE_SIZE = 20;
const POLL_INTERVAL_MS = 1_500;

const ACTIVE_EXECUTION_STATUSES = new Set<WorkflowExecutionStatusDTO>([
  'PENDING',
  'QUEUED',
  'RUNNING',
  'WAITING',
]);

const ACTIVE_RUNTIME_STATUSES = new Set<WorkflowRuntimeStatusDTO>([
  'PENDING',
  'QUEUED',
  'RUNNING',
  'WAITING',
  'RETRY_WAIT',
]);

const EXECUTION_STATUSES: WorkflowExecutionStatusDTO[] = [
  'PENDING',
  'QUEUED',
  'RUNNING',
  'WAITING',
  'SUCCEEDED',
  'FAILED',
  'CANCELED',
  'TIMED_OUT',
];

type Props = {
  workflow: WorkflowResponseDTO;
  selectedRevisionNumber: number | null;
  onNavigate?: (path: string) => void;
};

function isActiveExecution(status?: WorkflowExecutionStatusDTO | null) {
  return status ? ACTIVE_EXECUTION_STATUSES.has(status) : false;
}

function statusLabel(status: string) {
  return status.replaceAll('_', ' ');
}

function statusClasses(status: WorkflowExecutionStatusDTO | WorkflowRuntimeStatusDTO) {
  if (status === 'SUCCEEDED') {
    return 'border-status-success bg-surface-container-high text-status-success';
  }
  if (status === 'FAILED' || status === 'TIMED_OUT') {
    return 'border-status-error bg-surface-container-high text-status-error';
  }
  if (status === 'CANCELED') {
    return 'border-border-muted bg-surface-container-high text-on-surface-variant';
  }
  if (status === 'WAITING' || status === 'RETRY_WAIT') {
    return 'border-status-warning bg-surface-container-high text-status-warning';
  }
  return 'border-status-info bg-surface-container-high text-status-info';
}

function statusIcon(status: WorkflowExecutionStatusDTO | WorkflowRuntimeStatusDTO) {
  if (status === 'SUCCEEDED') return 'check_circle';
  if (status === 'FAILED' || status === 'TIMED_OUT') return 'error';
  if (status === 'CANCELED') return 'cancel';
  if (status === 'WAITING' || status === 'RETRY_WAIT') return 'schedule';
  return 'progress_activity';
}

function formatDateTime(value?: string | null) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(date);
}

function formatDuration(startedAt?: string | null, completedAt?: string | null) {
  if (!startedAt || !completedAt) return '—';
  const started = new Date(startedAt).getTime();
  const completed = new Date(completedAt).getTime();
  if (!Number.isFinite(started) || !Number.isFinite(completed)) return '—';
  const durationMs = Math.max(0, completed - started);
  if (durationMs < 1_000) return `${durationMs}ms`;
  if (durationMs < 60_000) return `${(durationMs / 1_000).toFixed(1)}s`;
  const minutes = Math.floor(durationMs / 60_000);
  const seconds = Math.floor((durationMs % 60_000) / 1_000);
  return `${minutes}m ${seconds}s`;
}

function formatJson(value: unknown) {
  return JSON.stringify(value ?? null, null, 2);
}

function scopeLabel(scope: WorkflowExecutionDetailDTO['scopes'][number]) {
  if (scope.scopeType === 'PARALLEL_BRANCH') {
    return `Parallel branch ${(scope.branchIndex ?? 0) + 1}`;
  }
  if (scope.scopeType === 'MAP_ITERATION') {
    return `Map iteration ${scope.itemIndex ?? 0}`;
  }
  return 'Root workflow';
}

function runButtonMessage(workflow: WorkflowResponseDTO) {
  if (workflow.status === 'ARCHIVED') return 'Archived workflows cannot be executed.';
  if (workflow.status === 'DRAFT') return 'Activate the recurring workflow revision before executing it.';
  if (!workflow.activeDefinition) return 'This workflow has no active definition.';
  return null;
}

/**
 * AI failure triage for a failed/timed-out execution: diagnoses the root cause and, when the fix is
 * in the workflow, offers a validated ASL patch. "Apply patch" stashes the corrected definition and
 * opens the revision editor pre-loaded with it (the user reviews and saves through the normal path).
 */
function TriagePanel({
  workflowId,
  executionId,
  editRevision,
  onNavigate,
}: {
  workflowId: string;
  executionId: string;
  editRevision: number | null;
  onNavigate?: (path: string) => void;
}) {
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<WorkflowTriageResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  // A different execution is a different diagnosis; drop any stale result.
  useEffect(() => {
    setResult(null);
    setError(null);
  }, [executionId]);

  const diagnose = async () => {
    setLoading(true);
    setError(null);
    try {
      setResult(await triageWorkflowExecution(workflowId, executionId));
    } catch (diagnoseError) {
      setError(diagnoseError instanceof Error ? diagnoseError.message : 'Diagnosis failed.');
    } finally {
      setLoading(false);
    }
  };

  const patch = result?.patch;
  const canApply = Boolean(patch?.hasPatch && patch.valid && editRevision != null && onNavigate);

  const applyPatch = () => {
    if (!patch?.aslDefinition || editRevision == null) return;
    setPendingTriagePatch(workflowId, patch.aslDefinition);
    onNavigate?.(`/workflows/${encodeURIComponent(workflowId)}/revisions/${editRevision}/edit`);
  };

  return (
    <div className="rounded border border-status-info/40 bg-surface-container-high p-3" data-testid="execution-triage">
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-2 font-body-sm text-body-sm font-medium text-status-info">
          <Sparkles size={15} /> AI failure triage
        </div>
        {!result && (
          <button
            type="button"
            data-testid="execution-triage-run"
            onClick={() => void diagnose()}
            disabled={loading}
            className="flex items-center gap-1.5 rounded border border-status-info/50 bg-status-info/10 px-2.5 py-1 font-body-sm text-[12px] text-status-info transition-colors hover:bg-status-info/20 disabled:opacity-50"
          >
            {loading ? <Loader2 size={13} className="animate-spin" /> : <Sparkles size={13} />}
            {loading ? 'Diagnosing…' : 'Diagnose with AI'}
          </button>
        )}
      </div>

      {error && <p className="mt-2 text-body-sm text-status-error">{error}</p>}

      {result && (
        <div className="mt-2 flex flex-col gap-2 text-body-sm" data-testid="execution-triage-result">
          <div>
            <div className="font-label-caps text-label-caps uppercase tracking-widest text-on-surface-variant">Root cause</div>
            <p className="mt-0.5 text-on-surface">{result.rootCause}</p>
          </div>
          {result.explanation && (
            <p className="whitespace-pre-wrap text-[12.5px] leading-relaxed text-on-surface-variant">{result.explanation}</p>
          )}

          {patch?.hasPatch ? (
            <div className="rounded border border-border-muted bg-surface-lowest p-2.5">
              <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-1.5 font-body-sm text-[12px] font-medium text-on-surface">
                  <Wrench size={13} /> Proposed fix
                </div>
                {patch.valid ? (
                  <span className="flex items-center gap-1 rounded border border-status-success/40 bg-status-success/10 px-1.5 py-0.5 font-mono-sm text-[10px] text-status-success">
                    <CheckCircle2 size={10} /> Validates
                  </span>
                ) : (
                  <span className="flex items-center gap-1 rounded border border-status-error/40 bg-status-error/10 px-1.5 py-0.5 font-mono-sm text-[10px] text-status-error">
                    <AlertTriangle size={10} /> Needs review
                  </span>
                )}
              </div>
              {patch.changes.length > 0 && (
                <ul className="mt-1.5 list-disc pl-4 text-[12px] text-on-surface-variant">
                  {patch.changes.map((change, index) => <li key={index}>{change}</li>)}
                </ul>
              )}
              {!patch.valid && patch.validationIssues.length > 0 && (
                <ul className="mt-1.5 list-disc pl-4 text-[11.5px] text-status-error">
                  {patch.validationIssues.map((issue, index) => <li key={index}>{issue}</li>)}
                </ul>
              )}
              <button
                type="button"
                data-testid="execution-triage-apply"
                onClick={applyPatch}
                disabled={!canApply}
                title={patch.valid ? 'Open the corrected ASL in the revision editor' : 'The proposed ASL did not validate; open it anyway to edit'}
                className="mt-2 flex items-center gap-1.5 rounded border border-status-info bg-status-info/15 px-2.5 py-1 font-body-sm text-[12px] text-status-info transition-colors hover:bg-status-info/25 disabled:cursor-not-allowed disabled:opacity-50"
              >
                <Wrench size={13} /> Apply patch in editor
              </button>
            </div>
          ) : (
            <p className="text-[12px] text-on-surface-variant">
              No workflow change proposed — the fix is likely outside the definition (an outage, credentials, or input).
            </p>
          )}

          <button
            type="button"
            onClick={() => void diagnose()}
            disabled={loading}
            className="self-start text-[11px] text-on-surface-variant underline-offset-2 transition-colors hover:text-on-surface hover:underline disabled:opacity-50"
          >
            {loading ? 'Diagnosing…' : 'Re-diagnose'}
          </button>
        </div>
      )}
    </div>
  );
}

export function ExecutionStatusView({ workflow, selectedRevisionNumber, onNavigate }: Props) {
  const [page, setPage] = useState(0);
  const [executionPage, setExecutionPage] = useState<WorkflowExecutionPageDTO | null>(null);
  const [selectedExecutionId, setSelectedExecutionId] = useState<string | null>(null);
  const [detail, setDetail] = useState<WorkflowExecutionDetailDTO | null>(null);
  const [listLoading, setListLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(true);
  const [listError, setListError] = useState<string | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<WorkflowExecutionStatusDTO | ''>('');
  const [revisionFilter, setRevisionFilter] = useState('');
  const [triggerFilter, setTriggerFilter] = useState<WorkflowExecutionTriggerDTO | ''>('');
  const [triggerDialogOpen, setTriggerDialogOpen] = useState(false);
  const [executionInput, setExecutionInput] = useState('{}');
  const [triggerError, setTriggerError] = useState<string | null>(null);
  const [triggerBusy, setTriggerBusy] = useState(false);
  const [cancelDialogOpen, setCancelDialogOpen] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);
  const [cancelBusy, setCancelBusy] = useState(false);
  const [showRawDetail, setShowRawDetail] = useState(false);
  const [copied, setCopied] = useState(false);

  const executionListRequest = useMemo(() => ({
    page,
    size: PAGE_SIZE,
    status: statusFilter || undefined,
    revision: revisionFilter ? Number(revisionFilter) : undefined,
    trigger: triggerFilter || undefined,
    search: search || undefined,
  }), [page, revisionFilter, search, statusFilter, triggerFilter]);

  const hasExecutionFilters = Boolean(
    search || statusFilter || revisionFilter || triggerFilter,
  );

  useEffect(() => {
    const timeout = window.setTimeout(() => {
      setListLoading(true);
      setPage(0);
      setSearch(searchInput.trim());
    }, 300);
    return () => window.clearTimeout(timeout);
  }, [searchInput]);

  const loadExecutionPage = useCallback(async (quiet = false) => {
    if (!quiet) setListLoading(true);
    try {
      const response = await listWorkflowExecutions(workflow.id, executionListRequest);
      setExecutionPage(response);
      setListError(null);
      setSelectedExecutionId((current) => {
        if (current && response.content.some((execution) => execution.id === current)) {
          return current;
        }
        return response.content[0]?.id ?? null;
      });
    } catch (error) {
      setListError(error instanceof Error ? error.message : 'Could not load workflow executions.');
    } finally {
      if (!quiet) setListLoading(false);
    }
  }, [executionListRequest, workflow.id]);

  const loadSelectedDetail = useCallback(async (quiet = false) => {
    if (!selectedExecutionId) {
      setDetail(null);
      setDetailError(null);
      return;
    }
    if (!quiet) setDetailLoading(true);
    try {
      const response = await getWorkflowExecution(workflow.id, selectedExecutionId);
      setDetail(response);
      setDetailError(null);
    } catch (error) {
      setDetailError(error instanceof Error ? error.message : 'Could not load execution details.');
    } finally {
      if (!quiet) setDetailLoading(false);
    }
  }, [selectedExecutionId, workflow.id]);

  useEffect(() => {
    let cancelled = false;
    listWorkflowExecutions(workflow.id, executionListRequest)
      .then((response) => {
        if (cancelled) return;
        setExecutionPage(response);
        setListError(null);
        setSelectedExecutionId((current) => {
          if (current && response.content.some((execution) => execution.id === current)) {
            return current;
          }
          return response.content[0]?.id ?? null;
        });
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setListError(error instanceof Error ? error.message : 'Could not load workflow executions.');
        }
      })
      .finally(() => {
        if (!cancelled) setListLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [executionListRequest, workflow.id]);

  useEffect(() => {
    if (!selectedExecutionId) return undefined;
    let cancelled = false;
    getWorkflowExecution(workflow.id, selectedExecutionId)
      .then((response) => {
        if (cancelled) return;
        setDetail(response);
        setDetailError(null);
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setDetailError(error instanceof Error ? error.message : 'Could not load execution details.');
        }
      })
      .finally(() => {
        if (!cancelled) setDetailLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [selectedExecutionId, workflow.id]);

  const selectedDetail = detail?.execution.id === selectedExecutionId
    ? detail
    : null;

  const shouldPoll = Boolean(
    (workflow.status === 'ACTIVE' && workflow.cronExpression)
      || executionPage?.content.some((execution) => isActiveExecution(execution.status))
      || isActiveExecution(selectedDetail?.execution.status),
  );

  useEffect(() => {
    if (!shouldPoll) return undefined;
    const interval = window.setInterval(() => {
      void loadExecutionPage(true);
      void loadSelectedDetail(true);
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(interval);
  }, [loadExecutionPage, loadSelectedDetail, shouldPoll]);

  const filteredExecutions = executionPage?.content ?? [];

  const selectedSummary = selectedDetail?.execution
    ?? executionPage?.content.find((execution) => execution.id === selectedExecutionId)
    ?? null;
  const activeRevision = workflow.activeDefinition?.revision ?? null;
  const selectedRevisionIsHistorical = selectedRevisionNumber != null
    && activeRevision != null
    && selectedRevisionNumber !== activeRevision;
  const triggerDisabledReason = runButtonMessage(workflow);
  const cancelEnabled = isActiveExecution(selectedSummary?.status);
  const activeLoadedCount = executionPage?.content.filter(
    (execution) => isActiveExecution(execution.status),
  ).length ?? 0;

  const handleTrigger = async () => {
    let input: unknown;
    try {
      input = JSON.parse(executionInput);
    } catch (error) {
      setTriggerError(error instanceof Error ? `Invalid JSON: ${error.message}` : 'Input must be valid JSON.');
      return;
    }

    setTriggerBusy(true);
    setTriggerError(null);
    try {
      const response = await startWorkflowExecution(workflow.id, { input });
      setTriggerDialogOpen(false);
      setExecutionInput('{}');
      setPage(0);
      setSearchInput('');
      setSearch('');
      setStatusFilter('');
      setRevisionFilter('');
      setTriggerFilter('');
      setSelectedExecutionId(response.workflowExecutionId);
      setDetailLoading(true);
      const [nextPage, nextDetail] = await Promise.all([
        listWorkflowExecutions(workflow.id, { page: 0, size: PAGE_SIZE }),
        getWorkflowExecution(workflow.id, response.workflowExecutionId),
      ]);
      setExecutionPage(nextPage);
      setDetail(nextDetail);
      setListError(null);
      setDetailError(null);
    } catch (error) {
      setTriggerError(error instanceof Error ? error.message : 'Could not start the workflow execution.');
    } finally {
      setTriggerBusy(false);
      setDetailLoading(false);
    }
  };

  const handleCancel = async () => {
    if (!selectedExecutionId || !cancelEnabled) return;
    setCancelBusy(true);
    setCancelError(null);
    try {
      await cancelWorkflowExecution(workflow.id, selectedExecutionId);
      const [nextPage, nextDetail] = await Promise.all([
        listWorkflowExecutions(workflow.id, executionListRequest),
        getWorkflowExecution(workflow.id, selectedExecutionId),
      ]);
      setExecutionPage(nextPage);
      setDetail(nextDetail);
      setCancelDialogOpen(false);
    } catch (error) {
      setCancelError(error instanceof Error ? error.message : 'Could not cancel the execution.');
    } finally {
      setCancelBusy(false);
    }
  };

  const handleCopyExecutionId = async () => {
    if (!selectedExecutionId) return;
    try {
      await navigator.clipboard.writeText(selectedExecutionId);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1_500);
    } catch {
      setCopied(false);
    }
  };

  const showingStart = executionPage && executionPage.totalElements > 0
    ? executionPage.page * executionPage.size + 1
    : 0;
  const showingEnd = executionPage
    ? Math.min((executionPage.page + 1) * executionPage.size, executionPage.totalElements)
    : 0;

  return (
    <div className="flex h-full min-h-0 flex-col bg-surface">
      <div className="shrink-0 border-b border-border-subtle bg-surface-elevated px-gutter py-2">
        <div className="flex min-w-0 items-center justify-between gap-4">
          <div className="flex min-w-0 items-center gap-4">
            <div className="min-w-0">
              <h1 className="truncate font-headline-lg text-headline-lg text-primary">{workflow.name}</h1>
              <div className="mt-0.5 flex flex-wrap items-center gap-2 font-mono-sm text-[11px] text-on-surface-variant">
                <span>Active revision: {activeRevision ? `Rev ${activeRevision}` : 'None'}</span>
                {selectedRevisionNumber != null && <span>Viewing: Rev {selectedRevisionNumber}</span>}
                {selectedRevisionIsHistorical && (
                  <span className="rounded border border-status-warning bg-surface-container-high px-1.5 py-0.5 text-status-warning">
                    New runs use Rev {activeRevision}
                  </span>
                )}
              </div>
            </div>
            <div className="hidden h-8 w-px bg-border-muted xl:block" />
            <div className="hidden items-center gap-4 text-body-sm font-body-sm xl:flex">
              <span className="text-on-surface-variant">
                {hasExecutionFilters ? 'Matching' : 'Total'} <span className="font-mono-sm text-primary">{executionPage?.totalElements ?? '—'}</span>
              </span>
              <span className="text-on-surface-variant">
                Active on page <span className="font-mono-sm text-status-info">{activeLoadedCount}</span>
              </span>
            </div>
          </div>

          <div className="flex shrink-0 items-center gap-2">
            <button
              type="button"
              data-testid="execution-cancel-selected"
              onClick={() => {
                setCancelError(null);
                setCancelDialogOpen(true);
              }}
              disabled={!cancelEnabled || cancelBusy}
              title={cancelEnabled ? 'Cancel the selected execution' : 'Select an active execution to cancel'}
              className="flex items-center gap-2 rounded border border-border-muted bg-surface-container-high px-3 py-1.5 font-body-sm text-body-sm text-on-surface transition-colors hover:bg-surface-container-highest disabled:cursor-not-allowed disabled:opacity-50"
            >
              <Square size={15} />
              Cancel selected
            </button>
            <button
              type="button"
              data-testid="execution-trigger-run"
              onClick={() => {
                setTriggerError(null);
                setTriggerDialogOpen(true);
              }}
              disabled={Boolean(triggerDisabledReason) || triggerBusy}
              title={triggerDisabledReason || `Run active revision ${activeRevision}`}
              className="flex items-center gap-2 rounded border border-primary bg-primary px-3 py-1.5 font-body-sm text-body-sm font-medium text-on-primary transition-colors hover:bg-primary-container disabled:cursor-not-allowed disabled:opacity-50"
            >
              <Play size={15} fill="currentColor" />
              Trigger run
            </button>
          </div>
        </div>
      </div>

      <div className="flex min-h-0 flex-1 overflow-hidden">
        <section className="flex min-w-[400px] w-[46%] flex-col border-r border-border-subtle bg-surface-lowest">
          <div className="shrink-0 space-y-2 border-b border-border-muted bg-surface p-2">
            <div className="flex items-center gap-2">
              <div className="relative flex-1">
                <Search size={16} className="absolute left-2 top-1/2 -translate-y-1/2 text-on-surface-variant" />
                <input
                  data-testid="execution-filter-search"
                  value={searchInput}
                  onChange={(event) => setSearchInput(event.target.value)}
                  className="w-full rounded border border-border-muted bg-surface-lowest py-1.5 pl-8 pr-2 font-body-sm text-body-sm text-primary placeholder:text-on-surface-variant focus:border-status-info focus:outline-none focus:ring-1 focus:ring-status-info"
                  placeholder="Exact execution ID or run number"
                  type="search"
                  aria-label="Search workflow executions"
                />
              </div>
              <button
                type="button"
                onClick={() => void loadExecutionPage()}
                disabled={listLoading}
                className="rounded border border-border-muted bg-surface-container-high p-1.5 text-on-surface-variant transition-colors hover:text-primary disabled:opacity-50"
                aria-label="Refresh executions"
                title="Refresh executions"
              >
                <RefreshCw size={17} className={listLoading ? 'animate-spin' : ''} />
              </button>
            </div>
            <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_92px_34px] gap-2">
              <select
                data-testid="execution-filter-status"
                value={statusFilter}
                onChange={(event) => {
                  setListLoading(true);
                  setPage(0);
                  setStatusFilter(event.target.value as WorkflowExecutionStatusDTO | '');
                }}
                className="min-w-0 rounded border border-border-muted bg-surface-lowest px-2 py-1.5 font-body-sm text-body-sm text-primary outline-none focus:border-status-info focus:ring-1 focus:ring-status-info"
                aria-label="Filter executions by status"
              >
                <option value="">All statuses</option>
                {EXECUTION_STATUSES.map((status) => (
                  <option key={status} value={status}>{statusLabel(status)}</option>
                ))}
              </select>
              <select
                data-testid="execution-filter-trigger"
                value={triggerFilter}
                onChange={(event) => {
                  setListLoading(true);
                  setPage(0);
                  setTriggerFilter(event.target.value as WorkflowExecutionTriggerDTO | '');
                }}
                className="min-w-0 rounded border border-border-muted bg-surface-lowest px-2 py-1.5 font-body-sm text-body-sm text-primary outline-none focus:border-status-info focus:ring-1 focus:ring-status-info"
                aria-label="Filter executions by trigger"
              >
                <option value="">All triggers</option>
                <option value="MANUAL">Manual</option>
                <option value="SCHEDULED">Scheduled</option>
              </select>
              <input
                data-testid="execution-filter-revision"
                value={revisionFilter}
                onChange={(event) => {
                  setListLoading(true);
                  setPage(0);
                  setRevisionFilter(event.target.value);
                }}
                className="min-w-0 rounded border border-border-muted bg-surface-lowest px-2 py-1.5 font-mono-sm text-body-sm text-primary placeholder:text-on-surface-variant outline-none focus:border-status-info focus:ring-1 focus:ring-status-info"
                type="number"
                min="1"
                step="1"
                placeholder="Revision"
                aria-label="Filter executions by revision"
              />
              <button
                type="button"
                data-testid="execution-clear-filters"
                onClick={() => {
                  setListLoading(true);
                  setPage(0);
                  setSearchInput('');
                  setSearch('');
                  setStatusFilter('');
                  setRevisionFilter('');
                  setTriggerFilter('');
                }}
                disabled={!hasExecutionFilters && !searchInput}
                className="flex items-center justify-center rounded border border-border-muted bg-surface-container-high text-on-surface-variant transition-colors hover:text-primary disabled:cursor-not-allowed disabled:opacity-40"
                aria-label="Clear execution filters"
                title="Clear filters"
              >
                <X size={15} />
              </button>
            </div>
          </div>

          <div className="grid grid-cols-[minmax(0,1fr)_42px_88px_98px] gap-2 border-b border-border-muted bg-surface-container-lowest px-4 py-2 font-label-caps text-label-caps uppercase text-on-surface-variant">
            <div>Execution</div>
            <div>Rev</div>
            <div>Status</div>
            <div className="text-right">Started</div>
          </div>

          <div className="min-h-0 flex-1 overflow-y-auto" data-testid="execution-list">
            {listLoading && !executionPage ? (
              <ExecutionListMessage title="Loading executions" message="Reading persisted workflow runs." />
            ) : listError ? (
              <ExecutionListMessage title="Could not load executions" message={listError} />
            ) : filteredExecutions.length === 0 ? (
              <ExecutionListMessage
                title={hasExecutionFilters ? 'No matching executions' : 'No executions yet'}
                message={hasExecutionFilters ? 'Change or clear the execution filters.' : 'Trigger the active revision to create the first run.'}
              />
            ) : (
              filteredExecutions.map((execution) => {
                const selected = execution.id === selectedExecutionId;
                return (
                  <button
                    type="button"
                    key={execution.id}
                    data-testid={`execution-row-${execution.id}`}
                    onClick={() => {
                      setSelectedExecutionId(execution.id);
                      setDetail(null);
                      setDetailError(null);
                      setDetailLoading(true);
                    }}
                    className={`grid w-full grid-cols-[minmax(0,1fr)_42px_88px_98px] gap-2 border-b border-border-muted px-4 py-2.5 text-left transition-colors hover:bg-surface-container-low ${selected ? 'border-l-2 border-l-status-info bg-surface-container-high' : 'border-l-2 border-l-transparent bg-surface-lowest'}`}
                  >
                    <div className="min-w-0">
                      <div className="flex items-center gap-2 truncate font-mono-sm text-mono-sm text-primary">
                        <span className={`material-symbols-outlined text-[14px] ${isActiveExecution(execution.status) ? 'animate-spin text-status-info' : statusClasses(execution.status).split(' ').at(-1)}`}>
                          {statusIcon(execution.status)}
                        </span>
                        Run {execution.runNumber}
                      </div>
                      <div
                        data-testid={`execution-trigger-kind-${execution.id}`}
                        className="truncate font-mono-sm text-[10px] text-on-surface-variant"
                      >
                        {execution.scheduledFor ? `Scheduled · ${execution.id}` : `Manual · ${execution.id}`}
                      </div>
                    </div>
                    <div className="font-mono-sm text-mono-sm text-on-surface-variant">{execution.definitionRevision}</div>
                    <div>
                      <StatusBadge status={execution.status} testId={`execution-status-${execution.id}`} />
                    </div>
                    <div className="text-right font-mono-sm text-[11px] text-on-surface-variant">
                      {formatDateTime(execution.startedAt || execution.createdAt)}
                    </div>
                  </button>
                );
              })
            )}
          </div>

          <div className="flex shrink-0 items-center justify-between border-t border-border-subtle bg-surface-container-lowest p-2 font-body-sm text-body-sm text-on-surface-variant">
            <span>
              {executionPage?.totalElements
                ? `Showing ${showingStart}-${showingEnd} of ${executionPage.totalElements}${hasExecutionFilters ? ' matching' : ''}`
                : hasExecutionFilters ? '0 matching executions' : 'No executions'}
            </span>
            <div className="flex gap-1">
              <button
                type="button"
                onClick={() => {
                  setListLoading(true);
                  setPage((current) => Math.max(0, current - 1));
                }}
                disabled={!executionPage || executionPage.first || listLoading}
                className="rounded p-1 hover:bg-surface-container-low disabled:opacity-40"
                aria-label="Previous execution page"
              >
                <ChevronLeft size={16} />
              </button>
              <button
                type="button"
                onClick={() => {
                  setListLoading(true);
                  setPage((current) => current + 1);
                }}
                disabled={!executionPage || executionPage.last || listLoading}
                className="rounded p-1 hover:bg-surface-container-low disabled:opacity-40"
                aria-label="Next execution page"
              >
                <ChevronRight size={16} />
              </button>
            </div>
          </div>
        </section>

        <section className="flex min-w-0 flex-1 flex-col overflow-hidden bg-surface">
          {!selectedExecutionId ? (
            <ExecutionListMessage title="No execution selected" message="Trigger a run or select one from the list." />
          ) : detailLoading && !selectedDetail ? (
            <ExecutionListMessage title="Loading execution trace" message="Reading scopes, states, and attempts." />
          ) : detailError ? (
            <ExecutionListMessage title="Could not load execution trace" message={detailError} />
          ) : selectedDetail ? (
            <>
              <div className="flex shrink-0 flex-col gap-3 border-b border-border-subtle bg-surface-elevated p-gutter">
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0">
                    <div className="mb-1 font-label-caps text-label-caps uppercase tracking-widest text-on-surface-variant">Execution trace</div>
                    <div className="flex flex-wrap items-center gap-2">
                      <h2 className="truncate font-mono-sm text-lg text-primary" data-testid="execution-selected-id">
                        Run {selectedDetail.execution.runNumber}
                      </h2>
                      <StatusBadge status={selectedDetail.execution.status} testId="execution-selected-status" />
                      <span className="rounded border border-border-muted bg-surface-container-high px-2 py-0.5 font-mono-sm text-[11px] text-on-surface-variant">
                        Rev {selectedDetail.execution.definitionRevision}
                      </span>
                    </div>
                    <div className="mt-1 truncate font-mono-sm text-[11px] text-on-surface-variant">{selectedDetail.execution.id}</div>
                  </div>
                  <div className="flex gap-2">
                    <button
                      type="button"
                      onClick={() => void handleCopyExecutionId()}
                      className="rounded border border-border-muted bg-surface-container-high p-1.5 text-on-surface transition-colors hover:bg-surface-container-highest"
                      aria-label="Copy execution ID"
                      title={copied ? 'Copied' : 'Copy execution ID'}
                    >
                      <Copy size={16} />
                    </button>
                    <button
                      type="button"
                      onClick={() => setShowRawDetail((current) => !current)}
                      className={`rounded border p-1.5 transition-colors ${showRawDetail ? 'border-status-info bg-surface-container-highest text-status-info' : 'border-border-muted bg-surface-container-high text-on-surface hover:bg-surface-container-highest'}`}
                      aria-label="View raw execution JSON"
                      aria-pressed={showRawDetail}
                    >
                      <Braces size={16} />
                    </button>
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-3 text-body-sm font-body-sm lg:grid-cols-5">
                  <Metric label="Started" value={formatDateTime(selectedDetail.execution.startedAt || selectedDetail.execution.createdAt)} />
                  <Metric label="Duration" value={formatDuration(selectedDetail.execution.startedAt, selectedDetail.execution.completedAt)} />
                  <Metric
                    label="Trigger"
                    value={selectedDetail.execution.scheduledFor ? `Scheduled ${formatDateTime(selectedDetail.execution.scheduledFor)}` : 'Manual'}
                  />
                  <Metric label="Scopes" value={String(selectedDetail.scopes.length)} />
                  <Metric
                    label="States"
                    value={String(selectedDetail.scopes.reduce((count, scope) => count + scope.stateExecutions.length, 0))}
                  />
                </div>
              </div>

              <div className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto p-gutter">
                {selectedDetail.execution.error && (
                  <div className="rounded border border-status-error bg-surface-container-high p-3 text-body-sm">
                    <div className="flex items-center gap-2 font-medium text-status-error">
                      <AlertTriangle size={16} />
                      {selectedDetail.execution.error}
                    </div>
                    {selectedDetail.execution.cause && <p className="mt-1 text-on-surface">{selectedDetail.execution.cause}</p>}
                  </div>
                )}

                {(selectedDetail.execution.status === 'FAILED'
                  || selectedDetail.execution.status === 'TIMED_OUT') && (
                  <TriagePanel
                    workflowId={workflow.id}
                    executionId={selectedDetail.execution.id}
                    editRevision={selectedDetail.execution.definitionRevision
                      ?? workflow.activeDefinition?.revision ?? null}
                    onNavigate={onNavigate}
                  />
                )}

                {showRawDetail ? (
                  <JsonPanel title="Raw execution detail" value={selectedDetail} open testId="execution-raw-json" />
                ) : (
                  <>
                    <div className="rounded border border-border-subtle bg-surface-elevated">
                      <div className="flex items-center gap-2 border-b border-border-subtle bg-surface-container-low px-4 py-2 font-headline-md text-body-sm font-medium text-primary">
                        <span className="material-symbols-outlined text-[16px]">account_tree</span>
                        Persisted state trace
                      </div>
                      <div className="flex flex-col gap-3 p-3">
                        {selectedDetail.scopes.length === 0 ? (
                          <p className="p-3 text-body-sm text-on-surface-variant">No execution scopes were persisted.</p>
                        ) : selectedDetail.scopes.map((scope) => (
                          <div key={scope.id} className="rounded border border-border-muted bg-surface-lowest p-3">
                            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                              <div>
                                <div className="font-body-sm text-body-sm font-medium text-primary">{scopeLabel(scope)}</div>
                                <div className="font-mono-sm text-[10px] text-on-surface-variant">{scope.scopePath}</div>
                              </div>
                              <StatusBadge status={scope.status} />
                            </div>
                            <div className="flex flex-col gap-2">
                              {scope.stateExecutions.length === 0 ? (
                                <p className="rounded border border-border-muted bg-surface-container-low p-3 text-body-sm text-on-surface-variant">
                                  No states have executed in this scope yet.
                                </p>
                              ) : scope.stateExecutions.map((state) => (
                                <StateExecutionCard key={state.id} state={state} />
                              ))}
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>

                    <JsonPanel title="Input payload" value={selectedDetail.execution.input} open testId="execution-input-json-view" />
                    <JsonPanel title="Output result" value={selectedDetail.execution.output} testId="execution-output-json" />
                  </>
                )}
              </div>
            </>
          ) : null}
        </section>
      </div>

      {triggerDialogOpen && (
        <div className="fixed inset-0 z-[80] flex items-center justify-center bg-background/80 p-6 backdrop-blur-sm">
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="trigger-run-title"
            data-testid="execution-trigger-dialog"
            className="w-full max-w-2xl rounded border border-border-subtle bg-surface-elevated shadow-2xl"
          >
            <div className="flex items-start justify-between border-b border-border-subtle px-5 py-4">
              <div>
                <h2 id="trigger-run-title" className="font-headline-lg text-headline-lg text-primary">Trigger workflow run</h2>
                <p className="mt-1 text-body-sm text-on-surface-variant">
                  This run will use active revision {activeRevision ? `Rev ${activeRevision}` : '—'}.
                </p>
              </div>
              <button
                type="button"
                onClick={() => setTriggerDialogOpen(false)}
                disabled={triggerBusy}
                className="rounded p-1 text-on-surface-variant hover:bg-surface-container-high hover:text-primary disabled:opacity-50"
                aria-label="Close trigger run dialog"
              >
                <X size={18} />
              </button>
            </div>
            <div className="p-5">
              <label htmlFor="execution-input" className="mb-2 block font-label-caps text-label-caps uppercase tracking-wider text-on-surface-variant">
                Workflow input JSON
              </label>
              <textarea
                id="execution-input"
                data-testid="execution-input-json"
                value={executionInput}
                onChange={(event) => {
                  setExecutionInput(event.target.value);
                  setTriggerError(null);
                }}
                rows={12}
                spellCheck={false}
                className="w-full resize-y rounded border border-border-muted bg-surface-lowest p-3 font-mono-sm text-mono-sm text-primary outline-none focus:border-status-info focus:ring-1 focus:ring-status-info"
              />
              {triggerError && (
                <div className="mt-3 rounded border border-status-error bg-surface-container-high px-3 py-2 text-body-sm text-status-error" role="alert">
                  {triggerError}
                </div>
              )}
            </div>
            <div className="flex justify-end gap-2 border-t border-border-subtle px-5 py-3">
              <button
                type="button"
                onClick={() => setTriggerDialogOpen(false)}
                disabled={triggerBusy}
                className="rounded border border-border-muted bg-surface-container-high px-4 py-2 text-body-sm text-on-surface hover:bg-surface-container-highest disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                type="button"
                data-testid="execution-submit-run"
                onClick={() => void handleTrigger()}
                disabled={triggerBusy}
                className="flex items-center gap-2 rounded border border-primary bg-primary px-4 py-2 text-body-sm font-medium text-on-primary hover:bg-primary-container disabled:opacity-50"
              >
                <Play size={15} fill="currentColor" />
                {triggerBusy ? 'Starting…' : 'Start run'}
              </button>
            </div>
          </div>
        </div>
      )}

      {cancelDialogOpen && selectedSummary && (
        <div className="fixed inset-0 z-[80] flex items-center justify-center bg-background/80 p-6 backdrop-blur-sm">
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="cancel-execution-title"
            data-testid="execution-cancel-dialog"
            className="w-full max-w-lg rounded border border-border-subtle bg-surface-elevated shadow-2xl"
          >
            <div className="flex items-start gap-3 border-b border-border-subtle px-5 py-4">
              <div className="rounded border border-status-warning bg-surface-container-high p-2 text-status-warning">
                <AlertTriangle size={18} />
              </div>
              <div>
                <h2 id="cancel-execution-title" className="font-headline-lg text-headline-lg text-primary">Cancel execution?</h2>
                <p className="mt-1 font-mono-sm text-[11px] text-on-surface-variant">{selectedSummary.id}</p>
              </div>
            </div>
            <div className="space-y-3 p-5 text-body-sm text-on-surface">
              <p>The execution, active scopes, states, and attempts will be marked as canceled and cannot advance further.</p>
              <p className="rounded border border-status-warning bg-surface-container-high p-3 text-status-warning">
                Cancellation is cooperative. An external Task that has already started may still finish, and its side effects cannot be rolled back.
              </p>
              {cancelError && (
                <div className="rounded border border-status-error bg-surface-container-high px-3 py-2 text-status-error" role="alert">
                  {cancelError}
                </div>
              )}
            </div>
            <div className="flex justify-end gap-2 border-t border-border-subtle px-5 py-3">
              <button
                type="button"
                onClick={() => setCancelDialogOpen(false)}
                disabled={cancelBusy}
                className="rounded border border-border-muted bg-surface-container-high px-4 py-2 text-body-sm text-on-surface hover:bg-surface-container-highest disabled:opacity-50"
              >
                Keep running
              </button>
              <button
                type="button"
                data-testid="execution-confirm-cancel"
                onClick={() => void handleCancel()}
                disabled={cancelBusy}
                className="flex items-center gap-2 rounded border border-status-error bg-surface-container-high px-4 py-2 text-body-sm font-medium text-status-error hover:bg-surface-container-highest disabled:opacity-50"
              >
                <Square size={15} />
                {cancelBusy ? 'Canceling…' : 'Cancel execution'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function ExecutionListMessage({ title, message }: { title: string; message: string }) {
  return (
    <div className="flex h-full min-h-40 flex-col items-center justify-center p-8 text-center">
      <div className="font-headline-md text-primary">{title}</div>
      <p className="mt-1 max-w-md text-body-sm text-on-surface-variant">{message}</p>
    </div>
  );
}

function StatusBadge({
  status,
  testId,
}: {
  status: WorkflowExecutionStatusDTO | WorkflowRuntimeStatusDTO;
  testId?: string;
}) {
  return (
    <span
      data-testid={testId}
      className={`inline-flex items-center gap-1 rounded border px-2 py-0.5 font-body-sm text-[10px] font-medium leading-none ${statusClasses(status)}`}
    >
      <span className={`material-symbols-outlined text-[12px] ${ACTIVE_RUNTIME_STATUSES.has(status) ? 'animate-spin' : ''}`}>
        {statusIcon(status)}
      </span>
      {statusLabel(status)}
    </span>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded border border-border-muted bg-surface-container-low px-3 py-2">
      <div className="font-label-caps text-[10px] uppercase tracking-wider text-on-surface-variant">{label}</div>
      <div className="mt-0.5 truncate font-mono-sm text-mono-sm text-primary">{value}</div>
    </div>
  );
}

function StateExecutionCard({ state }: { state: WorkflowStateExecutionDTO }) {
  return (
    <details
      className="group rounded border border-border-muted bg-surface-container-low"
      data-testid={`execution-state-${state.id}`}
      data-state-name={state.stateName}
    >
      <summary className="flex cursor-pointer list-none items-center justify-between gap-3 px-3 py-2.5 hover:bg-surface-container-high">
        <div className="flex min-w-0 items-center gap-2">
          <span className="material-symbols-outlined text-[15px] text-on-surface-variant transition-transform group-open:rotate-90">chevron_right</span>
          <span className="truncate font-body-sm text-body-sm font-medium text-primary">{state.stateName}</span>
          <span className="rounded border border-border-muted bg-surface-lowest px-1.5 py-0.5 font-label-caps text-[9px] text-on-surface-variant">
            {state.stateType}
          </span>
        </div>
        <div className="flex shrink-0 items-center gap-3">
          <span className="font-mono-sm text-[10px] text-on-surface-variant">
            {formatDuration(state.startedAt, state.completedAt)}
          </span>
          <StatusBadge status={state.status} />
        </div>
      </summary>
      <div className="space-y-3 border-t border-border-muted bg-surface-lowest p-3">
        {state.resource && (
          <div className="font-mono-sm text-[11px] text-on-surface-variant">
            Resource: <span className="text-primary">{state.resource}</span>
          </div>
        )}
        {state.error && (
          <div className="rounded border border-status-error bg-surface-container-high p-2 text-body-sm text-status-error">
            <strong>{state.error}</strong>{state.cause ? ` — ${state.cause}` : ''}
          </div>
        )}
        <div className="grid gap-3 xl:grid-cols-2">
          <JsonBlock label="State input" value={state.input} />
          <JsonBlock label="State output" value={state.output} />
        </div>
        {state.attempts.length > 0 && (
          <div>
            <div className="mb-2 font-label-caps text-[10px] uppercase tracking-wider text-on-surface-variant">
              Task attempts
            </div>
            <div className="space-y-2">
              {state.attempts.map((attempt) => <AttemptCard key={attempt.id} attempt={attempt} />)}
            </div>
          </div>
        )}
      </div>
    </details>
  );
}

function AttemptCard({ attempt }: { attempt: WorkflowStateExecutionAttemptDTO }) {
  return (
    <details className="rounded border border-border-muted bg-surface-container-low">
      <summary className="flex cursor-pointer list-none items-center justify-between gap-3 px-3 py-2">
        <span className="font-body-sm text-body-sm text-primary">Attempt {attempt.attemptNumber}</span>
        <div className="flex items-center gap-3">
          <span className="font-mono-sm text-[10px] text-on-surface-variant">
            {attempt.durationMs == null ? '—' : `${attempt.durationMs}ms`}
          </span>
          <StatusBadge status={attempt.status} />
        </div>
      </summary>
      <div className="grid gap-3 border-t border-border-muted bg-surface-lowest p-3 xl:grid-cols-2">
        <JsonBlock label="Arguments" value={attempt.arguments} />
        <JsonBlock label="Result" value={attempt.result} />
        {(attempt.error || attempt.lastDispatchError) && (
          <div className="xl:col-span-2 rounded border border-status-error bg-surface-container-high p-2 text-body-sm text-status-error">
            {attempt.error || attempt.lastDispatchError}{attempt.cause ? ` — ${attempt.cause}` : ''}
          </div>
        )}
      </div>
    </details>
  );
}

function JsonBlock({ label, value }: { label: string; value: unknown }) {
  return (
    <div className="min-w-0 rounded border border-border-muted bg-surface-container-low p-2">
      <div className="mb-1 font-label-caps text-[9px] uppercase tracking-wider text-on-surface-variant">{label}</div>
      <pre className="max-h-48 overflow-auto whitespace-pre-wrap break-words font-mono-sm text-[11px] text-primary">
        <code>{formatJson(value)}</code>
      </pre>
    </div>
  );
}

function JsonPanel({
  title,
  value,
  open = false,
  testId,
}: {
  title: string;
  value: unknown;
  open?: boolean;
  testId?: string;
}) {
  return (
    <details className="group overflow-hidden rounded border border-border-subtle bg-surface-elevated" open={open}>
      <summary className="flex cursor-pointer select-none items-center justify-between border-b border-border-subtle bg-surface-container-low px-4 py-2.5 transition-colors hover:bg-surface-container-high">
        <div className="flex items-center gap-2 font-headline-md text-body-sm font-medium text-primary">
          <span className="material-symbols-outlined text-[16px] transition-transform group-open:rotate-90">chevron_right</span>
          {title}
        </div>
        <span className="rounded border border-border-muted bg-surface-lowest px-2 py-0.5 font-label-caps text-label-caps text-on-surface-variant">JSON</span>
      </summary>
      <div className="bg-surface-lowest p-4">
        <pre className="overflow-x-auto whitespace-pre-wrap break-words font-mono-sm text-mono-sm text-on-surface" data-testid={testId}>
          <code>{formatJson(value)}</code>
        </pre>
      </div>
    </details>
  );
}
