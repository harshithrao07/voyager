import { useEffect, useRef, type ReactNode } from 'react';
import ReactMarkdown from 'react-markdown';
import { AlertCircle, Bot, Brain, ChevronDown, Loader2, RefreshCw, Sparkles } from 'lucide-react';
import { chatMarkdownComponents } from './chatMarkdown';
import type { ChatMessage } from './types';

const suggestions = [
  'Add a retry with backoff to the failing task',
  'Branch on the response status and alert on failure',
  'Explain what this workflow does',
];

const messageTimestampFormatter = new Intl.DateTimeFormat(undefined, {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

function MessageTimestamp({ createdAt, align }: { createdAt: number; align: 'left' | 'right' }) {
  const date = new Date(createdAt);
  if (Number.isNaN(date.getTime())) return null;

  return (
    <time
      dateTime={date.toISOString()}
      title={date.toLocaleString()}
      className={`mt-1 block font-mono-sm text-[9px] leading-4 text-on-surface-variant/65 ${
        align === 'right' ? 'text-right' : 'text-left'
      }`}
    >
      {messageTimestampFormatter.format(date)}
    </time>
  );
}

export function SidebarAiChat({
  messages,
  generating,
  error,
  hasStates,
  chatInputNode,
  onSubmitPrompt,
  expandedThinkingMessageIds,
  onToggleThinking,
  regeneratingMessageId,
  onRegenerateMessage,
  renderMessageAttachment,
}: {
  messages: ChatMessage[];
  generating: boolean;
  error: string | null;
  hasStates: boolean;
  chatInputNode: ReactNode;
  onSubmitPrompt: (prompt: string) => void;
  expandedThinkingMessageIds: Set<string>;
  onToggleThinking: (messageId: string) => void;
  regeneratingMessageId: string | null;
  onRegenerateMessage: (message: ChatMessage) => void;
  renderMessageAttachment?: (message: ChatMessage) => ReactNode;
}) {
  const endRef = useRef<HTMLDivElement>(null);

  const lastMessage = messages[messages.length - 1];
  const retryableMessageId = lastMessage?.role === 'assistant' && lastMessage.persisted
    ? lastMessage.id
    : null;
  const streamingKey = `${messages.length}:${lastMessage?.content.length || 0}:${lastMessage?.thinkingContent?.length || 0}`;

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [streamingKey, generating]);

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
        {messages.length === 0 ? (
          <div className="flex h-full flex-col justify-center">
            <div className="flex flex-col items-center text-center">
              <span className="flex h-9 w-9 items-center justify-center rounded-lg border border-primary/25 bg-surface-container-low text-primary">
                <Sparkles size={16} />
              </span>
              <div className="mt-3 font-mono-sm text-[11px] font-semibold text-on-surface">
                Edit this workflow with AI
              </div>
              <p className="mt-1.5 text-body-sm leading-relaxed text-on-surface-variant">
                {hasStates
                  ? 'The assistant can see the ASL in your editor and will amend it in place.'
                  : 'Add a state, or just describe the workflow you want and the assistant will build it.'}
              </p>
            </div>
            {hasStates && (
              <div className="mt-5 space-y-1.5">
                {suggestions.map((suggestion) => (
                  <button
                    key={suggestion}
                    type="button"
                    onClick={() => onSubmitPrompt(suggestion)}
                    className="block w-full rounded-DEFAULT border border-border-subtle px-3 py-2 text-left text-body-sm leading-snug text-on-surface-variant transition-colors hover:border-secondary/40 hover:text-on-surface"
                  >
                    {suggestion}
                  </button>
                ))}
              </div>
            )}
          </div>
        ) : (
          <div className="space-y-4">
            {messages.map((message) => (
              <SidebarChatMessage
                key={message.id}
                message={message}
                thinkingExpanded={expandedThinkingMessageIds.has(message.id)}
                onToggleThinking={() => onToggleThinking(message.id)}
                canRegenerate={message.id === retryableMessageId}
                regenerating={regeneratingMessageId === message.id}
                onRegenerate={() => onRegenerateMessage(message)}
                attachment={renderMessageAttachment?.(message)}
              />
            ))}
            {error && (
              <div className="flex items-start gap-2 rounded-DEFAULT border border-status-error/25 bg-status-error/10 p-3 text-body-sm text-status-error">
                <AlertCircle className="mt-0.5 shrink-0" size={14} />
                <span>{error}</span>
              </div>
            )}
            <div ref={endRef} />
          </div>
        )}
      </div>
      <div className="shrink-0 border-t border-border-subtle p-3">
        {chatInputNode}
      </div>
    </div>
  );
}

