import { X } from 'lucide-react';

type Props = {
  definition: any;
  selectedStateName?: string;
};

const stateIconMap: Record<string, string> = {
  Task: 'database',
  Choice: 'call_split',
  Pass: 'swap_horiz',
  Wait: 'schedule',
  Succeed: 'check_circle',
  Fail: 'cancel',
  Map: 'layers',
  Parallel: 'splitscreen',
};

const stateAccentMap: Record<string, string> = {
  Task: 'bg-status-info text-status-info',
  Choice: 'bg-status-warning text-status-warning',
  Pass: 'bg-on-surface-variant text-on-surface-variant',
  Wait: 'bg-status-accent text-status-accent',
  Succeed: 'bg-status-success text-status-success',
  Fail: 'bg-status-error text-status-error',
  Map: 'bg-status-info text-status-info',
  Parallel: 'bg-status-accent text-status-accent',
};

function getSelectedState(definition: any, selectedStateName?: string) {
  if (!definition?.States) return { name: undefined, state: undefined };
  const fallbackName = selectedStateName || definition.StartAt || Object.keys(definition.States)[0];
  return { name: fallbackName, state: definition.States[fallbackName] };
}

function formatJson(value: unknown) {
  return JSON.stringify(value, null, 2);
}

export function NodeDetailsPanel({ definition, selectedStateName }: Props) {
  const { name, state } = getSelectedState(definition, selectedStateName);
  const type = state?.Type || 'State';
  const accent = stateAccentMap[type] || 'bg-status-info text-status-info';
  const [barClass, textClass] = accent.split(' ');
  const icon = stateIconMap[type] || 'settings';
  const resource = state?.Resource || state?.Next || state?.Default || type;
  const timeout = state?.TimeoutSeconds ? `${state.TimeoutSeconds} seconds` : '300 seconds';
  const retry = state?.Retry?.[0] || {};
  const payloadPreview = state?.Parameters || state?.Input || {
    source_bucket: 'data-raw-us-east',
    prefix: 'batch_2023-10-24',
    limit: 5000,
    schema_version: 'v2.1',
  };

  return (
    <>
      <div className="px-5 py-4 border-b border-border-subtle flex items-center justify-between bg-surface-elevated/50 backdrop-blur-md">
        <h2 className="text-headline-md font-headline-md font-medium text-primary">Node Details</h2>
        <button className="text-on-surface-variant hover:text-primary transition-colors" aria-label="Close node details">
          <X size={20} />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto p-5 space-y-5">
        <div className="bg-surface-elevated border border-border-subtle rounded-lg p-4 relative overflow-hidden">
          <div className={`absolute top-0 left-0 w-1 h-full ${barClass}`}></div>
          <div className="flex items-start justify-between mb-2 pl-2">
            <div>
              <div className={`text-label-caps font-label-caps ${textClass} mb-1`}>{type.toUpperCase()} STATE</div>
              <div className="text-headline-md font-headline-md font-medium text-primary">{name || 'No node selected'}</div>
            </div>
            <span className="material-symbols-outlined text-on-surface-variant">{icon}</span>
          </div>
          <div className="pl-2 mt-4">
            <p className="text-body-sm font-body-sm text-on-surface-variant leading-relaxed">
              {type === 'Choice'
                ? 'Routes execution through the first matching branch and falls back to the default branch when no condition matches.'
                : 'Executes this workflow state and forwards its result to the next scheduled transition.'}
            </p>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div className="bg-surface-container-low border border-border-subtle rounded-lg p-3">
            <div className="text-label-caps font-label-caps text-on-surface-variant mb-1">RESOURCE</div>
            <div className="text-mono-sm font-mono-sm text-primary truncate" title={resource}>
              {resource}
            </div>
          </div>
          <div className="bg-surface-container-low border border-border-subtle rounded-lg p-3">
            <div className="text-label-caps font-label-caps text-on-surface-variant mb-1">TIMEOUT</div>
            <div className="text-mono-sm font-mono-sm text-primary">{timeout}</div>
          </div>
        </div>

        <div className="space-y-3">
          <h3 className="text-label-caps font-label-caps text-on-surface-variant">INPUT PAYLOAD (PREVIEW)</h3>
          <div className="bg-surface-lowest border border-border-subtle rounded-lg p-3 font-mono-sm text-mono-sm text-on-surface-variant overflow-x-auto relative group">
            <button className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity bg-surface-elevated p-1 rounded border border-border-subtle hover:text-primary" aria-label="Copy preview">
              <span className="material-symbols-outlined text-[14px]">content_copy</span>
            </button>
            <pre className="text-[11px] leading-relaxed">
              <code>{formatJson(payloadPreview)}</code>
            </pre>
          </div>
        </div>

        <div className="space-y-3">
          <h3 className="text-label-caps font-label-caps text-on-surface-variant">RETRY CONFIGURATION</h3>
          <div className="bg-surface-container-low border border-border-subtle rounded-lg divide-y divide-border-subtle">
            <div className="flex justify-between items-center p-3">
              <span className="text-body-sm font-body-sm text-on-surface-variant">Max Attempts</span>
              <span className="text-mono-sm font-mono-sm text-primary bg-surface-container px-2 py-0.5 rounded">{retry.MaxAttempts ?? 3}</span>
            </div>
            <div className="flex justify-between items-center p-3">
              <span className="text-body-sm font-body-sm text-on-surface-variant">Interval (s)</span>
              <span className="text-mono-sm font-mono-sm text-primary bg-surface-container px-2 py-0.5 rounded">{retry.IntervalSeconds ?? 2}</span>
            </div>
            <div className="flex justify-between items-center p-3">
              <span className="text-body-sm font-body-sm text-on-surface-variant">Backoff Rate</span>
              <span className="text-mono-sm font-mono-sm text-primary bg-surface-container px-2 py-0.5 rounded">{retry.BackoffRate ?? '2.0'}</span>
            </div>
          </div>
        </div>
      </div>
    </>
  );
}
