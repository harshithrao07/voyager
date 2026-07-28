import { X } from 'lucide-react';
import { getStateVisual } from '../utils/stateVisuals';

type Props = {
  definition: any;
  selectedStateName?: string;
  onClose: () => void;
};

const RETRY_CAPABLE_TYPES = new Set(['Task', 'Parallel', 'Map']);

function getSelectedState(definition: any, selectedStateName?: string) {
  if (!definition?.States) return { name: undefined, state: undefined };
  const fallbackName = selectedStateName || definition.StartAt || Object.keys(definition.States)[0];
  return { name: fallbackName, state: definition.States[fallbackName] };
}

function hasValue(value: unknown) {
  return value !== undefined && value !== null;
}

function formatJson(value: unknown) {
  return JSON.stringify(value, null, 2);
}

function DetailSection({
  eyebrow,
  title,
  children,
}: {
  eyebrow: string;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section className="space-y-3">
      <div>
        <div className="font-mono-sm text-[9px] uppercase tracking-[0.12em] text-on-surface-variant">{eyebrow}</div>
        <h3 className="mt-1 text-body-sm font-medium text-on-surface">{title}</h3>
      </div>
      {children}
    </section>
  );
}

function ValueCard({ label, value, mono = true }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div className="rounded-DEFAULT border border-border-subtle bg-surface-container-low p-3">
      <div className="font-mono-sm text-[9px] uppercase tracking-[0.08em] text-on-surface-variant">{label}</div>
      <div className={`mt-1.5 break-words text-on-surface ${mono ? 'font-mono-sm text-[11px]' : 'text-body-sm'}`}>
        {value}
      </div>
    </div>
  );
}

function JsonValue({ label, value }: { label: string; value: unknown }) {
  if (!hasValue(value)) return null;

  return (
    <div>
      <div className="mb-1.5 font-mono-sm text-[9px] uppercase tracking-[0.08em] text-on-surface-variant">{label}</div>
      <pre className="overflow-x-auto rounded-DEFAULT border border-border-subtle bg-surface-lowest p-3 font-mono-sm text-[10px] leading-relaxed text-on-surface-variant">
        <code>{formatJson(value)}</code>
      </pre>
    </div>
  );
}

function ChoiceDetails({ state }: { state: any }) {
  const choices = Array.isArray(state?.Choices) ? state.Choices : [];

  return (
    <DetailSection eyebrow="Routing" title={`${choices.length} ordered ${choices.length === 1 ? 'condition' : 'conditions'}`}>
      <div className="space-y-2">
        {choices.map((choice: any, index: number) => (
          <div key={index} className="rounded-DEFAULT border border-border-subtle bg-surface-container-low p-3">
            <div className="flex items-center justify-between gap-3">
              <span className="font-mono-sm text-[9px] uppercase tracking-[0.08em] text-status-info">Condition {index + 1}</span>
              <span className="font-mono-sm text-[10px] text-on-surface">→ {choice?.Next || 'Missing target'}</span>
            </div>
            <code className="mt-2 block whitespace-pre-wrap break-words rounded bg-surface-lowest px-2.5 py-2 font-mono-sm text-[10px] leading-relaxed text-on-surface-variant">
              {typeof choice?.Condition === 'string' ? choice.Condition : formatJson(choice?.Condition)}
            </code>
          </div>
        ))}
        {typeof state?.Default === 'string' && (
          <ValueCard label="Default branch" value={`→ ${state.Default}`} />
        )}
      </div>
    </DetailSection>
  );
}