function SidebarChatMessage({
  message,
  thinkingExpanded,
  onToggleThinking,
  canRegenerate,
  regenerating,
  onRegenerate,
  attachment,
}: {
  message: ChatMessage;
  thinkingExpanded: boolean;
  onToggleThinking: () => void;
  canRegenerate: boolean;
  regenerating: boolean;
  onRegenerate: () => void;
  attachment?: ReactNode;
}) {
  if (message.role === 'user') {
    return (
      <div className="ml-6 rounded-DEFAULT border border-border-subtle bg-surface-container-low px-3 py-2 font-mono-sm text-[12px] leading-5 text-on-surface">
        <div>{message.content}</div>
        <MessageTimestamp createdAt={message.createdAt} align="right" />
      </div>
    );
  }

  const streaming = Boolean(message.streamingStatus);
  // The reveal animation streams reasoning before the answer, so a message can legitimately
  // have no content yet while thinking is still arriving.
  const awaitingAnswer = streaming && !message.content;

  return (
    <div className="flex gap-2">
      <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-md border border-primary/25 bg-surface-container-low text-primary">
        {streaming ? <Loader2 className="animate-spin" size={12} /> : <Bot size={12} />}
      </span>
      <div className="min-w-0 flex-1">
        {message.thinkingContent && (
          <div className="mb-2 overflow-hidden rounded-DEFAULT border border-border-subtle bg-surface-container-low/50">
            <button
              type="button"
              onClick={onToggleThinking}
              className="flex w-full items-center gap-1.5 px-2.5 py-1.5 font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant transition-colors hover:text-on-surface"
            >
              <Brain size={11} className="shrink-0 text-secondary/80" />
              <span className="flex-1 text-left">Reasoning</span>
              <ChevronDown
                size={12}
                className={`shrink-0 transition-transform ${thinkingExpanded ? 'rotate-180' : ''}`}
              />
            </button>
            {thinkingExpanded && (
              <div className="max-h-[220px] overflow-y-auto border-t border-border-subtle px-2.5 py-2 font-mono-sm text-[11px] leading-5 text-on-surface-variant">
                {message.thinkingContent}
              </div>
            )}
          </div>
        )}

        {awaitingAnswer ? (
          <span className="font-mono-sm text-[12px] text-on-surface-variant">
            {message.streamingPhase === 'thinking' ? 'Reasoning...' : 'Thinking...'}
          </span>
        ) : message.content ? (
          <div className="font-mono-sm text-[12px] leading-5 text-on-surface">
            <ReactMarkdown components={chatMarkdownComponents}>
              {message.content}
            </ReactMarkdown>
          </div>
        ) : (
          // An empty reply would otherwise render as a bare avatar and read as a lost message.
          <span className="font-mono-sm text-[12px] italic text-on-surface-variant">
            The model returned an empty reply.
          </span>
        )}

        {!streaming && (
          <MessageTimestamp createdAt={message.createdAt} align="left" />
        )}

        {attachment && <div className="mt-3">{attachment}</div>}

        {!streaming && canRegenerate && (
          <button
            type="button"
            onClick={onRegenerate}
            disabled={regenerating}
            className="mt-1.5 flex h-6 items-center gap-1.5 rounded-[3px] px-1.5 font-mono-sm text-[10px] text-on-surface-variant transition-colors hover:bg-surface-container hover:text-on-surface disabled:cursor-not-allowed disabled:opacity-50"
            title="Regenerate this reply"
          >
            {regenerating
              ? <Loader2 className="animate-spin" size={11} />
              : <RefreshCw size={11} />}
            Retry
          </button>
        )}
      </div>
    </div>
  );
}
