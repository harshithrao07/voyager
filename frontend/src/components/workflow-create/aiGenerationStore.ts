import { useCallback, useSyncExternalStore } from 'react';
import {
  continueWorkflowAiConversation,
  isAbortError,
  startWorkflowAiConversation,
  type WorkflowAiChatRequest,
  type WorkflowAiResponse,
  type WorkflowAiStartRequest,
  type WorkflowAiStreamEvent,
} from '../../api';
import type { ChatMessage } from './types';

/**
 * A workflow AI turn runs on a websocket that must outlive the component that started it: the user
 * can navigate to another page mid-generation and come back expecting the request to still be in
 * flight (and cancellable). This module-level store owns those in-flight turns, keyed by the
 * conversation they belong to, so remounting `CreateWorkflowView` re-adopts the live turn instead
 * of losing it.
 */

/** Sentinel key for a brand-new conversation whose id is only known once the turn resolves. */
export const NEW_CONVERSATION_KEY = '__new__';

export type AiGenerationStatus = 'active' | 'done' | 'error' | 'cancelled';

export type AiGenerationEntry = {
  /** Stable id for this turn; lets a component apply each completion exactly once. */
  turnId: string;
  key: string;
  status: AiGenerationStatus;
  /** The user turn shown optimistically while the assistant works. */
  userMessage: ChatMessage;
  /** The live assistant bubble: 'processing' until the first frame, then 'streaming'. */
  assistantMessage: ChatMessage;
  /**
   * Whether any live frame arrived this turn. The backend only streams reasoning text, never the
   * answer, so when nothing streamed the view must typewriter the final answer to keep the streaming
   * feel; when reasoning did stream, replaying it would show it twice.
   */
  streamed: boolean;
  response?: WorkflowAiResponse;
  error?: string;
  abort: () => void;
};

type StartOptions = {
  key: string;
  kind: 'start' | 'continue';
  request: WorkflowAiStartRequest | WorkflowAiChatRequest;
  userMessage: ChatMessage;
  assistantMessageId: string;
  modelConfigId: string | null;
  modelDisplayName: string | null;
};

let turnCounter = 0;

class AiGenerationStore {
  private entries = new Map<string, AiGenerationEntry>();

  private listeners = new Set<() => void>();

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  getEntry = (key: string): AiGenerationEntry | undefined => this.entries.get(key);

  private emit() {
    this.listeners.forEach((listener) => listener());
  }

  private replace(key: string, patch: Partial<AiGenerationEntry>) {
    const current = this.entries.get(key);
    if (!current) return;
    this.entries.set(key, { ...current, ...patch });
    this.emit();
  }

  start(options: StartOptions): void {
    const existing = this.entries.get(options.key);
    // A live turn for this key is already running; ignore repeat sends.
    if (existing?.status === 'active') return;

    const controller = new AbortController();
    const turnId = `turn-${(turnCounter += 1)}`;
    const assistantMessage: ChatMessage = {
      id: options.assistantMessageId,
      role: 'assistant',
      content: '',
      createdAt: Date.now(),
      modelConfigId: options.modelConfigId,
      modelDisplayName: options.modelDisplayName,
      streamingStatus: 'processing',
    };

    this.entries.set(options.key, {
      turnId,
      key: options.key,
      status: 'active',
      userMessage: options.userMessage,
      assistantMessage,
      streamed: false,
      abort: () => controller.abort(),
    });
    this.emit();

    // Per-turn frame accumulation, mirroring the previous in-component stream handler. A later pass
    // supersedes the previous one, so its reasoning replaces rather than appends.
    let currentPass = 0;
    const handleEvent = (event: WorkflowAiStreamEvent) => {
      if (event.type === 'ERROR') {
        // The rejected socket promise renders the failure; a duplicate here would race it.
        return;
      }
      const startsNewPass = event.pass > currentPass;
      if (startsNewPass) {
        currentPass = event.pass;
      }
      const entry = this.entries.get(options.key);
      if (!entry || entry.turnId !== turnId) return;

      const base: ChatMessage = startsNewPass
        ? { ...entry.assistantMessage, thinkingContent: null }
        : entry.assistantMessage;

      let nextAssistant: ChatMessage;
      if (event.type === 'STAGE') {
        nextAssistant = {
          ...base,
          streamingStatus: 'streaming',
          streamingPhase: 'thinking',
          streamingStage: event.stage ?? null,
        };
      } else if (event.type === 'THINKING_DELTA') {
        nextAssistant = {
          ...base,
          streamingStatus: 'streaming',
          streamingPhase: 'thinking',
          thinkingContent: (base.thinkingContent || '') + (event.text || ''),
        };
      } else {
        // ANSWER_PROGRESS: the envelope is JSON under construction, so report the phase only.
        nextAssistant = {
          ...base,
          streamingStatus: 'streaming',
          streamingPhase: 'answer',
        };
      }
      this.replace(options.key, { assistantMessage: nextAssistant, streamed: true });
    };

    const socketPromise = options.kind === 'start'
      ? startWorkflowAiConversation(
          options.request as WorkflowAiStartRequest,
          handleEvent,
          controller.signal,
        )
      : continueWorkflowAiConversation(
          options.request as WorkflowAiChatRequest,
          handleEvent,
          controller.signal,
        );

    socketPromise
      .then((response) => {
        const entry = this.entries.get(options.key);
        if (!entry || entry.turnId !== turnId) return;
        this.replace(options.key, { status: 'done', response });
      })
      .catch((err: unknown) => {
        const entry = this.entries.get(options.key);
        if (!entry || entry.turnId !== turnId) return;
        if (isAbortError(err) || controller.signal.aborted) {
          this.replace(options.key, { status: 'cancelled' });
          return;
        }
        this.replace(options.key, {
          status: 'error',
          error: err instanceof Error ? err.message : 'Failed to generate workflow.',
        });
      });
  }

  abort(key: string): void {
    this.entries.get(key)?.abort();
  }

  /**
   * Drops a resolved turn once its owning component has applied the result. A no-op while the turn
   * is still active, so an accidental consume cannot cancel a live generation.
   */
  consume(key: string, turnId: string): void {
    const entry = this.entries.get(key);
    if (!entry || entry.turnId !== turnId || entry.status === 'active') return;
    this.entries.delete(key);
    this.emit();
  }
}

export const aiGenerationStore = new AiGenerationStore();

export function useAiGeneration(key: string | null): AiGenerationEntry | undefined {
  const getSnapshot = useCallback(
    () => (key ? aiGenerationStore.getEntry(key) : undefined),
    [key],
  );
  return useSyncExternalStore(aiGenerationStore.subscribe, getSnapshot);
}
