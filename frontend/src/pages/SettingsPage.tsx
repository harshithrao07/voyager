import { Judge0RuntimePanel } from '../components/Judge0RuntimePanel';

export function SettingsPage() {
  return (
    <div className="h-full overflow-y-auto px-6 py-6 md:px-10">
      <div className="mx-auto w-full max-w-[820px] space-y-5">
        <div>
          <h1 className="font-display text-[18px] font-semibold text-on-surface">Settings</h1>
          <p className="mt-1 font-mono-sm text-[11px] text-on-surface-variant">
            Self-hosted runtime status and limits. Sandbox ceilings and secrets are set by the operator
            in <span className="text-secondary">judge0.conf</span>; per-function limits are set on each function version.
          </p>
        </div>
        <Judge0RuntimePanel />
      </div>
    </div>
  );
}
