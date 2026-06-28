type Props = {
  title: string;
  message: string;
  action?: { label: string; onClick: () => void };
};

export function WorkspaceState({ title, message, action }: Props) {
  return (
    <div className="flex h-full items-center justify-center bg-surface-lowest p-6">
      <div className="glass-card max-w-md rounded-DEFAULT border border-border-subtle bg-surface-container-lowest p-5 text-center">
        <div className="font-headline-md text-headline-md font-medium text-primary">{title}</div>
        <p className="mt-2 text-body-sm text-on-surface-variant">{message}</p>
        {action && (
          <button
            type="button"
            onClick={action.onClick}
            className="mt-4 rounded-DEFAULT border border-border-subtle bg-surface-elevated px-3 py-1.5 text-body-sm text-primary transition-colors hover:bg-surface-container"
          >
            {action.label}
          </button>
        )}
      </div>
    </div>
  );
}
