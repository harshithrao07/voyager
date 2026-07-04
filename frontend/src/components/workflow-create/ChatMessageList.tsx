import type { ReactNode, RefObject } from 'react';
import ReactMarkdown from 'react-markdown';
import { AlertCircle, Bot, Check, ChevronDown, Copy, Loader2, MoreHorizontal, RefreshCw } from 'lucide-react';
import type { AiModel, ChatMessage } from './types';

const chatMessageBodyClass = 'font-mono-sm text-[12px] leading-5 text-on-surface';

const chatMarkdownComponents = {
  p: ({ children }: any) => <p className="mb-3 last:mb-0">{children}</p>,
  ul: ({ children }: any) => <ul className="my-3 list-disc space-y-1 pl-5">{children}</ul>,
  ol: ({ children }: any) => <ol className="my-3 list-decimal space-y-1 pl-5">{children}</ol>,
  li: ({ children }: any) => <li className="pl-1">{children}</li>,
  h1: ({ children }: any) => <h3 className="mb-2 mt-4 font-mono-sm text-[13px] font-semibold leading-5 text-primary first:mt-0">{children}</h3>,
  h2: ({ children }: any) => <h3 className="mb-2 mt-4 font-mono-sm text-[13px] font-semibold leading-5 text-primary first:mt-0">{children}</h3>,
  h3: ({ children }: any) => <h3 className="mb-2 mt-3 font-mono-sm text-[12px] font-semibold leading-5 text-primary first:mt-0">{children}</h3>,
  strong: ({ children }: any) => <strong className="font-semibold text-primary">{children}</strong>,
  em: ({ children }: any) => <em className="text-secondary">{children}</em>,
  a: ({ href, children }: any) => (
    <a href={href} className="text-primary underline underline-offset-2 hover:text-primary-fixed" target="_blank" rel="noreferrer">
      {children}
    </a>
  ),
  blockquote: ({ children }: any) => (
    <blockquote className="my-3 border-l border-primary/30 pl-3 text-on-surface-variant">
      {children}
    </blockquote>
  ),
  pre: ({ children }: any) => (
    <pre className="my-3 overflow-x-auto rounded-DEFAULT border border-border-subtle bg-surface-lowest p-3 text-[12px] leading-5">
      {children}
    </pre>
  ),
  code: ({ className, children, ...props }: any) => {
    const isBlock = Boolean(className);
    return (
      <code
        className={isBlock
          ? `${className || ''} bg-transparent p-0 font-mono-sm text-primary`
          : 'rounded-DEFAULT border border-border-subtle bg-surface-container px-1 py-0.5 font-mono-sm text-[12px] text-primary'}
        {...props}
      >
        {children}
      </code>
    );
  },
};

