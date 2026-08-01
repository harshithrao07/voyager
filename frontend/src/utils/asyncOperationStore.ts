import { useCallback, useSyncExternalStore } from 'react';

export type AsyncOperationStatus = 'running' | 'succeeded' | 'failed';

export type AsyncOperationEntry<T = unknown> = {
  id: number;
  key: string;
  status: AsyncOperationStatus;
  startedAt: number;
  finishedAt?: number;
  result?: T;
  error?: string;
};

/**
 * Owns long-running browser requests independently of routed components. Navigating
 * away only unsubscribes the old view; a remounted view adopts the same operation.
 */
class AsyncOperationStore {
  private entries = new Map<string, AsyncOperationEntry>();
  private promises = new Map<string, Promise<unknown>>();
  private listeners = new Set<() => void>();
  private version = 0;
  private nextId = 0;

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getVersion = () => this.version;

  getEntry = <T,>(key: string): AsyncOperationEntry<T> | undefined => (
    this.entries.get(key) as AsyncOperationEntry<T> | undefined
  );

  run<T>(key: string, operation: () => Promise<T>): Promise<T> {
    const existing = this.promises.get(key);
    if (existing) return existing as Promise<T>;

    const id = this.nextId += 1;
    this.entries.set(key, { id, key, status: 'running', startedAt: Date.now() });
    this.emit();

    const promise = Promise.resolve()
      .then(operation)
      .then((result) => {
        this.replace(key, id, {
          status: 'succeeded',
          finishedAt: Date.now(),
          result,
        });
        return result;
      })
      .catch((error: unknown) => {
        this.replace(key, id, {
          status: 'failed',
          finishedAt: Date.now(),
          error: error instanceof Error ? error.message : 'Request failed.',
        });
        throw error;
      })
      .finally(() => {
        if (this.entries.get(key)?.id === id) this.promises.delete(key);
      });

    this.promises.set(key, promise);
    return promise;
  }

  private replace(key: string, id: number, patch: Partial<AsyncOperationEntry>) {
    const current = this.entries.get(key);
    if (!current || current.id !== id) return;
    this.entries.set(key, { ...current, ...patch });
    this.emit();
  }

  private emit() {
    this.version += 1;
    this.listeners.forEach((listener) => listener());
  }
}

export const asyncOperationStore = new AsyncOperationStore();

export function useAsyncOperation<T>(key: string): AsyncOperationEntry<T> | undefined {
  const getSnapshot = useCallback(() => asyncOperationStore.getEntry<T>(key), [key]);
  return useSyncExternalStore(asyncOperationStore.subscribe, getSnapshot);
}

/** Subscribe when a view renders several operation keys dynamically. */
export function useAsyncOperationVersion(): number {
  return useSyncExternalStore(asyncOperationStore.subscribe, asyncOperationStore.getVersion);
}
