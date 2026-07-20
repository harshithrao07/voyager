import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  Activity,
  AlertTriangle,
  ArrowLeft,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Clock3,
  Copy,
  Globe,
  KeyRound,
  Loader2,
  Pencil,
  Play,
  Plug,
  Plus,
  Power,
  RefreshCw,
  Search,
  Shield,
  ShieldAlert,
  Timer,
  Wrench,
  X,
  XCircle,
  Zap,
} from 'lucide-react';
import {
  callMcpTool,
  getMcpServer,
  listMcpExecutions,
  listMcpKnownTools,
  listMcpLiveTools,
  listMcpServers,
  registerMcpServer,
  syncMcpTools,
  updateMcpServer,
  updateMcpServerStatus,
  type McpAuthType,
  type McpServerDTO,
  type McpServerRequest,
  type McpToolCallResult,
  type McpToolDTO,
  type McpToolExecutionDTO,
  type McpToolExecutionStatus,
  type McpToolSyncResultDTO,
  type McpTransport,
  type McpTrustLevel,
} from '../api';

type DetailTab = 'tools' | 'executions' | 'playground';
type StatusFilter = 'ALL' | 'ENABLED' | 'DISABLED';

const serverIdPattern = /^[a-z0-9][a-z0-9-]*$/;

function slugifyServerId(value: string) {
  return value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}
const fieldClass =
  'h-9 w-full rounded-lg border border-border-subtle bg-surface-container-lowest px-3 text-[12px] text-on-surface outline-none transition-colors placeholder:text-on-surface-variant/45 focus:border-primary/60';
const selectFieldClass = `${fieldClass} py-0 leading-[34px]`;
const labelClass = 'mb-1.5 flex items-center gap-1.5 text-[11px] text-on-surface-variant';

const TRUST_LEVELS: McpTrustLevel[] = ['UNTRUSTED', 'READ_ONLY', 'WRITE', 'DESTRUCTIVE'];
const GRANTABLE_TRUST_LEVELS: McpTrustLevel[] = ['READ_ONLY', 'WRITE', 'DESTRUCTIVE'];

function trustLevelLabel(level: McpTrustLevel) {
  return level === 'READ_ONLY'
    ? 'Read only'
    : level.charAt(0) + level.slice(1).toLowerCase();
}

const TRUST_META: Record<McpTrustLevel, { rank: number; text: string; bar: string; description: string }> = {
  UNTRUSTED: {
    rank: 0,
    text: 'text-on-surface-variant',
    bar: 'bg-on-surface-variant',
    description: 'External and unvetted. Direct calls are always rejected.',
  },
  READ_ONLY: {
    rank: 1,
    text: 'text-secondary',
    bar: 'bg-secondary',
    description: 'May read data. Mutations are blocked by policy.',
  },
  WRITE: {
    rank: 2,
    text: 'text-primary',
    bar: 'bg-primary',
    description: 'May create and update records on connected systems.',
  },
  DESTRUCTIVE: {
    rank: 3,
    text: 'text-status-error',
    bar: 'bg-status-error',
    description: 'Full access including deletes. Grant sparingly.',
  },
};

function mcpResource(serverId: string, toolName?: string) {
  return `voyager://mcp/${serverId || 'server'}/${toolName || '<tool>'}`;
}

function formatUpdated(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Updated recently';
  const diffMs = Date.now() - date.getTime();
  const minute = 60 * 1000;
  const hour = 60 * minute;
  const day = 24 * hour;
  if (diffMs < minute) return 'Updated just now';
  if (diffMs < hour) return `Updated ${Math.floor(diffMs / minute)}m ago`;
  if (diffMs < day) return `Updated ${Math.floor(diffMs / hour)}h ago`;
  return `Updated ${Math.floor(diffMs / day)}d ago`;
}

function formatDateTime(value?: string | null) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(date);
}

function formatDuration(durationMs?: number | null) {
  if (durationMs === null || durationMs === undefined) return '-';
  if (durationMs < 1000) return `${durationMs} ms`;
  return `${(durationMs / 1000).toFixed(2)} s`;
}