function RetryDetails({ state }: { state: any }) {
  const retriers = Array.isArray(state?.Retry) ? state.Retry : [];
  const catchers = Array.isArray(state?.Catch) ? state.Catch : [];

  return (
    <DetailSection eyebrow="Failure policy" title="Retry and catch">
      {retriers.length === 0 && catchers.length === 0 ? (
        <div className="rounded-DEFAULT border border-dashed border-border-subtle bg-surface-container-low/60 p-3 text-body-sm text-on-surface-variant">
          No retry or catch behavior is configured for this state.
        </div>
      ) : (
        <div className="space-y-3">
          {retriers.map((retrier: any, index: number) => (
            <div key={`retry-${index}`} className="rounded-DEFAULT border border-border-subtle bg-surface-container-low p-3">
              <div className="font-mono-sm text-[9px] uppercase tracking-[0.08em] text-status-warning">Retry {index + 1}</div>
              <div className="mt-2 font-mono-sm text-[10px] text-on-surface">
                {(Array.isArray(retrier?.ErrorEquals) ? retrier.ErrorEquals : []).join(', ') || 'No errors selected'}
              </div>
              <div className="mt-3 grid grid-cols-3 gap-2 text-center">
                <ValueCard label="Attempts" value={retrier?.MaxAttempts ?? '3 default'} />
                <ValueCard label="Interval" value={`${retrier?.IntervalSeconds ?? 1}s`} />
                <ValueCard label="Backoff" value={retrier?.BackoffRate ?? 2} />
              </div>
              {(hasValue(retrier?.MaxDelaySeconds) || hasValue(retrier?.JitterStrategy)) && (
                <div className="mt-2 grid grid-cols-2 gap-2">
                  {hasValue(retrier?.MaxDelaySeconds) && <ValueCard label="Max delay" value={`${retrier.MaxDelaySeconds}s`} />}
                  {hasValue(retrier?.JitterStrategy) && <ValueCard label="Jitter" value={retrier.JitterStrategy} />}
                </div>
              )}
            </div>
          ))}
          {catchers.map((catcher: any, index: number) => (
            <div key={`catch-${index}`} className="rounded-DEFAULT border border-border-subtle bg-surface-container-low p-3">
              <div className="flex items-center justify-between gap-3">
                <span className="font-mono-sm text-[9px] uppercase tracking-[0.08em] text-status-error">Catch {index + 1}</span>
                <span className="font-mono-sm text-[10px] text-on-surface">→ {catcher?.Next || 'Missing target'}</span>
              </div>
              <div className="mt-2 font-mono-sm text-[10px] text-on-surface-variant">
                {(Array.isArray(catcher?.ErrorEquals) ? catcher.ErrorEquals : []).join(', ') || 'No errors selected'}
              </div>
            </div>
          ))}
        </div>
      )}
    </DetailSection>
  );
}

function TypeSpecificDetails({ state, type }: { state: any; type: string }) {
  if (type === 'Task') {
    return (
      <DetailSection eyebrow="Task" title="Resource call">
        <div className="grid grid-cols-2 gap-2">
          <div className="col-span-2"><ValueCard label="Resource" value={state?.Resource || 'Not configured'} /></div>
          {hasValue(state?.TimeoutSeconds) && <ValueCard label="Timeout" value={`${state.TimeoutSeconds} seconds`} />}
          {hasValue(state?.HeartbeatSeconds) && <ValueCard label="Heartbeat" value={`${state.HeartbeatSeconds} seconds`} />}
        </div>
      </DetailSection>
    );
  }

  if (type === 'Wait') {
    const usesSeconds = hasValue(state?.Seconds);
    return (
      <DetailSection eyebrow="Wait" title="Resume condition">
        <ValueCard
          label={usesSeconds ? 'Duration' : 'Timestamp'}
          value={usesSeconds ? `${state.Seconds} seconds` : state?.Timestamp || 'Not configured'}
        />
      </DetailSection>
    );
  }

  if (type === 'Choice') return <ChoiceDetails state={state} />;

  if (type === 'Fail') {
    if (!hasValue(state?.Error) && !hasValue(state?.Cause)) return null;
    return (
      <DetailSection eyebrow="Outcome" title="Failure details">
        <div className="grid grid-cols-2 gap-2">
          {hasValue(state?.Error) && <ValueCard label="Error" value={state.Error} />}
          {hasValue(state?.Cause) && <ValueCard label="Cause" value={state.Cause} />}
        </div>
      </DetailSection>
    );
  }

  if (type === 'Parallel') {
    const branches = Array.isArray(state?.Branches) ? state.Branches : [];
    return (
      <DetailSection eyebrow="Parallel" title={`${branches.length} concurrent ${branches.length === 1 ? 'branch' : 'branches'}`}>
        <div className="space-y-2">
          {branches.map((branch: any, index: number) => (
            <ValueCard
              key={index}
              label={`Branch ${index + 1}`}
              value={`${branch?.StartAt || 'Missing start'} · ${Object.keys(branch?.States || {}).length} states`}
            />
          ))}
        </div>
      </DetailSection>
    );
  }

  if (type === 'Map') {
    const processor = state?.ItemProcessor;
    return (
      <DetailSection eyebrow="Map" title="Item processing">
        <div className="grid grid-cols-2 gap-2">
          <ValueCard label="Processor start" value={processor?.StartAt || 'Not configured'} />
          <ValueCard label="Processor states" value={Object.keys(processor?.States || {}).length} />
          {hasValue(state?.MaxConcurrency) && <ValueCard label="Concurrency" value={state.MaxConcurrency} />}
        </div>
        {hasValue(state?.Items) && <JsonValue label="Items" value={state.Items} />}
        {hasValue(state?.ItemSelector) && <JsonValue label="Item selector" value={state.ItemSelector} />}
      </DetailSection>
    );
  }

  return null;
}

