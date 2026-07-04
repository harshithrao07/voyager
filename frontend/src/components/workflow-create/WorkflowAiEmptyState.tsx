import { useRef, useState, type ChangeEvent, type ReactNode } from 'react';
import { Compass, Lightbulb, Sparkles, X } from 'lucide-react';

const promptExamples = [
  {
    label: 'Monitor API health',
    prompt: 'Run an API health check every 5 minutes. If it fails twice, retry once and notify Slack.',
  },
  {
    label: 'Retry failed payments',
    prompt: 'Retry failed payment sync jobs with backoff, stop after 3 attempts, and create an alert when all attempts fail.',
  },
  {
    label: 'Fetch, transform, notify',
    prompt: 'Fetch customer records from an API, transform the response, store the result, then notify the operations channel.',
  },
  {
    label: 'Generate compliance report',
    prompt: 'Generate a daily compliance report, branch on missing data, and send the final summary by email.',
  },
];

export function WorkflowAiEmptyState({
  chatInputNode,
  onSubmitPrompt,
  onPreviewPrompt,
  onImportTemplate,
}: {
  chatInputNode: ReactNode;
  onSubmitPrompt: (prompt: string) => void;
  onPreviewPrompt?: (prompt: string | null) => void;
  onImportTemplate?: (raw: string, fileName?: string) => void;
}) {
  const [examplesOpen, setExamplesOpen] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const openTemplatePicker = () => fileInputRef.current?.click();

  const handleTemplateFile = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    try {
      const raw = await file.text();
      onImportTemplate?.(raw, file.name);
    } catch {
      onImportTemplate?.('', file.name);
    }
  };

  const previewExample = (prompt: string | null) => onPreviewPrompt?.(prompt);

  return (
    <div className="flex flex-1 flex-col items-center justify-center overflow-y-auto px-6 pb-[7vh] pt-6 md:px-8">
      <div className="w-full max-w-[640px]">
        <div className="mb-14 text-center">
          <div className="voyager-hero-wordmark inline-flex items-center justify-center gap-1.5">
            <img src="/voyager-logo.svg" alt="" className="h-16 w-16 shrink-0" />
            <div className="font-mono-sm text-[38px] font-semibold leading-none tracking-normal text-primary md:text-[40px]">Voyager</div>
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

        <input
          ref={fileInputRef}
          type="file"
          accept=".json,.asl,application/json"
          className="hidden"
          onChange={handleTemplateFile}
        />

        <div className="mx-auto mt-4 grid max-w-[560px] gap-4 md:grid-cols-2">
          <ActionTile
            icon={<Sparkles size={15} />}
            title="Import from template"
            subtitle="Start from an ASL JSON file"
            onClick={openTemplatePicker}
          />
          <ActionTile
            icon={<Compass size={15} />}
            title="Explore examples"
            subtitle="See what's possible"
            onClick={() => setExamplesOpen((open) => {
              const next = !open;
              if (!next) previewExample(null);
              return next;
            })}
            active={examplesOpen}
          />
        </div>

        {examplesOpen && (
          <div className="voyager-examples-panel mx-auto mt-5 w-full max-w-[600px] rounded-[18px] border text-left">
            <div className="flex h-10 items-center justify-between px-4">
              <div className="flex items-center gap-2 text-body-sm text-on-surface-variant">
                <Lightbulb size={14} className="text-secondary/80" />
                <span>Voyager's choice</span>
              </div>
              <button
                type="button"
                onClick={() => {
                  previewExample(null);
                  setExamplesOpen(false);
                }}
                className="flex h-7 w-7 items-center justify-center rounded-md text-on-surface transition-colors hover:bg-surface-container-high"
                title="Close examples"
              >
                <X size={15} />
              </button>
            </div>
            <div className="divide-y divide-border-muted/70 px-3 pb-3">
              {promptExamples.map((example) => (
                <button
                  key={example.label}
                  type="button"
                  onClick={() => {
                    previewExample(null);
                    onSubmitPrompt(example.prompt);
                  }}
                  onMouseEnter={() => previewExample(example.prompt)}
                  onMouseLeave={() => previewExample(null)}
                  onFocus={() => previewExample(example.prompt)}
                  onBlur={() => previewExample(null)}
                  title="Click to send this example"
                  className="block min-h-[48px] w-full rounded-md px-2 py-3 text-left text-[13px] leading-[1.45] text-on-surface transition-colors hover:bg-surface-container-high/55 focus-visible:bg-surface-container-high focus-visible:outline-none"
                >
                  {example.label}
                </button>
              ))}
            </div>
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
