type Revision = {
  id: string;
  label: string;
  timestamp: string;
  active?: boolean;
};

const revisions: Revision[] = [
  { id: 'rev-3', label: 'Rev 3', timestamp: 'Today, 14:32 UTC', active: true },
  { id: 'rev-2', label: 'Rev 2', timestamp: 'Yesterday, 09:15 UTC' },
  { id: 'rev-1', label: 'Rev 1', timestamp: 'Oct 12, 18:00 UTC' },
];

export function RevisionHistoryPanel() {
  return (
    <>
      <div className="p-4 border-b border-border-subtle bg-surface-container-lowest">
        <h2 className="font-headline-md text-headline-md text-primary flex items-center gap-2">
          <span className="material-symbols-outlined text-[18px]">history</span>
          Revision History
        </h2>
      </div>

      <div className="flex-1 overflow-y-auto">
        {revisions.map((revision) => (
          <div
            key={revision.id}
            className={`p-5 border-b border-border-subtle relative overflow-hidden group ${revision.active ? 'bg-surface-container-low' : 'hover:bg-surface-container-lowest transition-colors'}`}
          >
            {revision.active && <div className="absolute left-0 top-0 bottom-0 w-1 bg-status-info" />}
            <div className={revision.active ? 'pl-2' : ''}>
              <div className="flex items-center gap-2">
                <span className="font-headline-md text-headline-md text-primary">{revision.label}</span>
                {revision.active && (
                  <span className="bg-status-success/10 text-status-success border border-status-success/20 px-1.5 py-0.5 rounded text-[10px] uppercase font-bold tracking-wider">
                    Active
                  </span>
                )}
              </div>
              <div className="font-mono-sm text-mono-sm text-on-surface-variant mt-1">{revision.timestamp}</div>

              <div className={`mt-4 ${revision.active ? '' : 'opacity-0 group-hover:opacity-100 transition-opacity'}`}>
                <button
                  className={`w-full border border-border-muted py-1.5 rounded font-body-sm text-body-sm transition-colors ${revision.active ? 'bg-surface-lowest text-on-surface-variant cursor-not-allowed opacity-70' : 'bg-transparent hover:bg-surface-container text-on-surface'}`}
                  disabled={revision.active}
                >
                  {revision.active ? 'Currently Active' : 'Activate Revision'}
                </button>
              </div>
            </div>
          </div>
        ))}
      </div>
    </>
  );
}