export function StateDetailsPanel({ definition, selectedStateName, onClose }: Props) {
  const { name, state } = getSelectedState(definition, selectedStateName);
  const type = state?.Type || 'State';
  const visual = getStateVisual(type);
  const isStartState = Boolean(name && definition?.StartAt === name);
  const supportsRetry = RETRY_CAPABLE_TYPES.has(type);
  const hasFailurePolicy =
    (Array.isArray(state?.Retry) && state.Retry.length > 0)
    || (Array.isArray(state?.Catch) && state.Catch.length > 0);
  const hasDataFields = hasValue(state?.Arguments) || hasValue(state?.Assign) || hasValue(state?.Output);

  return (
    <>
      <div className="glass-shell flex items-center justify-between border-b border-border-subtle bg-surface-elevated/50 px-5 py-4 backdrop-blur-md">
        <div className="min-w-0">
          <div className="font-mono-sm text-[9px] uppercase tracking-[0.12em] text-on-surface-variant">State details</div>
          <h2 className="mt-0.5 truncate text-headline-md font-medium text-primary">{name || 'No state selected'}</h2>
        </div>
        <button
          type="button"
          onClick={onClose}
          className="text-on-surface-variant transition-colors hover:text-primary"
          aria-label="Close state details"
          title="Close state details"
        >
          <X size={20} />
        </button>
      </div>

      <div className="flex-1 space-y-6 overflow-y-auto p-5">
        <section className="relative overflow-hidden rounded-lg border border-border-subtle bg-surface-elevated p-4">
          <div className={`absolute bottom-0 left-0 top-0 w-1 ${visual.barClass}`} />
          <div className="flex items-start justify-between gap-4 pl-2">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <span className={`font-mono-sm text-[9px] uppercase tracking-[0.1em] ${visual.textClass}`}>{visual.label}</span>
                {isStartState && (
                  <span className="rounded-full border border-status-info/30 bg-status-info/10 px-2 py-0.5 font-mono-sm text-[8px] uppercase tracking-[0.08em] text-status-info">Start</span>
                )}
              </div>
            </div>
            <span className={`material-symbols-outlined text-[22px] ${visual.textClass}`}>{visual.iconName}</span>
          </div>
        </section>

        {state && (
          <>
            {hasValue(state.Comment) && (
              <DetailSection eyebrow="Comment" title="Author note">
                <div className="rounded-DEFAULT border border-border-subtle bg-surface-container-low p-3 text-body-sm text-on-surface-variant">
                  {state.Comment}
                </div>
              </DetailSection>
            )}

            <TypeSpecificDetails state={state} type={type} />

            {hasDataFields && (
              <DetailSection eyebrow="Configuration" title="Data transformation">
                <div className="space-y-3">
                  <JsonValue label="Arguments" value={state.Arguments} />
                  <JsonValue label="Assign" value={state.Assign} />
                  <JsonValue label="Output" value={state.Output} />
                </div>
              </DetailSection>
            )}

            {typeof state.Next === 'string' && (
              <DetailSection eyebrow="Transition" title="Next state">
                <ValueCard label="Next" value={`→ ${state.Next}`} />
              </DetailSection>
            )}

            {state.End === true && (
              <DetailSection eyebrow="Transition" title="End">
                <ValueCard label="End" value="true" />
              </DetailSection>
            )}

            {supportsRetry && hasFailurePolicy && <RetryDetails state={state} />}
          </>
        )}
      </div>
    </>
  );
}
