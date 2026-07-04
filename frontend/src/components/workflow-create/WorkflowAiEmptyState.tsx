import { useState, type ReactNode } from 'react';
import { Compass, DatabaseZap, HeartPulse, RotateCcw, ShieldCheck, Sparkles } from 'lucide-react';

const promptExamples = [
  {
    icon: <HeartPulse size={15} />,
    label: 'API health check',
    prompt: 'Run an API health check every 5 minutes. If it fails twice, retry once and notify Slack.',
  },
  {
    icon: <RotateCcw size={15} />,
    label: 'Failed payment retry',
    prompt: 'Retry failed payment sync jobs with backoff, stop after 3 attempts, and create an alert when all attempts fail.',
  },
  {
    icon: <DatabaseZap size={15} />,
    label: 'Fetch transform notify',
    prompt: 'Fetch customer records from an API, transform the response, store the result, then notify the operations channel.',
  },
  {
    icon: <ShieldCheck size={15} />,
    label: 'Compliance report',
    prompt: 'Generate a daily compliance report, branch on missing data, and send the final summary by email.',
  },
];

export function WorkflowAiEmptyState({
  chatInputNode,
  onSelectPrompt,
}: {
  chatInputNode: ReactNode;
  onSelectPrompt: (prompt: string) => void;
}) {
  const [examplesOpen, setExamplesOpen] = useState(false);

  return (
    <div className="flex flex-1 flex-col items-center justify-center overflow-y-auto px-6 pb-[7vh] pt-6 md:px-8">
      <div className="w-full max-w-[940px]">
        <div className="mb-16 text-center">
          <div className="voyager-hero-wordmark inline-flex items-center justify-center gap-1.5">
            <img src="/voyager-logo.svg" alt="" className="h-16 w-16 shrink-0 md:h-20 md:w-20" />
            <div className="font-mono-sm text-[34px] font-semibold leading-none tracking-normal text-primary md:text-[42px]">Voyager</div>
          </div>
          <p className="mx-auto mt-3 max-w-[520px] font-mono-sm text-[11px] uppercase tracking-[0.2em] text-secondary/80">
            Smooth sailing for complex workflows
          </p>
        </div>

        <div className="pointer-events-auto w-full">
          {chatInputNode}
        </div>

        <div className="mt-8 text-center font-mono-sm text-[11px] text-on-surface-variant">
          Not sure what to ask? Try one of these
        </div>

        <div className="mx-auto mt-4 grid max-w-[560px] gap-4 md:grid-cols-2">
          <ActionTile
            icon={<Sparkles size={15} />}
            title="Import from template"
            subtitle="Start from a proven workflow"
            onClick={() => onSelectPrompt('Create a workflow from a proven template for a scheduled data sync with retries, validation, and notification.')}
          />
          <ActionTile
            icon={<Compass size={15} />}
            title={examplesOpen ? 'Hide examples' : 'Explore examples'}
            subtitle="See what's possible"
            onClick={() => setExamplesOpen((open) => !open)}
            active={examplesOpen}
          />
        </div>

        {examplesOpen && (
          <div className="mx-auto mt-5 grid max-w-[900px] gap-3 md:grid-cols-2">
            {promptExamples.map((example) => (
              <button
                key={example.label}
                type="button"
                onClick={() => onSelectPrompt(example.prompt)}
                className="voyager-action-tile group flex min-h-[64px] items-center gap-3 rounded-lg border border-border-subtle px-4 py-3 text-left transition-colors hover:border-secondary/45"
              >
                <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-secondary/25 bg-secondary-container/25 text-secondary">
                  {example.icon}
                </span>
                <span className="min-w-0">
                  <span className="block truncate font-mono-sm text-[11px] font-semibold text-on-surface">{example.label}</span>
                  <span className="mt-0.5 line-clamp-2 block text-body-sm text-on-surface-variant">{example.prompt}</span>
                </span>
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function ActionTile({
  icon,
  title,
  subtitle,
  onClick,
  active = false,
}: {
  icon: ReactNode;
  title: string;
  subtitle: string;
  onClick: () => void;
  active?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`voyager-action-tile flex min-h-[60px] items-center gap-3 rounded-lg border px-4 py-3 text-left transition-colors ${
        active
          ? 'border-secondary/45 bg-secondary-container/25'
          : 'border-border-subtle hover:border-secondary/35'
      }`}
    >
      <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-secondary/25 bg-surface-base text-secondary">
        {icon}
      </span>
      <span className="min-w-0">
        <span className="block truncate font-mono-sm text-[11px] font-semibold text-on-surface">{title}</span>
        <span className="mt-0.5 block truncate text-body-sm text-on-surface-variant">{subtitle}</span>
      </span>
    </button>
  );
}