function formatMessageTime(value: number) {
  return new Intl.DateTimeFormat(undefined, {
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

function formatDuration(value?: number | null) {
  if (!value || value < 0) return null;
  if (value < 1000) return `${value}ms`;
  return `${(value / 1000).toFixed(value < 10000 ? 1 : 0)}s`;
}

function formatTokenCount(value?: number | null) {
  if (!value || value < 0) return null;
  if (value >= 1000) return `${(value / 1000).toFixed(value < 10000 ? 1 : 0)}k tokens`;
  return `${value} tokens`;
}

type Props = {
  messages: ChatMessage[];
  chatEntered: boolean;
  chatScrollRef: RefObject<HTMLDivElement | null>;
  chatEndRef: RefObject<HTMLDivElement | null>;
  chatSessionModelLabel: string;
  chatSessionTimeLabel: string;
  selectedModel: AiModel | null;
  editingMessageId: string | null;
  editingMessageContent: string;
  onEditingMessageContentChange: (value: string) => void;
  copiedMessageId: string | null;
  expandedThinkingMessageIds: Set<string>;
  regeneratingMessageId: string | null;
  error: string | null;
  headerNode?: ReactNode;
  onCancelEditingMessage: () => void;
  onSaveEditedMessage: () => void;
  onStartEditingMessage: (message: ChatMessage) => void;
  onCopyMessage: (message: ChatMessage) => void;
  onToggleThinking: (messageId: string) => void;
  onRegenerateMessage: (message: ChatMessage) => void;
};

export function ChatMessageList({
  messages,
  chatEntered,
  chatScrollRef,
  chatEndRef,
  chatSessionModelLabel,
  chatSessionTimeLabel,
  selectedModel,
  editingMessageId,
  editingMessageContent,
  onEditingMessageContentChange,
  copiedMessageId,
  expandedThinkingMessageIds,
  regeneratingMessageId,
  error,
  headerNode,
  onCancelEditingMessage,
  onSaveEditedMessage,
  onStartEditingMessage,
  onCopyMessage,
  onToggleThinking,
  onRegenerateMessage,
}: Props) {
  return (
    <>
      <div className="pointer-events-none absolute left-0 right-0 top-3 z-10 flex justify-center">
        <div className="flex items-center gap-2 font-mono-sm text-[12px] text-on-surface-variant">
          <span className="text-secondary">{chatSessionModelLabel}</span>
          {chatSessionTimeLabel && <span>{chatSessionTimeLabel}</span>}
          <span>&middot;</span>
          <span>{messages.length} msgs</span>
          <ChevronDown size={13} />
        </div>
      </div>
      <div ref={chatScrollRef} className="flex-1 overflow-y-auto p-8 pb-[320px] scroll-smooth">
        <div className={`mx-auto w-full max-w-[900px] space-y-6 transition-all duration-500 ease-out ${chatEntered ? 'translate-y-0 opacity-100' : 'translate-y-6 opacity-0'}`}>
          {headerNode}
          {messages.map((message) => {
            const isEditing = editingMessageId === message.id;
            const durationLabel = formatDuration(message.durationMs);
            const tokenLabel = formatTokenCount(message.totalTokens);
            const assistantModelLabel = message.modelDisplayName || selectedModel?.label || 'AI model';
            const thinkingExpanded = expandedThinkingMessageIds.has(message.id);

            return (
              <div key={message.id} className={`group flex ${message.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                {message.role === 'user' ? (
                  <div className="w-fit max-w-[min(420px,78%)] rounded-[24px] border border-secondary/25 bg-surface-container-low p-4 text-body-md text-on-surface shadow-[0_12px_32px_rgba(0,0,0,0.18)]">
                    <div className="mb-3 flex items-center justify-end gap-2 font-mono-sm text-[12px] text-on-surface-variant">
                      <span className="h-2 w-2 rounded-full bg-secondary/55" />
                      <span className="font-semibold text-secondary">You</span>
                      <span>{formatMessageTime(message.createdAt)}</span>
                    </div>

                    {isEditing ? (
                      <MessageEditor
                        value={editingMessageContent}
                        onChange={onEditingMessageContentChange}
                        onCancel={onCancelEditingMessage}
                        onSave={onSaveEditedMessage}
                        minHeightClass="min-h-[96px]"
                      />
                    ) : (
                      <>
                        <div className={`whitespace-pre-wrap ${chatMessageBodyClass}`}>{message.content}</div>
                        <div className="mt-4 flex items-center justify-end gap-2 text-on-surface-variant">
                          <IconAction label="Edit message" onClick={() => onStartEditingMessage(message)}>
                            <span className="material-symbols-outlined text-[17px]">edit</span>
                          </IconAction>
                          <IconAction label="Copy message" onClick={() => onCopyMessage(message)}>
                            {copiedMessageId === message.id ? <Check size={15} /> : <Copy size={15} />}
                          </IconAction>
                          <IconAction label="More actions">
                            <MoreHorizontal size={16} />
                          </IconAction>
                        </div>
                      </>
                    )}
                  </div>
                ) : (
                  <div className="w-full max-w-[840px] rounded-[22px] border border-secondary/25 bg-surface-lowest p-4 text-body-md text-on-surface shadow-[0_18px_50px_rgba(0,0,0,0.28)]">
                    <div className="mb-4 flex items-center gap-2 font-mono-sm">
                      <Bot size={15} className="text-primary" />
                      <span className="text-[12px] font-semibold text-primary">{assistantModelLabel}</span>
                      <span className="text-[12px] text-on-surface-variant">{formatMessageTime(message.createdAt)}</span>
                    </div>

                    {isEditing ? (
                      <MessageEditor
                        value={editingMessageContent}
                        onChange={onEditingMessageContentChange}
                        onCancel={onCancelEditingMessage}
                        onSave={onSaveEditedMessage}
                        minHeightClass="min-h-[120px]"
                      />
                    ) : message.streamingStatus === 'processing' ? (
                      <div className="flex min-h-[38px] items-center gap-3 font-mono-sm text-[12px] text-primary">
                        <span>Processing request</span>
                        <span className="voyager-processing-bars" aria-hidden="true">
                          <span />
                          <span />
                          <span />
                        </span>
                      </div>
                    ) : (
                      <>
                        {(message.thinkingContent || message.streamingPhase === 'thinking') && (
                          <div className="mb-5 overflow-hidden rounded-DEFAULT border border-primary/25 bg-primary/10 font-mono-sm">
                            <button
                              type="button"
                              onClick={() => onToggleThinking(message.id)}
                              className="flex w-full items-center justify-between gap-3 border-b border-primary/20 px-4 py-3 text-left text-primary transition-colors hover:bg-primary/10"
                            >
                              <span>{thinkingExpanded ? 'Hide' : 'Show'} thinking process</span>
                              <span className="flex items-center gap-2 text-[12px] text-on-surface-variant">
                                {durationLabel && <span>{durationLabel}</span>}
                                {durationLabel && tokenLabel && <span>&middot;</span>}
                                {tokenLabel && <span>{tokenLabel}</span>}
                                <ChevronDown size={14} className={`transition-transform ${thinkingExpanded ? 'rotate-180' : ''}`} />
                              </span>
                            </button>
                            {thinkingExpanded && (
                              <div className="max-h-[220px] overflow-y-auto px-4 py-4 text-[12px] leading-5 text-secondary">
                                <div className="whitespace-pre-wrap">
                                  {message.thinkingContent}
                                  {message.streamingStatus === 'streaming' && message.streamingPhase === 'thinking' && (
                                    <span className="voyager-stream-cursor" aria-hidden="true" />
                                  )}
                                </div>
                              </div>
                            )}
                          </div>
                        )}
                        <div className={chatMessageBodyClass}>
                          <ReactMarkdown components={chatMarkdownComponents}>{message.content}</ReactMarkdown>
                          {message.streamingStatus === 'streaming' && message.streamingPhase === 'answer' && (
                            <span className="voyager-stream-cursor" aria-hidden="true" />
                          )}
                        </div>
                        {message.streamingStatus !== 'streaming' && (
                          <div className="mt-6 flex items-center gap-3 font-mono-sm text-[12px] text-on-surface-variant">
                            {tokenLabel && <span>{tokenLabel}</span>}
                            {durationLabel && <span>&middot; {durationLabel}</span>}
                            {(tokenLabel || durationLabel) && <span className="h-4 w-px bg-border-subtle" />}
                            <IconAction
                              label="Regenerate message"
                              onClick={() => onRegenerateMessage(message)}
                              disabled={Boolean(regeneratingMessageId)}
                            >
                              {regeneratingMessageId === message.id
                                ? <Loader2 className="animate-spin" size={14} />
                                : <RefreshCw size={14} />}
                            </IconAction>
                            <IconAction label="Copy message" onClick={() => onCopyMessage(message)}>
                              {copiedMessageId === message.id ? <Check size={14} /> : <Copy size={14} />}
                            </IconAction>
                            <IconAction label="Edit message" onClick={() => onStartEditingMessage(message)}>
                              <span className="material-symbols-outlined text-[16px]">edit</span>
                            </IconAction>
                            <IconAction label="More actions">
                              <MoreHorizontal size={16} />
                            </IconAction>
                          </div>
                        )}
                      </>
                    )}
                  </div>
                )}
              </div>
            );
          })}
          {error && (
            <div className="mt-4 rounded-DEFAULT border border-status-error/25 bg-status-error/10 p-3 text-body-sm text-status-error">
              <div className="flex items-start gap-2">
                <AlertCircle className="mt-0.5 shrink-0" size={16} />
                <div>{error}</div>
              </div>
            </div>
          )}
          <div ref={chatEndRef} className="h-[220px]" aria-hidden="true" />
        </div>
      </div>
    </>
  );
}

function IconAction({
  label,
  onClick,
  disabled,
  children,
}: {
  label: string;
  onClick?: () => void;
  disabled?: boolean;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="flex h-8 w-8 items-center justify-center rounded-DEFAULT transition-colors hover:bg-surface-container hover:text-secondary disabled:cursor-not-allowed disabled:opacity-40"
      title={label}
      aria-label={label}
    >
      {children}
    </button>
  );
}

function MessageEditor({
  value,
  onChange,
  onCancel,
  onSave,
  minHeightClass,
}: {
  value: string;
  onChange: (value: string) => void;
  onCancel: () => void;
  onSave: () => void;
  minHeightClass: string;
}) {
  return (
    <div className="space-y-2">
      <textarea
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className={`${minHeightClass} w-full resize-y rounded-DEFAULT border border-secondary/40 bg-surface-base p-3 font-mono-sm text-body-md text-secondary outline-none focus:border-secondary`}
        autoFocus
      />
      <div className="flex justify-end gap-2">
        <button
          type="button"
          onClick={onCancel}
          className="h-8 rounded-DEFAULT px-3 font-body-sm text-body-sm text-on-surface-variant transition-colors hover:bg-surface-container hover:text-on-surface"
        >
          Cancel
        </button>
        <button
          type="button"
          onClick={onSave}
          className="h-8 rounded-DEFAULT bg-primary px-3 font-body-sm text-body-sm font-medium text-on-primary transition-colors hover:bg-primary-fixed-dim"
        >
          Save
        </button>
      </div>
    </div>
  );
}
