import { ChevronLeft, ChevronRight, Copy, Filter, Play, Search, Square, Braces } from 'lucide-react';

type ExecutionStatus = 'Completed' | 'Failed' | 'Running';

export type ExecutionRun = {
  id: string;
  status: ExecutionStatus;
  duration: string;
  started: string;
};

const defaultExecutions: ExecutionRun[] = [
  { id: 'ex-9a8b7c-20231024', status: 'Completed', duration: '14.2s', started: '10:42:01 AM' },
  { id: 'ex-1d2e3f-20231024', status: 'Failed', duration: '2.1s', started: '10:40:15 AM' },
  { id: 'ex-4g5h6i-20231024', status: 'Running', duration: '45.0s', started: '10:39:50 AM' },
  { id: 'ex-7j8k9l-20231024', status: 'Completed', duration: '112.5s', started: '10:35:12 AM' },
];

const traceItems = [
  { name: 'FetchSourceData', kind: 'Activity', duration: '2.8s' },
  { name: 'ProcessData', kind: 'Workflow', duration: '8.5s', children: ['CleanTextActivity', 'ExtractEntitiesActivity'] },
  { name: 'ValidateOutput', kind: 'Activity', duration: '2.1s' },
  { name: 'SaveResults', kind: 'Activity', duration: '0.8s' },
];

function statusClasses(status: ExecutionStatus) {
  if (status === 'Failed') return 'bg-status-error/10 text-status-error border-status-error/20';
  if (status === 'Running') return 'bg-status-info/10 text-status-info border-status-info/20';
  return 'bg-status-success/10 text-status-success border-status-success/20';
}

function statusIcon(status: ExecutionStatus) {
  if (status === 'Failed') return 'cancel';
  if (status === 'Running') return 'progress_activity';
  return 'check_circle';
}