function prettyJson(value: unknown) {
  if (value === null || value === undefined) return '';
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function argsTemplateFromSchema(schema: unknown): string {
  const objectSchema = schema as
    | { properties?: Record<string, { type?: string }>; required?: string[] }
    | null
    | undefined;
  const properties = objectSchema?.properties;
  if (!properties || typeof properties !== 'object') return '{}';
  const required = new Set(objectSchema?.required ?? []);
  const keys = Object.keys(properties).sort((a, b) => Number(required.has(b)) - Number(required.has(a)));
  const template: Record<string, unknown> = {};
  for (const key of keys) {
    const type = properties[key]?.type;
    if (type === 'number' || type === 'integer') template[key] = 0;
    else if (type === 'boolean') template[key] = false;
    else if (type === 'array') template[key] = [];
    else if (type === 'object') template[key] = {};
    else template[key] = '';
  }
  return JSON.stringify(template, null, 2);
}

function TrustBadge({ level, showLabel = true }: { level: McpTrustLevel; showLabel?: boolean }) {
  const meta = TRUST_META[level];
  return (
    <span className={`inline-flex items-center gap-1.5 ${meta.text}`} title={`Trust level: ${level}`}>
      <span className="flex items-end gap-[2px]">
        {[5, 7, 9, 11].map((height, index) => (
          <span
            key={height}
            style={{ height, width: 3 }}
            className={`rounded-[1px] ${index <= meta.rank ? meta.bar : 'bg-surface-container-highest'}`}
          />
        ))}
      </span>
      {showLabel && <span className="font-mono-sm text-[10px] tracking-[0.05em]">{level}</span>}
    </span>
  );
}

function ServerStatusChip({ status }: { status: McpServerDTO['status'] }) {
  return (
    <span className={`rounded-md border px-2 py-1 text-[11px] font-medium ${
      status === 'ENABLED'
        ? 'border-secondary/35 bg-secondary/10 text-secondary'
        : 'border-border-subtle bg-surface-container-low text-on-surface-variant'
    }`}>
      {status === 'ENABLED' ? 'Enabled' : 'Disabled'}
    </span>
  );
}

function ExecutionStatusChip({ status }: { status: McpToolExecutionStatus }) {
  const meta = status === 'SUCCESS'
    ? { className: 'border-secondary/35 bg-secondary/10 text-secondary', icon: <CheckCircle2 size={11} /> }
    : status === 'FAILED'
      ? { className: 'border-status-error/35 bg-status-error/10 text-status-error', icon: <XCircle size={11} /> }
      : status === 'REJECTED'
        ? { className: 'border-primary/35 bg-primary/10 text-primary', icon: <ShieldAlert size={11} /> }
        : { className: 'border-border-subtle bg-surface-container-low text-on-surface-variant', icon: <Loader2 size={11} className="animate-spin" /> };
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-md border px-2 py-0.5 font-mono-sm text-[10px] tracking-[0.04em] ${meta.className}`}>
      {meta.icon}
      {status}
    </span>
  );
}

function McpMetric({ label, value, tone }: { label: string; value: string | number; tone: string }) {
  return (
    <div className="border-b border-border-subtle px-4 py-3 md:border-b-0 md:border-r">
      <div className="text-label-caps font-label-caps text-on-surface-variant">{label}</div>
      <div className={`mt-1 font-mono-sm text-[14px] font-semibold ${tone}`}>{value}</div>
    </div>
  );
}

function SummaryCard({ icon, label, value }: { icon: ReactNode; label: string; value: string | number }) {
  return (
    <div className="flex min-h-[96px] items-center gap-4 rounded-lg border border-border-subtle bg-[linear-gradient(145deg,rgba(255,255,255,0.045),rgba(255,255,255,0.015))] px-5 shadow-[inset_0_1px_0_rgba(255,255,255,0.04)]">
      <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl border border-primary/20 bg-primary/10 text-primary shadow-[0_0_32px_rgba(242,121,90,0.12)]">
        {icon}
      </div>
      <div className="min-w-0">
        <div className="text-[12px] text-on-surface-variant">{label}</div>
        <div className="mt-1 truncate text-[18px] font-semibold text-on-surface">{value}</div>
      </div>
    </div>
  );
}

function HeroChip({ icon, label }: { icon: ReactNode; label: ReactNode }) {
  return (
    <span className="inline-flex h-8 items-center gap-1.5 rounded-md border border-border-subtle bg-surface-container-lowest/65 px-2.5 text-[12px] text-on-surface-variant">
      {icon}
      {label}
    </span>
  );
}

function JsonBlock({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <div className="mb-1.5 text-label-caps font-label-caps text-on-surface-variant">{label}</div>
      <pre className="max-h-56 overflow-auto rounded-lg border border-border-subtle bg-surface-container-lowest/85 p-3 font-mono-sm text-[11px] leading-[1.6] text-on-surface">
        {value || '-'}
      </pre>
    </div>
  );
}

function ErrorNote({ message }: { message: string }) {
  return (
    <div
      data-testid="mcp-error-note"
      className="flex items-start gap-2 rounded-lg border border-status-error/35 bg-status-error/10 px-3 py-2 text-[12px] leading-5 text-status-error"
    >
      <AlertTriangle size={14} className="mt-0.5 shrink-0" />
      <span className="min-w-0 break-words">{message}</span>
    </div>
  );
}

// STDIO args are edited one-per-line; env as KEY=VALUE lines.
function parseArgsText(text: string): string[] {
  return text.split('\n').map((line) => line.trim()).filter((line) => line.length > 0);
}

function parseEnvText(text: string): Record<string, string> {
  const env: Record<string, string> = {};
  for (const line of text.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const separator = trimmed.indexOf('=');
    if (separator <= 0) continue;
    env[trimmed.slice(0, separator).trim()] = trimmed.slice(separator + 1).trim();
  }
  return env;
}

function formatEnv(env: Record<string, string> | null | undefined): string {
  return Object.entries(env ?? {}).map(([key, value]) => `${key}=${value}`).join('\n');
}

function validateKeyValueText(text: string, label: string): string | null {
  const lines = text.split('\n');
  for (let index = 0; index < lines.length; index += 1) {
    const trimmed = lines[index].trim();
    if (!trimmed) continue;
    if (trimmed.indexOf('=') <= 0) {
      return `${label} line ${index + 1} must use NAME=VALUE.`;
    }
  }
  return null;
}

/** Human-readable address: base URL + endpoint for HTTP, the command line for STDIO. */
function serverAddress(server: McpServerDTO): string {
  if (server.transport === 'STDIO') {
    return [server.command ?? '', ...(server.args ?? [])].join(' ').trim();
  }
  return `${server.baseUrl ?? ''}${server.endpoint ?? ''}`;
}

function authTypeLabel(authType: McpAuthType): string {
  switch (authType) {
    case 'BEARER_TOKEN': return 'Bearer';
    case 'API_KEY': return 'API key';
    case 'BASIC': return 'Basic';
    case 'CUSTOM_HEADERS': return 'Custom headers';
    default: return 'No auth';
  }
}

type ServerFormModalProps = {
  mode: 'create' | 'edit';
  initial?: McpServerDTO;
  onCancel: () => void;
  onSaved: (server: McpServerDTO) => void;
};

function ServerFormModal({ mode, initial, onCancel, onSaved }: ServerFormModalProps) {
  const [displayName, setDisplayName] = useState(initial?.displayName ?? '');
  const [serverId, setServerId] = useState(initial?.serverId ?? '');
  const [serverIdTouched, setServerIdTouched] = useState(mode === 'edit');
  const [transport, setTransport] = useState<McpTransport>(initial?.transport ?? 'HTTP');
  const [baseUrl, setBaseUrl] = useState(initial?.baseUrl ?? '');
  const [endpoint, setEndpoint] = useState(initial?.endpoint ?? '/mcp');
  const [command, setCommand] = useState(initial?.command ?? '');
  const [argsText, setArgsText] = useState((initial?.args ?? []).join('\n'));
  const [envText, setEnvText] = useState(formatEnv(initial?.env));
  // Secret env values are never returned; prefill existing keys with empty values
  // (blank = keep the stored encrypted value on save).
  const [secretEnvText, setSecretEnvText] = useState(
    (initial?.secretEnvKeys ?? []).map((name) => `${name}=`).join('\n'),
  );
  const [secretHeadersText, setSecretHeadersText] = useState(
    [...(initial?.secretHeaderNames ?? [])].sort().map((name) => `${name}=`).join('\n'),
  );
  const [authEnvVar, setAuthEnvVar] = useState(initial?.authEnvVar ?? '');
  const [authType, setAuthType] = useState<McpAuthType>(initial?.authType ?? 'NONE');
  const [authToken, setAuthToken] = useState('');
  const [authHeaderName, setAuthHeaderName] = useState(initial?.authHeaderName ?? '');
  const [authUsername, setAuthUsername] = useState(initial?.authUsername ?? '');
  const [trustLevel, setTrustLevel] = useState<McpTrustLevel>(initial?.trustLevel ?? 'UNTRUSTED');
  const [enabled, setEnabled] = useState(initial ? initial.status === 'ENABLED' : false);
  const [timeoutMs, setTimeoutMs] = useState(initial?.requestTimeoutMs ? String(initial.requestTimeoutMs) : '');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const validate = (): string | null => {
    if (!displayName.trim()) return 'Display name is required.';
    if (!serverIdPattern.test(serverId)) return 'Server ID must use lowercase letters, numbers, and hyphens.';
    if (transport === 'HTTP') {
      try {
        const parsed = new URL(baseUrl);
        if (!parsed.protocol || !parsed.host) return 'Base URL must be an absolute URL.';
      } catch {
        return 'Base URL must be an absolute URL, e.g. https://mcp.example.com';
      }
      if (!endpoint.startsWith('/')) return 'Endpoint must start with /';
    } else {
      if (!command.trim()) return 'Command is required for STDIO transport.';
      if (authType !== 'NONE' && authType !== 'BEARER_TOKEN') {
        return 'STDIO transport supports only None or Bearer token auth.';
      }
      if (authType === 'BEARER_TOKEN' && !authEnvVar.trim()) {
        return 'A token env var is required to inject the token for STDIO bearer auth.';
      }
      const secretEnvError = validateKeyValueText(secretEnvText, 'Secret environment');
      if (secretEnvError) return secretEnvError;
    }
    if (authType !== 'NONE' && authType !== 'CUSTOM_HEADERS'
      && !authToken.trim() && !(mode === 'edit' && initial?.hasAuthToken)) {
      return 'A token is required for authenticated servers.';
    }
    if (authType === 'CUSTOM_HEADERS') {
      const headerTextError = validateKeyValueText(secretHeadersText, 'Custom header');
      if (headerTextError) return headerTextError;
      const headers = parseEnvText(secretHeadersText);
      if (Object.keys(headers).length === 0) {
        return 'At least one custom authentication header is required.';
      }
      if (mode === 'create' && Object.values(headers).some((value) => !value)) {
        return 'Custom authentication header values are required when registering a server.';
      }
    }
    if (authType === 'API_KEY' && !authHeaderName.trim()) {
      return 'A header name is required for API key auth.';
    }
    if (authType === 'BASIC' && !authUsername.trim()) {
      return 'A username is required for basic auth.';
    }
    if (timeoutMs.trim()) {
      const value = Number(timeoutMs);
      if (!Number.isInteger(value) || value <= 0) return 'Request timeout must be a positive integer (ms).';
    }
    return null;
  };

  const submit = async () => {
    const validationError = validate();
    if (validationError) {
      setError(validationError);
      return;
    }
    const isStdio = transport === 'STDIO';
    const request: McpServerRequest = {
      serverId,
      displayName: displayName.trim(),
      transport,
      baseUrl: isStdio ? null : baseUrl.trim(),
      endpoint: isStdio ? null : endpoint.trim(),
      command: isStdio ? command.trim() : null,
      args: isStdio ? parseArgsText(argsText) : null,
      env: isStdio ? parseEnvText(envText) : null,
      secretEnv: isStdio ? parseEnvText(secretEnvText) : null,
      secretHeaders: authType === 'CUSTOM_HEADERS' ? parseEnvText(secretHeadersText) : null,
      authEnvVar: isStdio && authType === 'BEARER_TOKEN' ? authEnvVar.trim() : null,
      authType,
      // Blank on edit keeps the existing encrypted token; NONE clears it.
      authToken: authType !== 'NONE' && authType !== 'CUSTOM_HEADERS'
        ? (authToken.trim() || null)
        : null,
      authHeaderName: authType === 'API_KEY' ? authHeaderName.trim() : null,
      authUsername: authType === 'BASIC' ? authUsername.trim() : null,
      trustLevel,
      status: enabled ? 'ENABLED' : 'DISABLED',
      requestTimeoutMs: timeoutMs.trim() ? Number(timeoutMs) : null,
    };
    setBusy(true);
    setError(null);
    try {
      const saved = mode === 'create'
        ? await registerMcpServer(request)
        : await updateMcpServer(serverId, request);
      onSaved(saved);
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : 'Request failed.');
      setBusy(false);
    }
  };

  return (
    <div
      data-testid="mcp-server-form"
      className="fixed inset-0 z-[100] flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      onClick={onCancel}
    >
      <div
        className="flex max-h-full w-full max-w-[620px] flex-col overflow-hidden rounded-xl border border-border-subtle bg-surface-container-lowest shadow-[0_30px_80px_rgba(0,0,0,0.5)]"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex shrink-0 items-start gap-3 border-b border-border-subtle p-5 pb-4">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-primary/35 bg-primary/10 text-primary">
            <Plug size={18} />
          </div>
          <div className="min-w-0 flex-1">
            <h3 className="text-[15px] font-semibold text-on-surface">
              {mode === 'create' ? 'Register MCP server' : `Edit ${initial?.displayName ?? 'server'}`}
            </h3>
            <p className="mt-1 text-[12px] leading-5 text-on-surface-variant">
              Connect a Model Context Protocol server so workflows can call its tools via{' '}
              <span className="font-mono-sm text-[11px] text-secondary">{mcpResource(serverId || 'server-id')}</span>
            </p>
          </div>
          <button
            type="button"
            onClick={onCancel}
            className="shrink-0 rounded-md p-1 text-on-surface-variant transition-colors hover:text-on-surface"
            aria-label="Close"
          >
            <X size={16} />
          </button>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto p-5">
        <div className="grid gap-3 sm:grid-cols-2">
          <label>
            <span className={labelClass}>Display name</span>
            <input
              data-testid="mcp-display-name"
              value={displayName}
              onChange={(event) => {
                const next = event.target.value;
                setDisplayName(next);
                if (mode === 'create' && !serverIdTouched) {
                  setServerId(slugifyServerId(next));
                }
              }}
              placeholder="GitHub Tools"
              className={fieldClass}
            />
          </label>
          <label>
            <span className={labelClass}>
              Server ID
              {mode === 'edit' && <span className="text-on-surface-variant/60">(immutable)</span>}
            </span>
            <input
              data-testid="mcp-server-id"
              value={serverId}
              onChange={(event) => {
                const next = event.target.value;
                setServerId(next);
                setServerIdTouched(next.trim().length > 0);
              }}
              placeholder="github-tools"
              disabled={mode === 'edit'}
              className={`${fieldClass} font-mono-sm disabled:opacity-60`}
            />
            {mode === 'create' && (
              <span className="mt-1 block text-[10.5px] leading-4 text-on-surface-variant/70">
                {serverIdTouched
                  ? 'Immutable after creation — used in voyager://mcp/… task URIs.'
                  : 'Suggested from the display name — edit to override. Immutable after creation.'}
              </span>
            )}
          </label>
          <label className="sm:col-span-2">
            <span className={labelClass}>Transport</span>
            <select
              data-testid="mcp-transport"
              value={transport}
              onChange={(event) => setTransport(event.target.value as McpTransport)}
              className={selectFieldClass}
            >
              <option value="HTTP">HTTP (streamable)</option>
              <option value="STDIO">STDIO (local process)</option>
            </select>
          </label>
          {transport === 'HTTP' ? (
            <>
              <label>
                <span className={labelClass}>Base URL</span>
                <input
                  data-testid="mcp-base-url"
                  value={baseUrl}
                  onChange={(event) => setBaseUrl(event.target.value)}
                  placeholder="https://mcp.example.com"
                  className={`${fieldClass} font-mono-sm`}
                />
              </label>
              <label>
                <span className={labelClass}>Endpoint</span>
                <input
                  data-testid="mcp-endpoint"
                  value={endpoint}
                  onChange={(event) => setEndpoint(event.target.value)}
                  placeholder="/mcp"
                  className={`${fieldClass} font-mono-sm`}
                />
              </label>
            </>
          ) : (
            <>
              <label className="sm:col-span-2">
                <span className={labelClass}>Command</span>
                <input
                  data-testid="mcp-command"
                  value={command}
                  onChange={(event) => setCommand(event.target.value)}
                  placeholder="npx"
                  className={`${fieldClass} font-mono-sm`}
                />
              </label>
              <label className="sm:col-span-2">
                <span className={labelClass}>Arguments (one per line)</span>
                <textarea
                  data-testid="mcp-args"
                  value={argsText}
                  onChange={(event) => setArgsText(event.target.value)}
                  placeholder={'-y\n@modelcontextprotocol/server-filesystem\n/data'}
                  rows={3}
                  className={`${fieldClass} font-mono-sm`}
                />
              </label>
              <label className="sm:col-span-2">
                <span className={labelClass}>Environment (KEY=VALUE per line)</span>
                <textarea
                  value={envText}
                  onChange={(event) => setEnvText(event.target.value)}
                  placeholder={'LOG_LEVEL=info\nMCP_MODE=stdio'}
                  rows={2}
                  className={`${fieldClass} font-mono-sm`}
                />
                <span className="mt-1 block text-[10.5px] leading-4 text-on-surface-variant/70">
                  Non-secret settings only — these are stored and shown in plaintext.
                </span>
              </label>
              <label className="sm:col-span-2">
                <span className={labelClass}>Secret environment (KEY=VALUE per line)</span>
                <textarea
                  value={secretEnvText}
                  onChange={(event) => setSecretEnvText(event.target.value)}
                  placeholder={'GITHUB_TOKEN=ghp_...'}
                  rows={2}
                  className={`${fieldClass} font-mono-sm`}
                />
                <span className="mt-1 block text-[10.5px] leading-4 text-on-surface-variant/70">
                  Values are encrypted and never shown again. Leave a value blank to keep the stored secret; remove the line to drop it.
                </span>
              </label>
            </>
          )}
          <label>
            <span className={labelClass}>Request timeout (ms)</span>
            <input
              data-testid="mcp-timeout"
              value={timeoutMs}
              onChange={(event) => setTimeoutMs(event.target.value)}
              placeholder="Application default"
              inputMode="numeric"
              className={`${fieldClass} font-mono-sm`}
            />
          </label>
          <label>
            <span className={labelClass}>Authentication</span>
            <select
              data-testid="mcp-auth-type"
              value={authType}
              onChange={(event) => setAuthType(event.target.value as McpAuthType)}
              className={selectFieldClass}
            >
              <option value="NONE">None</option>
              <option value="BEARER_TOKEN">Bearer token</option>
              <option value="API_KEY">API key (header)</option>
              <option value="BASIC">Basic</option>
              {transport === 'HTTP' && <option value="CUSTOM_HEADERS">Multiple custom headers</option>}
            </select>
          </label>
          {authType !== 'NONE' && authType !== 'CUSTOM_HEADERS' ? (
            <label>
              <span className={labelClass}>
                <KeyRound size={11} />
                {authType === 'BASIC' ? 'Password' : 'Token / secret'}
              </span>
              <input
                type="password"
                value={authToken}
                onChange={(event) => setAuthToken(event.target.value)}
                placeholder={mode === 'edit' && initial?.hasAuthToken ? 'Leave blank to keep the stored value' : 'Paste the token/secret'}
                autoComplete="off"
                className={`${fieldClass} font-mono-sm`}
              />
              <span className="mt-1 block text-[10.5px] leading-4 text-on-surface-variant/70">
                Encrypted and stored in the database; never shown again.
              </span>
            </label>
          ) : (
            <div />
          )}
          {authType === 'CUSTOM_HEADERS' && (
            <label className="sm:col-span-2">
              <span className={labelClass}>
                <KeyRound size={11} />
                Secret request headers (NAME=VALUE per line)
              </span>
              <textarea
                data-testid="mcp-secret-headers"
                value={secretHeadersText}
                onChange={(event) => setSecretHeadersText(event.target.value)}
                placeholder={'X-API-Key=secret-value\nX-Client-Secret=another-secret'}
                rows={3}
                className={`${fieldClass} font-mono-sm`}
              />
              <span className="mt-1 block text-[10.5px] leading-4 text-on-surface-variant/70">
                Values are encrypted and never returned. On edit, leave a value blank to keep it; remove the line to delete that header.
              </span>
            </label>
          )}
          {authType === 'API_KEY' && (
            <label>
              <span className={labelClass}>Header name</span>
              <input
                value={authHeaderName}
                onChange={(event) => setAuthHeaderName(event.target.value)}
                placeholder="X-API-Key"
                className={`${fieldClass} font-mono-sm`}
              />
            </label>
          )}
          {authType === 'BASIC' && (
            <label>
              <span className={labelClass}>Username</span>
              <input
                value={authUsername}
                onChange={(event) => setAuthUsername(event.target.value)}
                placeholder="api-user"
                className={`${fieldClass} font-mono-sm`}
              />
            </label>
          )}
          {transport === 'STDIO' && authType === 'BEARER_TOKEN' && (
            <label>
              <span className={labelClass}>
                <KeyRound size={11} />
                Token env var
              </span>
              <input
                value={authEnvVar}
                onChange={(event) => setAuthEnvVar(event.target.value)}
                placeholder="GITHUB_PERSONAL_ACCESS_TOKEN"
                className={`${fieldClass} font-mono-sm`}
              />
              <span className="mt-1 block text-[10.5px] leading-4 text-on-surface-variant/70">
                The resolved token is passed to the process in this environment variable.
              </span>
            </label>
          )}
        </div>

        <div className="mt-4">
          <span className={labelClass}>Trust level</span>
          <div className="grid gap-2 sm:grid-cols-2">
            {TRUST_LEVELS.map((level) => (
              <button
                key={level}
                type="button"
                onClick={() => setTrustLevel(level)}
                data-testid={`mcp-trust-${level.toLowerCase().replace('_', '-')}`}
                className={`rounded-lg border p-2.5 text-left transition-colors ${
                  trustLevel === level
                    ? 'border-primary/60 bg-primary/10'
                    : 'border-border-subtle bg-surface-container-low/40 hover:border-primary/35'
                }`}
              >
                <TrustBadge level={level} />
                <div className="mt-1.5 text-[11px] leading-4 text-on-surface-variant">
                  {TRUST_META[level].description}
                </div>
              </button>
            ))}
          </div>
        </div>

        <label className="mt-4 flex items-center gap-2.5">
          <input
            data-testid="mcp-enable"
            type="checkbox"
            checked={enabled}
            onChange={(event) => setEnabled(event.target.checked)}
            className="h-4 w-4 rounded border-border-subtle bg-surface-container-lowest text-primary focus:ring-primary/40"
          />
          <span className="text-[12px] text-on-surface">Enable server for workflow execution</span>
        </label>

        {error && (
          <div className="mt-3">
            <ErrorNote message={error} />
          </div>
        )}
        </div>

        <div className="flex shrink-0 justify-end gap-2 border-t border-border-subtle px-5 py-4">
          <button
            type="button"
            onClick={onCancel}
            disabled={busy}
            className="flex h-9 items-center rounded-lg border border-border-subtle px-4 text-[12px] text-on-surface-variant transition-colors hover:text-on-surface disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            data-testid="mcp-save-server"
            onClick={submit}
            disabled={busy}
            className="flex h-9 items-center gap-2 rounded-lg border border-primary bg-primary px-4 text-[12px] font-semibold text-on-primary transition-colors hover:bg-primary-fixed-dim disabled:cursor-not-allowed disabled:opacity-50"
          >
            {busy && <Loader2 size={14} className="animate-spin" />}
            {mode === 'create' ? 'Register server' : 'Save changes'}
          </button>
        </div>
      </div>
    </div>
  );
}

function SyncResultBanner({ result, onDismiss }: { result: McpToolSyncResultDTO; onDismiss: () => void }) {
  const pills: Array<{ label: string; className: string }> = [
    { label: `${result.discoveredCount} discovered`, className: 'border-secondary/35 bg-secondary/10 text-secondary' },
    { label: `${result.createdCount} created`, className: 'border-primary/35 bg-primary/10 text-primary' },
    { label: `${result.updatedCount} updated`, className: 'border-border-subtle bg-surface-container-low text-on-surface-variant' },
    { label: `${result.disabledCount} disabled`, className: 'border-border-subtle bg-surface-container-low text-on-surface-variant' },
  ];
  return (
    <div
      data-testid="mcp-sync-result"
      className="flex flex-wrap items-center gap-2 rounded-lg border border-secondary/30 bg-secondary/[0.06] px-3 py-2"
    >
      <CheckCircle2 size={14} className="shrink-0 text-secondary" />
      <span className="text-[12px] text-on-surface">Tool sync finished {formatDateTime(result.syncedAt)}</span>
      {pills.map((pill) => (
        <span key={pill.label} className={`rounded-md border px-1.5 py-0.5 font-mono-sm text-[10px] ${pill.className}`}>
          {pill.label}
        </span>
      ))}
      <button
        type="button"
        onClick={onDismiss}
        className="ml-auto rounded-md p-1 text-on-surface-variant transition-colors hover:text-on-surface"
        aria-label="Dismiss sync summary"
      >
        <X size={13} />
      </button>
    </div>
  );
}

function ToolsPanel({
  server,
  tools,
  loading,
  error,
  syncResult,
  onDismissSyncResult,
  onRunTool,
  onReload,
}: {
  server: McpServerDTO;
  tools: McpToolDTO[];
  loading: boolean;
  error: string | null;
  syncResult: McpToolSyncResultDTO | null;
  onDismissSyncResult: () => void;
  onRunTool: (toolName: string) => void;
  onReload: () => void;
}) {
  const [expandedToolId, setExpandedToolId] = useState<string | null>(null);
  const [probeState, setProbeState] = useState<{ busy: boolean; ok?: boolean; message?: string }>({ busy: false });

  const probeLiveTools = async () => {
    setProbeState({ busy: true });
    try {
      const result = await listMcpLiveTools(server.serverId);
      const liveCount = result.tools?.length ?? 0;
      setProbeState({ busy: false, ok: true, message: `Server reachable — ${liveCount} tool${liveCount === 1 ? '' : 's'} live` });
    } catch (probeError) {
      setProbeState({
        busy: false,
        ok: false,
        message: probeError instanceof Error ? probeError.message : 'Probe failed',
      });
    }
  };

  const enabledCount = tools.filter((tool) => tool.enabled).length;

  return (
    <div className="flex flex-col gap-3">
      {syncResult && <SyncResultBanner result={syncResult} onDismiss={onDismissSyncResult} />}

      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono-sm text-[11px] text-on-surface-variant">
          {tools.length} known · {enabledCount} enabled
        </span>
        <div className="ml-auto flex items-center gap-2">
          {probeState.message && (
            <span className={`font-mono-sm text-[11px] ${probeState.ok ? 'text-secondary' : 'text-status-error'}`}>
              {probeState.message}
            </span>
          )}
            <button
              type="button"
              data-testid="mcp-probe-live"
              onClick={probeLiveTools}
            disabled={probeState.busy}
            className="flex h-8 items-center gap-1.5 rounded-lg border border-border-subtle px-2.5 text-[12px] text-on-surface-variant transition-colors hover:border-secondary/45 hover:text-on-surface disabled:opacity-50"
            title="Fetch the live tool list from the server without syncing"
          >
            {probeState.busy ? <Loader2 size={13} className="animate-spin" /> : <Zap size={13} />}
            Probe live
          </button>
          <button
            type="button"
            onClick={onReload}
            className="flex h-8 w-8 items-center justify-center rounded-lg border border-border-subtle text-on-surface-variant transition-colors hover:border-primary/45 hover:text-on-surface"
            title="Reload known tools"
          >
            <RefreshCw size={13} />
          </button>
        </div>
      </div>

      {error && <ErrorNote message={error} />}

      {loading ? (
        <div className="flex items-center justify-center gap-2 rounded-lg border border-border-subtle px-3 py-12 text-[12px] text-on-surface-variant">
          <Loader2 size={14} className="animate-spin" /> Loading tools...
        </div>
      ) : tools.length === 0 ? (
        <div className="rounded-lg border border-dashed border-border-subtle px-3 py-12 text-center text-[12px] text-on-surface-variant">
          No tools synced yet. Run <span className="font-mono-sm text-secondary">Sync tools</span> to discover what this server offers.
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg border border-border-subtle">
          <div className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,2fr)_150px_110px_90px] border-b border-border-subtle bg-surface-container-lowest px-4 py-2 text-label-caps font-label-caps text-on-surface-variant">
            <div>Tool</div>
            <div>Description</div>
            <div>Last seen</div>
            <div>State</div>
            <div className="text-right">Actions</div>
          </div>
          {tools.map((tool) => {
            const expanded = expandedToolId === tool.id;
            return (
              <div key={tool.id} className={tool.enabled ? '' : 'opacity-60'}>
                <button
                  type="button"
                  data-testid={`mcp-tool-${tool.toolName}`}
                  onClick={() => setExpandedToolId(expanded ? null : tool.id)}
                  className="grid w-full grid-cols-[minmax(0,1.4fr)_minmax(0,2fr)_150px_110px_90px] items-center border-b border-border-subtle px-4 py-2.5 text-left transition-colors hover:bg-surface-container-low"
                >
                  <div className="flex min-w-0 items-center gap-2">
                    {expanded ? (
                      <ChevronDown size={13} className="shrink-0 text-primary" />
                    ) : (
                      <ChevronRight size={13} className="shrink-0 text-on-surface-variant" />
                    )}
                    <div className="min-w-0">
                      <div className="truncate font-mono-sm text-[12px] text-secondary">{tool.toolName}</div>
                      {tool.title && <div className="truncate text-[10.5px] text-on-surface-variant">{tool.title}</div>}
                    </div>
                  </div>
                  <div className="truncate pr-3 text-[12px] text-on-surface-variant">{tool.description || '-'}</div>
                  <div className="font-mono-sm text-[11px] text-on-surface-variant">{formatDateTime(tool.lastSeenAt)}</div>
                  <div>
                    <span className={`rounded-md border px-1.5 py-0.5 font-mono-sm text-[9px] uppercase tracking-[0.06em] ${
                      tool.enabled
                        ? 'border-secondary/35 bg-secondary/10 text-secondary'
                        : 'border-border-subtle bg-surface-container-low text-on-surface-variant'
                    }`}>
                      {tool.enabled ? 'Enabled' : 'Disabled'}
                    </span>
                  </div>
                  <div className="flex justify-end">
                    <span
                      role="button"
                      data-testid={`mcp-tool-run-${tool.toolName}`}
                      tabIndex={tool.enabled && server.status === 'ENABLED' ? 0 : -1}
                      onClick={(event) => {
                        event.stopPropagation();
                        if (tool.enabled && server.status === 'ENABLED') onRunTool(tool.toolName);
                      }}
                      onKeyDown={(event) => {
                        if ((event.key === 'Enter' || event.key === ' ') && tool.enabled && server.status === 'ENABLED') {
                          event.preventDefault();
                          event.stopPropagation();
                          onRunTool(tool.toolName);
                        }
                      }}
                      className={`flex h-7 items-center gap-1 rounded-md border px-2 text-[11px] transition-colors ${
                        tool.enabled && server.status === 'ENABLED'
                          ? 'border-border-subtle text-on-surface-variant hover:border-primary/45 hover:text-primary'
                          : 'cursor-not-allowed border-border-subtle/60 text-on-surface-variant/40'
                      }`}
                      title={
                        server.status !== 'ENABLED'
                          ? 'Enable the server to run tools'
                          : tool.enabled
                            ? 'Open in playground'
                            : 'Tool is disabled'
                      }
                    >
                      <Play size={11} />
                      Run
                    </span>
                  </div>
                </button>
                {expanded && (
                  <div className="grid gap-3 border-b border-border-subtle bg-surface-container-lowest/50 px-4 py-3 lg:grid-cols-2">
                    <JsonBlock label="Input schema" value={prettyJson(tool.inputSchema)} />
                    <JsonBlock label="Output schema" value={tool.outputSchema ? prettyJson(tool.outputSchema) : 'Not declared'} />
                    <div className="font-mono-sm text-[10.5px] text-on-surface-variant lg:col-span-2">
                      {mcpResource(server.serverId, tool.toolName)} · first seen {formatDateTime(tool.createdAt)}
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function ExecutionsPanel({
  executions,
  loading,
  error,
  onRefresh,
}: {
  executions: McpToolExecutionDTO[];
  loading: boolean;
  error: string | null;
  onRefresh: () => void;
}) {
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [toolFilter, setToolFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | McpToolExecutionStatus>('ALL');

  const toolNames = useMemo(
    () => Array.from(new Set(executions.map((execution) => execution.toolName))).sort(),
    [executions],
  );
  const visible = executions.filter((execution) =>
    (!toolFilter || execution.toolName === toolFilter)
    && (statusFilter === 'ALL' || execution.status === statusFilter));

  const statusOptions: Array<'ALL' | McpToolExecutionStatus> = ['ALL', 'SUCCESS', 'FAILED', 'REJECTED', 'RUNNING'];

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <div className="flex items-center gap-1 rounded-lg border border-border-subtle bg-surface-container-lowest p-1">
          {statusOptions.map((option) => (
            <button
              key={option}
              type="button"
              data-testid={`mcp-execution-filter-${option.toLowerCase()}`}
              onClick={() => setStatusFilter(option)}
              className={`rounded-md px-2 py-1 font-mono-sm text-[10px] tracking-[0.04em] transition-colors ${
                statusFilter === option
                  ? 'bg-surface-container text-primary'
                  : 'text-on-surface-variant hover:text-on-surface'
              }`}
            >
              {option === 'ALL' ? 'ALL' : option}
            </button>
          ))}
        </div>
        <select
          value={toolFilter}
          onChange={(event) => setToolFilter(event.target.value)}
          className="h-8 rounded-lg border border-border-subtle bg-surface-container-lowest px-2 text-[12px] text-on-surface outline-none focus:border-primary/60"
        >
          <option value="">All tools</option>
          {toolNames.map((name) => (
            <option key={name} value={name}>{name}</option>
          ))}
        </select>
        <span className="font-mono-sm text-[11px] text-on-surface-variant">{visible.length} of {executions.length}</span>
        <button
          type="button"
          onClick={onRefresh}
          className="ml-auto flex h-8 w-8 items-center justify-center rounded-lg border border-border-subtle text-on-surface-variant transition-colors hover:border-primary/45 hover:text-on-surface"
          title="Refresh executions"
        >
          <RefreshCw size={13} />
        </button>
      </div>

      {error && <ErrorNote message={error} />}

      {loading ? (
        <div className="flex items-center justify-center gap-2 rounded-lg border border-border-subtle px-3 py-12 text-[12px] text-on-surface-variant">
          <Loader2 size={14} className="animate-spin" /> Loading executions...
        </div>
      ) : visible.length === 0 ? (
        <div className="rounded-lg border border-dashed border-border-subtle px-3 py-12 text-center text-[12px] text-on-surface-variant">
          {executions.length === 0
            ? 'No executions recorded for this server yet.'
            : 'No executions match the current filters.'}
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg border border-border-subtle">
          <div className="grid grid-cols-[170px_minmax(0,1fr)_130px_110px_130px] border-b border-border-subtle bg-surface-container-lowest px-4 py-2 text-label-caps font-label-caps text-on-surface-variant">
            <div>Started</div>
            <div>Tool</div>
            <div>Status</div>
            <div className="text-right">Duration</div>
            <div className="text-right">Trust cap</div>
          </div>
          {visible.map((execution) => {
            const expanded = expandedId === execution.id;
            return (
              <div key={execution.id}>
                <button
                  type="button"
                  data-testid={`mcp-execution-${execution.id}`}
                  onClick={() => setExpandedId(expanded ? null : execution.id)}
                  className={`grid w-full grid-cols-[170px_minmax(0,1fr)_130px_110px_130px] items-center border-b border-border-subtle px-4 py-2.5 text-left transition-colors hover:bg-surface-container-low ${
                    expanded ? 'bg-surface-container-low/70' : ''
                  }`}
                >
                  <div className="font-mono-sm text-[11px] text-on-surface-variant">{formatDateTime(execution.startedAt)}</div>
                  <div className="truncate pr-3 font-mono-sm text-[12px] text-secondary">{execution.toolName}</div>
                  <div><ExecutionStatusChip status={execution.status} /></div>
                  <div className="text-right font-mono-sm text-[11px] text-on-surface-variant">{formatDuration(execution.durationMs)}</div>
                  <div className="flex justify-end">
                    {execution.maxAllowedTrustLevel ? <TrustBadge level={execution.maxAllowedTrustLevel} /> : <span className="text-[11px] text-on-surface-variant/50">-</span>}
                  </div>
                </button>
                {expanded && (
                  <div className="border-b border-border-subtle bg-surface-container-lowest/50 px-4 py-3">
                    {execution.errorMessage && (
                      <div className="mb-3">
                        <ErrorNote message={execution.errorMessage} />
                      </div>
                    )}
                    <div className="grid gap-3 lg:grid-cols-2">
                      <JsonBlock label="Arguments" value={prettyJson(execution.arguments)} />
                      <JsonBlock
                        label="Result"
                        value={execution.result
                          ? prettyJson(execution.result)
                          : execution.status === 'REJECTED'
                            ? 'No result — rejected before dispatch'
                            : execution.status === 'RUNNING'
                              ? 'Still running...'
                              : 'No result recorded'}
                      />
                    </div>
                    <div className="mt-2 font-mono-sm text-[10.5px] text-on-surface-variant">
                      {execution.id} · started {formatDateTime(execution.startedAt)}
                      {execution.completedAt ? ` · completed ${formatDateTime(execution.completedAt)}` : ''}
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function PlaygroundPanel({
  server,
  tools,
  selectedToolName,
  onSelectTool,
  onExecuted,
}: {
  server: McpServerDTO;
  tools: McpToolDTO[];
  selectedToolName: string;
  onSelectTool: (toolName: string) => void;
  onExecuted: () => void;
}) {
  const enabledTools = useMemo(() => tools.filter((tool) => tool.enabled), [tools]);
  const selectedTool = enabledTools.find((tool) => tool.toolName === selectedToolName) ?? null;
  const [argsText, setArgsText] = useState('{}');
  const [trustCap, setTrustCap] = useState<McpTrustLevel>('READ_ONLY');
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);
  const [result, setResult] = useState<McpToolCallResult | null>(null);
  const [lastDurationMs, setLastDurationMs] = useState<number | null>(null);

  useEffect(() => {
    if (!selectedTool && enabledTools.length > 0) {
      onSelectTool(enabledTools[0].toolName);
    }
  }, [selectedTool, enabledTools, onSelectTool]);

  useEffect(() => {
    setArgsText(selectedTool ? argsTemplateFromSchema(selectedTool.inputSchema) : '{}');
    setResult(null);
    setRunError(null);
    setLastDurationMs(null);
  }, [selectedTool]);

  const preflightWarning = server.status !== 'ENABLED'
    ? 'This server is disabled — every call will be rejected until it is enabled.'
    : server.trustLevel === 'UNTRUSTED'
      ? 'This server is UNTRUSTED — direct calls are always rejected by policy.'
      : TRUST_META[server.trustLevel].rank > TRUST_META[trustCap].rank
        ? `Server trust ${server.trustLevel} exceeds the selected cap ${trustCap} — this call will be rejected before dispatch.`
        : null;

  const run = async () => {
    if (!selectedTool) return;
    let parsedArgs: Record<string, unknown>;
    try {
      const parsed: unknown = argsText.trim() ? JSON.parse(argsText) : {};
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        setRunError('Arguments must be a JSON object.');
        return;
      }
      parsedArgs = parsed as Record<string, unknown>;
    } catch {
      setRunError('Arguments are not valid JSON.');
      return;
    }
    setRunning(true);
    setRunError(null);
    setResult(null);
    const startedAt = performance.now();
    try {
      const callResult = await callMcpTool(server.serverId, selectedTool.toolName, {
        arguments: parsedArgs,
        maxAllowedTrustLevel: trustCap,
      });
      setLastDurationMs(Math.round(performance.now() - startedAt));
      setResult(callResult);
    } catch (callError) {
      setLastDurationMs(Math.round(performance.now() - startedAt));
      setRunError(callError instanceof Error ? callError.message : 'Tool call failed.');
    } finally {
      setRunning(false);
      onExecuted();
    }
  };

  if (enabledTools.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-border-subtle px-3 py-12 text-center text-[12px] text-on-surface-variant">
        No enabled tools available. Sync tools first, then come back to try them out.
      </div>
    );
  }

  return (
    <div className="grid gap-4 xl:grid-cols-2">
      <div className="flex flex-col gap-3">
        <label>
          <span className={labelClass}>Tool</span>
          <select
            data-testid="mcp-playground-tool"
            value={selectedTool?.toolName ?? ''}
            onChange={(event) => onSelectTool(event.target.value)}
            className={selectFieldClass}
          >
            {enabledTools.map((tool) => (
              <option key={tool.id} value={tool.toolName}>{tool.toolName}</option>
            ))}
          </select>
        </label>
        {selectedTool?.description && (
          <p className="text-[12px] leading-5 text-on-surface-variant">{selectedTool.description}</p>
        )}

        <div className="flex items-center gap-2 rounded-lg border border-secondary/25 bg-secondary/[0.05] px-3 py-2">
          <Shield size={14} className="shrink-0 text-secondary" />
          <span className="min-w-0 flex-1 text-[11.5px] leading-4 text-on-surface-variant">
            Execution cap — calls needing more trust are rejected before dispatch.
          </span>
          <select
            data-testid="mcp-playground-trust"
            value={trustCap}
            onChange={(event) => setTrustCap(event.target.value as McpTrustLevel)}
            aria-label="Execution cap"
            className="h-8 min-w-[132px] shrink-0 rounded-lg border border-border-subtle bg-surface-container-lowest px-2 pr-8 text-[12px] font-medium text-on-surface outline-none focus:border-primary/60"
          >
            {GRANTABLE_TRUST_LEVELS.map((level) => (
              <option key={level} value={level}>{trustLevelLabel(level)}</option>
            ))}
          </select>
        </div>

        <label>
          <span className={labelClass}>
            Arguments (JSON)
            <button
              type="button"
              onClick={() => selectedTool && setArgsText(argsTemplateFromSchema(selectedTool.inputSchema))}
              className="ml-auto text-[10.5px] text-secondary transition-colors hover:text-secondary-fixed"
            >
              Reset from schema
            </button>
          </span>
          <textarea
            data-testid="mcp-playground-args"
            value={argsText}
            onChange={(event) => setArgsText(event.target.value)}
            rows={10}
            spellCheck={false}
            className="w-full resize-y rounded-lg border border-border-subtle bg-surface-container-lowest px-3 py-2 font-mono-sm text-[11.5px] leading-[1.6] text-on-surface outline-none transition-colors focus:border-primary/60"
          />
        </label>

        {preflightWarning && (
          <div className="flex items-start gap-2 rounded-lg border border-primary/35 bg-primary/10 px-3 py-2 text-[12px] leading-5 text-primary">
            <ShieldAlert size={14} className="mt-0.5 shrink-0" />
            {preflightWarning}
          </div>
        )}

        <button
          type="button"
          data-testid="mcp-run-tool"
          onClick={run}
          disabled={running || !selectedTool}
          className="flex h-10 items-center justify-center gap-2 rounded-lg border border-primary bg-primary px-4 text-[13px] font-semibold text-on-primary shadow-[0_16px_42px_rgba(242,121,90,0.24)] transition-colors hover:bg-primary-fixed-dim disabled:cursor-not-allowed disabled:opacity-50"
        >
          {running ? <Loader2 size={15} className="animate-spin" /> : <Play size={15} />}
          {running ? 'Running...' : 'Run tool'}
        </button>
      </div>

      <div className="flex min-w-0 flex-col gap-3">
        <div className="flex items-center gap-2">
          <span className="text-label-caps font-label-caps text-on-surface-variant">Result</span>
          {lastDurationMs !== null && (
            <span className="font-mono-sm text-[11px] text-on-surface-variant">{formatDuration(lastDurationMs)}</span>
          )}
          {result && (
            <span className={`rounded-md border px-1.5 py-0.5 font-mono-sm text-[10px] ${
              result.isError
                ? 'border-status-error/35 bg-status-error/10 text-status-error'
                : 'border-secondary/35 bg-secondary/10 text-secondary'
            }`}>
              {result.isError ? 'TOOL ERROR' : 'SUCCESS'}
            </span>
          )}
        </div>

        {runError && <ErrorNote message={runError} />}

        {result ? (
          <div className="flex min-w-0 flex-col gap-3">
            {(result.content ?? []).map((block, index) => (
              <pre
                key={index}
                className="max-h-72 overflow-auto rounded-lg border border-border-subtle bg-surface-container-lowest/85 p-3 font-mono-sm text-[11px] leading-[1.6] text-on-surface"
              >
                {block.type === 'text' ? block.text : prettyJson(block)}
              </pre>
            ))}
            {result.structuredContent !== undefined && result.structuredContent !== null && (
              <div data-testid="mcp-playground-result">
                <JsonBlock label="Structured content" value={prettyJson(result.structuredContent)} />
              </div>
            )}
            {(result.content ?? []).length === 0 && result.structuredContent == null && (
              <div className="rounded-lg border border-dashed border-border-subtle px-3 py-8 text-center text-[12px] text-on-surface-variant">
                The tool returned no content blocks.
              </div>
            )}
          </div>
        ) : !runError ? (
          <div className="flex flex-1 items-center justify-center rounded-lg border border-dashed border-border-subtle px-3 py-12 text-center text-[12px] text-on-surface-variant">
            Run the tool to see its result here. Every call is recorded under Executions.
          </div>
        ) : null}
      </div>
    </div>
  );
}

function ServerDetail({
  server,
  onBack,
  onEdit,
  onServerUpdated,
}: {
  server: McpServerDTO;
  onBack: () => void;
  onEdit: () => void;
  onServerUpdated: (server: McpServerDTO) => void;
}) {
  const [activeTab, setActiveTab] = useState<DetailTab>('tools');
  const [tools, setTools] = useState<McpToolDTO[]>([]);
  const [toolsLoading, setToolsLoading] = useState(true);
  const [toolsError, setToolsError] = useState<string | null>(null);
  const [executions, setExecutions] = useState<McpToolExecutionDTO[]>([]);
  const [executionsLoading, setExecutionsLoading] = useState(true);
  const [executionsError, setExecutionsError] = useState<string | null>(null);
  const [syncResult, setSyncResult] = useState<McpToolSyncResultDTO | null>(null);
  const [syncBusy, setSyncBusy] = useState(false);
  const [statusBusy, setStatusBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [playgroundTool, setPlaygroundTool] = useState('');

  const loadTools = useCallback(() => {
    setToolsLoading(true);
    setToolsError(null);
    listMcpKnownTools(server.serverId)
      .then(setTools)
      .catch((error) => setToolsError(error instanceof Error ? error.message : 'Failed to load tools.'))
      .finally(() => setToolsLoading(false));
  }, [server.serverId]);

  const loadExecutions = useCallback(() => {
    setExecutionsLoading(true);
    setExecutionsError(null);
    listMcpExecutions(server.serverId)
      .then(setExecutions)
      .catch((error) => setExecutionsError(error instanceof Error ? error.message : 'Failed to load executions.'))
      .finally(() => setExecutionsLoading(false));
  }, [server.serverId]);

  useEffect(() => {
    setActiveTab('tools');
    setSyncResult(null);
    setActionError(null);
    setPlaygroundTool('');
    loadTools();
    loadExecutions();
    getMcpServer(server.serverId).then(onServerUpdated).catch(() => {
      // The list copy stays authoritative when the refresh fails.
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [server.serverId]);

  const syncTools = async () => {
    setSyncBusy(true);
    setActionError(null);
    try {
      const result = await syncMcpTools(server.serverId);
      setSyncResult(result);
      setTools(result.tools);
    } catch (error) {
      setActionError(error instanceof Error ? error.message : 'Tool sync failed.');
    } finally {
      setSyncBusy(false);
    }
  };

  const toggleStatus = async () => {
    setStatusBusy(true);
    setActionError(null);
    try {
      const updated = await updateMcpServerStatus(
        server.serverId,
        server.status === 'ENABLED' ? 'DISABLED' : 'ENABLED',
      );
      onServerUpdated(updated);
    } catch (error) {
      setActionError(error instanceof Error ? error.message : 'Status update failed.');
    } finally {
      setStatusBusy(false);
    }
  };

  const openPlayground = (toolName: string) => {
    setPlaygroundTool(toolName);
    setActiveTab('playground');
  };

  const enabledToolCount = tools.filter((tool) => tool.enabled).length;
  const lastSeenAt = tools.reduce<string | null>(
    (latest, tool) => (!latest || tool.lastSeenAt > latest ? tool.lastSeenAt : latest),
    null,
  );
  const resourceUri = mcpResource(server.serverId);
  const tabs: Array<{ id: DetailTab; label: string; icon: ReactNode }> = [
    { id: 'tools', label: 'Tools', icon: <Wrench size={14} /> },
    { id: 'executions', label: 'Executions', icon: <Activity size={14} /> },
    { id: 'playground', label: 'Playground', icon: <Play size={14} /> },
  ];

  const copyResource = async () => {
    try {
      await navigator.clipboard.writeText(resourceUri);
    } catch {
      // Clipboard failure should not block the detail view.
    }
  };

  return (
    <div className="flex h-full min-h-0 w-full flex-1 flex-col bg-[radial-gradient(ellipse_at_58%_0%,rgba(242,121,90,0.09),transparent_32%),linear-gradient(180deg,#081421,#050b13_64%,#040912)]">
      <div className="flex h-16 shrink-0 items-center justify-between gap-3 border-b border-border-subtle/80 px-5">
        <div className="flex min-w-0 items-center gap-2">
          <button
            type="button"
            onClick={onBack}
            className="flex shrink-0 items-center gap-1.5 text-[14px] font-semibold text-on-surface-variant transition-colors hover:text-on-surface"
            title="Back to MCP servers"
          >
            <ArrowLeft size={14} />
            MCP Servers
          </button>
          <span className="text-on-surface-variant/60">/</span>
          <span className="min-w-0 truncate text-[14px] font-semibold text-on-surface" aria-current="page">
            {server.displayName}
          </span>
          <ServerStatusChip status={server.status} />
          <TrustBadge level={server.trustLevel} />
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <button
            type="button"
            data-testid="mcp-toggle-status"
            onClick={toggleStatus}
            disabled={statusBusy}
            className={`flex h-9 items-center gap-2 rounded-lg border px-3 text-[12px] transition-colors disabled:opacity-50 ${
              server.status === 'ENABLED'
                ? 'border-border-subtle text-on-surface-variant hover:border-status-error/45 hover:text-status-error'
                : 'border-secondary/45 bg-secondary/10 text-secondary hover:bg-secondary/20'
            }`}
            title={server.status === 'ENABLED' ? 'Disable server' : 'Enable server'}
          >
            {statusBusy ? <Loader2 size={13} className="animate-spin" /> : <Power size={13} />}
            {server.status === 'ENABLED' ? 'Disable' : 'Enable'}
          </button>
          <button
            type="button"
            data-testid="mcp-edit-server"
            onClick={onEdit}
            className="flex h-9 items-center gap-2 rounded-lg border border-border-subtle px-3 text-[12px] text-on-surface-variant transition-colors hover:border-primary/45 hover:text-on-surface"
          >
            <Pencil size={13} />
            Edit
          </button>
          <button
            type="button"
            data-testid="mcp-sync-tools"
            onClick={syncTools}
            disabled={syncBusy}
            className="flex h-9 items-center gap-2 rounded-lg border border-primary bg-primary px-3 text-[12px] font-semibold text-on-primary shadow-[0_16px_42px_rgba(242,121,90,0.24)] transition-colors hover:bg-primary-fixed-dim disabled:cursor-not-allowed disabled:opacity-50"
          >
            {syncBusy ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
            Sync tools
          </button>
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">
        {actionError && (
          <div className="mb-3">
            <ErrorNote message={actionError} />
          </div>
        )}

        <section className="grid gap-3 xl:grid-cols-4">
          <SummaryCard
            icon={<Wrench size={22} />}
            label="Known tools"
            value={toolsLoading ? '...' : `${tools.length} (${enabledToolCount} enabled)`}
          />
          <SummaryCard
            icon={<Activity size={22} />}
            label="Recorded executions"
            value={executionsLoading ? '...' : executions.length}
          />
          <SummaryCard icon={<Shield size={22} />} label="Trust level" value={server.trustLevel} />
          <SummaryCard
            icon={<Clock3 size={22} />}
            label="Tools last seen"
            value={lastSeenAt ? formatUpdated(lastSeenAt).replace('Updated ', '') : 'Never synced'}
          />
        </section>

        <section className="mt-4 rounded-lg border border-primary/55 bg-[radial-gradient(circle_at_7%_30%,rgba(242,121,90,0.2),transparent_18%),linear-gradient(135deg,rgba(36,28,32,0.84),rgba(8,17,29,0.88)_42%,rgba(5,12,21,0.94))] p-5 shadow-[0_22px_70px_rgba(0,0,0,0.26)]">
          <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_390px]">
            <div className="flex min-w-0 gap-4">
              <div className="flex h-20 w-20 shrink-0 items-center justify-center rounded-full border border-primary/25 bg-primary/10 text-primary shadow-[0_0_40px_rgba(242,121,90,0.18)]">
                <Plug size={38} strokeWidth={1.8} />
              </div>
              <div className="min-w-0 pt-1">
                <h2 className="truncate text-[22px] font-semibold tracking-[-0.01em] text-on-surface">{server.displayName}</h2>
                <div className="mt-1 truncate font-mono-sm text-[13px] text-on-surface">
                  {server.transport === 'STDIO' ? (
                    serverAddress(server)
                  ) : (
                    <>{server.baseUrl}<span className="text-secondary">{server.endpoint}</span></>
                  )}
                </div>
                <p className="mt-2 max-w-3xl text-[13px] leading-5 text-on-surface-variant">
                  Registered {formatDateTime(server.createdAt)} · {formatUpdated(server.updatedAt).toLowerCase()}
                </p>
                <div className="mt-4 flex flex-wrap gap-2">
                  <HeroChip icon={<Globe size={13} />} label={`${server.transport} transport`} />
                  <HeroChip
                    icon={<KeyRound size={13} />}
                    label={server.authType === 'NONE'
                      ? 'No auth'
                      : server.authType === 'CUSTOM_HEADERS'
                        ? `${authTypeLabel(server.authType)} · ${server.secretHeaderNames.length}`
                        : <span>{authTypeLabel(server.authType)}{server.hasAuthToken ? '' : ' · no token'}</span>}
                  />
                  <HeroChip
                    icon={<Timer size={13} />}
                    label={server.requestTimeoutMs ? `${server.requestTimeoutMs.toLocaleString()} ms timeout` : 'Default timeout'}
                  />
                </div>
              </div>
            </div>
            <div className="flex flex-col justify-center lg:items-end">
              <div className="w-full max-w-[380px]">
                <div className="mb-2 text-[12px] text-on-surface-variant">Task resource URI</div>
                <button
                  type="button"
                  onClick={copyResource}
                  className="flex h-10 w-full min-w-0 items-center gap-2 rounded-lg border border-border-subtle bg-surface-container-lowest/85 px-3 font-mono-sm text-[12px] text-on-surface transition-colors hover:border-primary/45"
                  title="Copy MCP task resource URI"
                >
                  <span className="min-w-0 flex-1 truncate text-left">{resourceUri}</span>
                  <Copy size={14} className="shrink-0 text-on-surface-variant" />
                </button>
                <div className="mt-2 text-[10.5px] leading-4 text-on-surface-variant/70">
                  Append <span className="font-mono-sm">?trust=WRITE</span> in a Task state to grant more than READ_ONLY.
                </div>
              </div>
            </div>
          </div>
        </section>

        <section className="mt-4">
          <div className="flex h-12 items-center gap-6 border-b border-border-subtle">
            {tabs.map((tab) => (
              <button
                key={tab.id}
                type="button"
                data-testid={`mcp-tab-${tab.id}`}
                onClick={() => setActiveTab(tab.id)}
                className={`flex h-full items-center gap-2 border-b-2 text-[13px] transition-colors ${
                  activeTab === tab.id
                    ? 'border-primary text-primary'
                    : 'border-transparent text-on-surface-variant hover:text-on-surface'
                }`}
              >
                {tab.icon}
                {tab.label}
              </button>
            ))}
          </div>

          <div className="py-4">
            {activeTab === 'tools' && (
              <ToolsPanel
                server={server}
                tools={tools}
                loading={toolsLoading}
                error={toolsError}
                syncResult={syncResult}
                onDismissSyncResult={() => setSyncResult(null)}
                onRunTool={openPlayground}
                onReload={loadTools}
              />
            )}
            {activeTab === 'executions' && (
              <ExecutionsPanel
                executions={executions}
                loading={executionsLoading}
                error={executionsError}
                onRefresh={loadExecutions}
              />
            )}
            {activeTab === 'playground' && (
              <PlaygroundPanel
                server={server}
                tools={tools}
                selectedToolName={playgroundTool}
                onSelectTool={setPlaygroundTool}
                onExecuted={loadExecutions}
              />
            )}
          </div>
        </section>
      </div>
    </div>
  );
}

function ServerList({
  servers,
  loading,
  error,
  onRetry,
  onSelect,
  onRegister,
  onRefresh,
}: {
  servers: McpServerDTO[];
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  onSelect: (serverId: string) => void;
  onRegister: () => void;
  onRefresh: () => void;
}) {
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');

  const enabledCount = servers.filter((server) => server.status === 'ENABLED').length;
  const disabledCount = servers.length - enabledCount;
  const writeCapableCount = servers.filter(
    (server) => server.trustLevel === 'WRITE' || server.trustLevel === 'DESTRUCTIVE',
  ).length;

  const visible = servers.filter((server) => {
    if (statusFilter !== 'ALL' && server.status !== statusFilter) return false;
    const needle = search.trim().toLowerCase();
    if (!needle) return true;
    return (
      server.displayName.toLowerCase().includes(needle)
      || server.serverId.toLowerCase().includes(needle)
      || serverAddress(server).toLowerCase().includes(needle)
    );
  });

  const filterButton = (filter: StatusFilter, label: string, count: number) => (
    <button
      type="button"
      onClick={() => setStatusFilter(filter)}
      className={`flex h-9 items-center gap-2 rounded-lg border px-3 text-[12px] transition-colors ${
        statusFilter === filter
          ? 'border-primary/55 bg-primary/10 text-primary'
          : 'border-border-subtle text-on-surface-variant hover:border-primary/45 hover:text-on-surface'
      }`}
    >
      {label}
      <span className="font-mono-sm text-[10px] opacity-80">{count}</span>
    </button>
  );

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center gap-2 text-[12px] text-on-surface-variant">
        <Loader2 size={16} className="animate-spin" /> Loading MCP servers...
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex h-full items-center justify-center p-6">
        <div className="w-full max-w-md rounded-lg border border-border-subtle bg-surface-container-lowest/60 p-6 text-center">
          <AlertTriangle size={22} className="mx-auto text-status-error" />
          <div className="mt-3 text-[14px] font-semibold text-on-surface">MCP API unavailable</div>
          <p className="mt-1.5 break-words text-[12px] leading-5 text-on-surface-variant">{error}</p>
          <button
            type="button"
            onClick={onRetry}
            className="mt-4 inline-flex h-9 items-center gap-2 rounded-lg border border-primary bg-primary px-4 text-[12px] font-semibold text-on-primary transition-colors hover:bg-primary-fixed-dim"
          >
            <RefreshCw size={13} />
            Retry
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 w-full flex-1 flex-col">
      <div className="glass-shell grid shrink-0 grid-cols-1 border-b border-border-subtle bg-surface-base md:grid-cols-4">
        <McpMetric label="Registered servers" value={servers.length} tone="text-primary" />
        <McpMetric label="Enabled" value={enabledCount} tone="text-status-success" />
        <McpMetric label="Disabled" value={disabledCount} tone="text-on-surface-variant" />
        <McpMetric label="Write-capable" value={writeCapableCount} tone="text-status-warning" />
      </div>

      <div className="flex shrink-0 flex-wrap items-center gap-2 border-b border-border-subtle bg-surface-base/60 px-5 py-3">
        <label className="flex h-9 min-w-[220px] flex-1 items-center gap-2 rounded-lg border border-border-subtle bg-surface-container-lowest px-3">
          <Search size={14} className="text-on-surface-variant" />
            <input
            data-testid="mcp-search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search servers..."
            className="min-w-0 flex-1 border-none bg-transparent text-[12px] text-on-surface outline-none placeholder:text-on-surface-variant/55"
          />
        </label>
        {filterButton('ALL', 'All', servers.length)}
        {filterButton('ENABLED', 'Enabled', enabledCount)}
        {filterButton('DISABLED', 'Disabled', disabledCount)}
        <button
          type="button"
          data-testid="mcp-register-open"
          onClick={onRegister}
          className="flex h-9 items-center gap-2 rounded-lg border border-primary/45 bg-primary px-3 text-[13px] font-semibold text-on-primary shadow-[0_16px_42px_rgba(242,121,90,0.24)] transition-colors hover:bg-primary-fixed-dim"
        >
          <Plus size={16} />
          Register server
        </button>
        <button
          type="button"
          onClick={onRefresh}
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-border-subtle text-on-surface-variant transition-colors hover:border-primary/45 hover:text-on-surface"
          title="Refresh servers"
        >
          <RefreshCw size={14} />
        </button>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto bg-[linear-gradient(180deg,#081421,#050b13)] p-5">
        {visible.length === 0 ? (
          <div className="rounded-lg border border-dashed border-border-subtle px-3 py-16 text-center text-[12px] text-on-surface-variant">
            {servers.length === 0
              ? 'No MCP servers registered yet. Register one to make its tools available to workflows.'
              : 'No servers match the current filters.'}
          </div>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {visible.map((server) => (
              <button
                key={server.id}
                type="button"
                data-testid={`mcp-server-card-${server.serverId}`}
                data-mcp-server-id={server.serverId}
                data-mcp-status={server.status}
                onClick={() => onSelect(server.serverId)}
                className="group flex flex-col rounded-lg border border-border-subtle bg-surface-container-lowest/60 p-4 text-left transition-colors hover:border-primary/55 hover:bg-[linear-gradient(145deg,rgba(242,121,90,0.1),rgba(14,23,34,0.78))]"
              >
                <div className="flex items-center gap-2">
                  <span className={`h-2 w-2 shrink-0 rounded-full ${
                    server.status === 'ENABLED' ? 'bg-secondary' : 'bg-on-surface-variant/45'
                  }`} />
                  <span className="min-w-0 flex-1 truncate text-[14px] font-semibold text-on-surface">
                    {server.displayName}
                  </span>
                  <TrustBadge level={server.trustLevel} showLabel={false} />
                </div>
                <div className="mt-2 truncate font-mono-sm text-[11px] text-on-surface-variant">{server.serverId}</div>
                <div className="truncate font-mono-sm text-[11px] text-on-surface-variant/70">
                  {serverAddress(server)}
                </div>
                <div className="mt-3 flex flex-wrap items-center gap-1.5">
                  <span className="rounded-md border border-border-subtle bg-surface-container-low px-1.5 py-0.5 font-mono-sm text-[9px] uppercase tracking-[0.06em] text-on-surface-variant">
                    {server.transport}
                  </span>
                  <span className={`rounded-md border px-1.5 py-0.5 font-mono-sm text-[9px] uppercase tracking-[0.06em] ${
                    server.authType !== 'NONE'
                      ? 'border-status-info/35 bg-status-info/10 text-status-info'
                      : 'border-border-subtle bg-surface-container-low text-on-surface-variant'
                  }`}>
                    {authTypeLabel(server.authType)}
                  </span>
                  <span className={`ml-auto font-mono-sm text-[10px] ${TRUST_META[server.trustLevel].text}`}>
                    {server.trustLevel}
                  </span>
                </div>
                <div className="mt-2 font-mono-sm text-[11px] text-on-surface-variant/70">{formatUpdated(server.updatedAt)}</div>
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

export function McpServersPage() {
  const [servers, setServers] = useState<McpServerDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedServerId, setSelectedServerId] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);

  const loadServers = useCallback(() => {
    setLoading(true);
    setError(null);
    listMcpServers()
      .then(setServers)
      .catch((loadError) => setError(loadError instanceof Error ? loadError.message : 'Failed to load MCP servers.'))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    loadServers();
  }, [loadServers]);

  const selectedServer = useMemo(
    () => servers.find((server) => server.serverId === selectedServerId) ?? null,
    [servers, selectedServerId],
  );

  const upsertServer = useCallback((saved: McpServerDTO) => {
    setServers((current) => {
      const index = current.findIndex((server) => server.serverId === saved.serverId);
      if (index === -1) return [saved, ...current];
      const next = [...current];
      next[index] = saved;
      return next;
    });
  }, []);

  return (
    <div className="flex h-full min-h-0 w-full flex-1 flex-col">
      {selectedServer ? (
        <ServerDetail
          server={selectedServer}
          onBack={() => setSelectedServerId(null)}
          onEdit={() => setEditOpen(true)}
          onServerUpdated={upsertServer}
        />
      ) : (
        <ServerList
          servers={servers}
          loading={loading}
          error={error}
          onRetry={loadServers}
          onSelect={setSelectedServerId}
          onRegister={() => setCreateOpen(true)}
          onRefresh={loadServers}
        />
      )}

      {createOpen && (
        <ServerFormModal
          mode="create"
          onCancel={() => setCreateOpen(false)}
          onSaved={(saved) => {
            upsertServer(saved);
            setCreateOpen(false);
            setSelectedServerId(saved.serverId);
          }}
        />
      )}
      {editOpen && selectedServer && (
        <ServerFormModal
          mode="edit"
          initial={selectedServer}
          onCancel={() => setEditOpen(false)}
          onSaved={(saved) => {
            upsertServer(saved);
            setEditOpen(false);
          }}
        />
      )}
    </div>
  );
}
