import { useMemo, useState } from 'react';
import { Search, X } from 'lucide-react';

export type RevisionHistoryItem = {
  id: string;
  label: string;
  timestamp: string;
  active?: boolean;
  note?: string;
};

type Props = {
  revisions: RevisionHistoryItem[];
  selectedRevisionId: string;
  onRevisionSelected: (revisionId: string) => void;
  onClose: () => void;
};

export function RevisionHistoryPanel({
  revisions,
  selectedRevisionId,
  onRevisionSelected,
  onClose,
}: Props) {
  const [query, setQuery] = useState('');
  const normalizedQuery = query.trim().toLowerCase();
  const filteredRevisions = useMemo(() => {
    if (!normalizedQuery) return revisions;

    return revisions.filter((revision) => {
      const searchable = `${revision.label} ${revision.timestamp} ${revision.note || ''}`.toLowerCase();
      return searchable.includes(normalizedQuery);
    });
  }, [normalizedQuery, revisions]);

  return (
    <>
      <div className="glass-shell border-b border-border-subtle bg-surface-elevated/50 px-5 py-4 backdrop-blur-md">
        <div className="flex items-center justify-between gap-3">
          <div>
            <div className="text-label-caps font-label-caps text-on-surface-variant">Revision History</div>
            <h2
              className="mt-1 font-headline-md text-headline-md font-medium text-primary"
              data-testid="workflow-revision-count"
            >
              {revisions.length} revisions
            </h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-on-surface-variant transition-colors hover:text-primary"
            aria-label="Close revision history"
            title="Close revision history"
          >
            <X size={20} />
          </button>
        </div>

        <div className="relative mt-4">
          <Search size={15} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-on-surface-variant" />
          <input
            className="w-full rounded-DEFAULT border border-border-subtle bg-surface-lowest py-2 pl-8 pr-3 font-body-sm text-body-sm text-primary placeholder-on-surface-variant/60 outline-none transition-colors focus:border-status-info focus:ring-1 focus:ring-status-info"
            type="text"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search revisions..."
          />
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-2">
        {filteredRevisions.map((revision) => {
          const selected = revision.id === selectedRevisionId;

          return (
            <button
              key={revision.id}
              type="button"
              data-testid={`workflow-revision-${revision.id}`}
              data-revision-active={revision.active ? 'true' : 'false'}
              onClick={() => onRevisionSelected(revision.id)}
              className={`glass-card group relative mb-2 w-full overflow-hidden rounded-DEFAULT border p-4 text-left transition-colors ${selected ? 'border-status-info bg-surface-container-high text-primary' : 'border-border-subtle bg-surface-lowest/40 text-on-surface-variant hover:border-border-muted hover:bg-surface-container-lowest hover:text-primary'}`}
              aria-pressed={selected}
            >
              {selected && <div className="absolute bottom-0 left-0 top-0 w-1 bg-status-info" />}
              <div className={selected ? 'pl-2' : ''}>
                <div className="flex items-center justify-between gap-2">
                  <span className="font-headline-md text-headline-md text-primary">{revision.label}</span>
                  <div className="flex shrink-0 items-center gap-1">
                    {revision.active && (
                      <span className="rounded border border-status-success/20 bg-status-success/10 px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wider text-status-success">
                        Active
                      </span>
                    )}
                    {selected && (
                      <span className="rounded border border-status-info/20 bg-status-info/10 px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wider text-status-info">
                        Selected
                      </span>
                    )}
                  </div>
                </div>
                <div className="mt-1 font-mono-sm text-mono-sm text-on-surface-variant">{revision.timestamp}</div>
                {revision.note && (
                  <p className="mt-3 font-body-sm text-body-sm text-on-surface-variant">
                    {revision.note}
                  </p>
                )}
                <div className="mt-4 flex items-center justify-between border-t border-border-subtle pt-3">
                  <span className="text-label-caps font-label-caps text-on-surface-variant">
                    {selected ? 'Selected revision' : 'Select revision'}
                  </span>
                  <span className={`material-symbols-outlined text-[16px] transition-transform ${selected ? 'text-status-info' : 'text-on-surface-variant group-hover:translate-x-0.5 group-hover:text-primary'}`}>
                    arrow_forward
                  </span>
                </div>
              </div>
            </button>
          );
        })}

        {filteredRevisions.length === 0 && (
          <div className="glass-card rounded-DEFAULT border border-border-subtle bg-surface-lowest p-4 text-body-sm text-on-surface-variant">
            No revisions match that search.
          </div>
        )}
      </div>
    </>
  );
}