export function ExecutionStatusView({
  workflowName = 'Workflow',
  revisionLabel = 'Rev 3',
  runs = defaultExecutions,
}: {
  workflowName?: string;
  revisionLabel?: string;
  runs?: ExecutionRun[];
}) {
  const executions = runs;
  const selectedExecution = executions[0];

  return (
    <div className="flex h-full min-h-0 flex-col bg-surface">
      <div className="h-14 shrink-0 border-b border-border-subtle bg-surface-elevated px-gutter flex items-center justify-between">
        <div className="flex items-center gap-4">
          <h1 className="text-headline-lg font-headline-lg text-primary">{workflowName}</h1>
          <div className="h-4 w-px bg-border-muted" />
          <div className="font-mono-sm text-mono-sm text-on-surface-variant">{revisionLabel}</div>
          <div className="h-4 w-px bg-border-muted" />
          <div className="flex items-center gap-element-gap-md text-body-sm font-body-sm">
            <div className="flex items-center gap-2">
              <span className="h-2 w-2 rounded-full bg-status-info animate-pulse" />
              <span className="text-on-surface-variant">Active:</span>
              <span className="text-primary font-mono-sm text-mono-sm">14</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="h-2 w-2 rounded-full bg-status-success" />
              <span className="text-on-surface-variant">Success (24h):</span>
              <span className="text-primary font-mono-sm text-mono-sm">99.8%</span>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <button className="px-3 py-1.5 rounded border border-border-muted text-on-surface hover:bg-surface-container-low font-body-sm text-body-sm flex items-center gap-2 transition-colors">
            <Square size={16} />
            Cancel Selected
          </button>
          <button className="px-3 py-1.5 rounded bg-primary text-on-primary font-body-sm text-body-sm font-medium hover:bg-primary-container flex items-center gap-2 transition-colors">
            <Play size={16} fill="currentColor" />
            Trigger Run
          </button>
        </div>
      </div>

      <div className="flex-1 min-h-0 flex overflow-hidden">
        <section className="w-[45%] min-w-[420px] border-r border-border-subtle flex flex-col bg-surface-lowest">
          <div className="p-2 border-b border-border-muted bg-surface flex items-center gap-2 shrink-0">
            <div className="relative flex-1">
              <Search size={16} className="absolute left-2 top-1/2 -translate-y-1/2 text-on-surface-variant" />
              <input
                className="w-full bg-surface-lowest border border-border-muted rounded py-1 pl-8 pr-2 text-body-sm font-body-sm text-primary placeholder-on-surface-variant focus:outline-none focus:border-status-info focus:ring-1 focus:ring-status-info transition-all"
                placeholder="Filter by Workflow ID or Status..."
                type="text"
              />
            </div>
            <button className="p-1.5 rounded hover:bg-surface-container-low text-on-surface-variant border border-border-muted" aria-label="Filter executions">
              <Filter size={18} />
            </button>
          </div>

          <div className="grid grid-cols-[1fr_100px_80px_120px] gap-2 px-4 py-2 border-b border-border-muted bg-surface-container-lowest text-label-caps font-label-caps text-on-surface-variant uppercase">
            <div>Workflow ID</div>
            <div>Status</div>
            <div className="text-right">Duration</div>
            <div className="text-right">Started</div>
          </div>

          <div className="flex-1 overflow-y-auto">
            {executions.map((execution, index) => (
              <button
                key={execution.id}
                className={`grid w-full grid-cols-[1fr_100px_80px_120px] gap-2 px-4 py-2.5 border-b border-border-muted text-left cursor-pointer hover:bg-surface-container-low transition-colors ${index === 0 ? 'bg-surface-container-high border-l-2 border-l-status-info' : ''}`}
              >
                <div className="font-mono-sm text-mono-sm text-primary truncate flex items-center gap-2">
                  <span className={`material-symbols-outlined text-[14px] ${execution.status === 'Failed' ? 'text-status-error' : execution.status === 'Running' ? 'text-status-info animate-spin' : 'text-status-success'}`}>
                    {statusIcon(execution.status)}
                  </span>
                  {execution.id}
                </div>
                <div>
                  <span className={`inline-flex items-center px-2 py-0.5 rounded-full border font-body-sm text-[11px] font-medium leading-none ${statusClasses(execution.status)}`}>
                    {execution.status}
                  </span>
                </div>
                <div className="font-mono-sm text-mono-sm text-on-surface-variant text-right">{execution.duration}</div>
                <div className="font-mono-sm text-mono-sm text-on-surface-variant text-right">{execution.started}</div>
              </button>
            ))}
          </div>

          <div className="p-2 border-t border-border-subtle bg-surface-container-lowest flex items-center justify-between text-body-sm font-body-sm text-on-surface-variant">
            <span>Showing 1-4 of 1,204</span>
            <div className="flex gap-1">
              <button className="p-1 rounded hover:bg-surface-container-low" aria-label="Previous page"><ChevronLeft size={16} /></button>
              <button className="p-1 rounded hover:bg-surface-container-low" aria-label="Next page"><ChevronRight size={16} /></button>
            </div>
          </div>
        </section>

        <section className="flex-1 min-w-0 flex flex-col bg-surface overflow-hidden">
          <div className="p-gutter border-b border-border-subtle bg-surface-elevated shrink-0 flex flex-col gap-4">
            <div className="flex items-center justify-between">
              <div>
                <div className="text-label-caps font-label-caps text-on-surface-variant mb-1 uppercase tracking-widest">Execution Trace</div>
                <h2 className="font-mono-sm text-mono-sm text-primary text-lg">{selectedExecution?.id || 'No executions for revision'}</h2>
              </div>
              <div className="flex gap-2">
                <button className="p-1.5 rounded border border-border-muted text-on-surface hover:bg-surface-container-low" aria-label="Copy execution ID">
                  <Copy size={16} />
                </button>
                <button className="p-1.5 rounded border border-border-muted text-on-surface hover:bg-surface-container-low" aria-label="View source JSON">
                  <Braces size={16} />
                </button>
              </div>
            </div>

            <div className="flex flex-col gap-1.5">
              <div className="flex justify-between text-label-caps font-label-caps text-on-surface-variant">
                <span>0s</span>
                <span>Total Duration: {selectedExecution?.duration || '-'}</span>
              </div>
              <div className="h-2 w-full bg-surface-container-highest rounded-full overflow-hidden flex">
                <div className="h-full bg-status-success opacity-80 w-[20%]" title="Fetch Source Data: 2.8s" />
                <div className="h-full bg-status-info opacity-80 w-[60%]" title="Process Data: 8.5s" />
                <div className="h-full bg-status-warning opacity-80 w-[15%]" title="Validate Output: 2.1s" />
                <div className="h-full bg-status-success opacity-80 w-[5%]" title="Save Results: 0.8s" />
              </div>
            </div>
          </div>

          <div className="flex-1 overflow-y-auto p-gutter flex flex-col gap-6">
            <div className="border border-border-subtle rounded bg-surface-elevated">
              <div className="px-4 py-2 border-b border-border-subtle bg-surface-container-low text-body-sm font-headline-md text-primary font-medium flex items-center gap-2">
                <span className="material-symbols-outlined text-[16px]">account_tree</span>
                Execution Path
              </div>
              <div className="p-4 pl-8 relative">
                {traceItems.map((item, index) => (
                  <div key={item.name} className={`relative ${index < traceItems.length - 1 ? 'pb-4 tree-line' : ''}`}>
                    <div className="tree-item-marker" />
                    <div className="flex items-center justify-between group cursor-default">
                      <div className="flex items-center gap-2">
                        <span className="material-symbols-outlined text-[14px] text-status-success">check_circle</span>
                        <span className="font-body-sm text-body-sm text-primary font-medium">{item.name}</span>
                        <span className="text-label-caps font-label-caps px-1.5 py-0.5 rounded bg-surface-container-highest text-on-surface-variant border border-border-muted">{item.kind}</span>
                      </div>
                      <div className="font-mono-sm text-mono-sm text-on-surface-variant opacity-0 group-hover:opacity-100 transition-opacity">{item.duration}</div>
                    </div>

                    {item.children && (
                      <div className="pl-6 pt-3 relative">
                        {item.children.map((child, childIndex) => (
                          <div key={child} className={`relative ${childIndex < item.children!.length - 1 ? 'pb-3 tree-line' : 'pb-2 tree-line'}`}>
                            <div className="tree-item-marker" />
                            <div className="flex items-center gap-2">
                              <span className="material-symbols-outlined text-[14px] text-status-success">check_circle</span>
                              <span className="font-body-sm text-body-sm text-on-surface">{child}</span>
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </div>

            <JsonPanel title="Input Payload" open />
            <JsonPanel title="Output Result" />
          </div>
        </section>
      </div>
    </div>
  );
}

function JsonPanel({ title, open = false }: { title: string; open?: boolean }) {
  const payload = title === 'Input Payload'
    ? `{
  "datasetId": "ds_89231",
  "batchSize": 1000,
  "options": {
    "strictValidation": true,
    "fallbackRegion": "us-east-2"
  },
  "timestamp": "2023-10-24T10:42:01Z"
}`
    : `{
  "status": "success",
  "recordsProcessed": 1000,
  "errors": 0,
  "outputUri": "s3://project-alpha-results/ds_89231/20231024.json"
}`;

  return (
    <details className="group border border-border-subtle rounded bg-surface-elevated overflow-hidden" open={open}>
      <summary className="px-4 py-2.5 bg-surface-container-low hover:bg-surface-container-high cursor-pointer flex items-center justify-between select-none border-b border-border-subtle transition-colors">
        <div className="font-headline-md text-body-sm text-primary font-medium flex items-center gap-2">
          <span className="material-symbols-outlined text-[16px] group-open:rotate-90 transition-transform">chevron_right</span>
          {title}
        </div>
        <span className="text-label-caps font-label-caps text-on-surface-variant bg-surface-lowest px-2 py-0.5 rounded border border-border-muted">JSON</span>
      </summary>
      <div className="p-4 bg-surface-lowest">
        <pre className="font-mono-sm text-mono-sm text-on-surface overflow-x-auto"><code>{payload}</code></pre>
      </div>
    </details>
  );
}
