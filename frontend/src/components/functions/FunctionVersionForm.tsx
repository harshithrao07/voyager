import { useEffect, useMemo, useState } from 'react';
import { Loader2 } from 'lucide-react';
import {
  createFunctionVersion,
  type FunctionLanguageDTO,
  type FunctionSourceMode,
  type FunctionVersionDTO,
  type FunctionVersionRequest,
} from '../../api';

const field =
  'h-9 w-full rounded-lg border border-border-subtle bg-surface-container-lowest px-3 font-mono-sm text-[12px] text-on-surface outline-none transition-colors placeholder:text-on-surface-variant/45 focus:border-primary/50';
const label = 'mb-1 block font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant/70';

function toNumber(value: string): number | undefined {
  if (value.trim() === '') return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

type Props = {
  functionId: string;
  languages: FunctionLanguageDTO[];
  onCreated: (version: FunctionVersionDTO) => void;
  onCancel: () => void;
};

export function FunctionVersionForm({ functionId, languages, onCreated, onCancel }: Props) {
  const [sourceMode, setSourceMode] = useState<FunctionSourceMode>('SINGLE_FILE');
  const [languageId, setLanguageId] = useState('');
  const [sourceCode, setSourceCode] = useState('');
  const [additionalFilesBase64, setAdditionalFilesBase64] = useState('');
  const [compilerOptions, setCompilerOptions] = useState('');
  const [commandLineArguments, setCommandLineArguments] = useState('');
  const [cpu, setCpu] = useState('');
  const [wall, setWall] = useState('');
  const [memory, setMemory] = useState('');
  const [fileSize, setFileSize] = useState('');
  const [outputBytes, setOutputBytes] = useState('');
  const [enableNetwork, setEnableNetwork] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const sortedLanguages = useMemo(
    () => [...languages]
      .filter((language) => sourceMode === 'SINGLE_FILE' || language.multiFileSupported)
      .sort((a, b) => a.name.localeCompare(b.name)),
    [languages, sourceMode],
  );
  const selectedLanguage = languages.find((language) => String(language.id) === languageId);

  useEffect(() => {
    if (sourceMode === 'MULTI_FILE' && selectedLanguage && !selectedLanguage.multiFileSupported) {
      setLanguageId('');
    }
  }, [selectedLanguage, sourceMode]);

  const canSave =
    languageId !== '' &&
    (sourceMode === 'SINGLE_FILE' || Boolean(selectedLanguage?.multiFileSupported)) &&
    (sourceMode === 'SINGLE_FILE' ? sourceCode.trim() !== '' : additionalFilesBase64.trim() !== '');

  const submit = async () => {
    if (!canSave || saving) return;
    setSaving(true);
    setError(null);
    const request: FunctionVersionRequest = {
      sourceMode,
      languageId: Number(languageId),
      sourceCode: sourceMode === 'SINGLE_FILE' ? sourceCode : undefined,
      additionalFilesBase64: sourceMode === 'MULTI_FILE' ? additionalFilesBase64.trim() : undefined,
      compilerOptions: compilerOptions.trim() || undefined,
      commandLineArguments: commandLineArguments.trim() || undefined,
      cpuTimeLimitSeconds: toNumber(cpu),
      wallTimeLimitSeconds: toNumber(wall),
      memoryLimitKb: toNumber(memory),
      maxFileSizeKb: toNumber(fileSize),
      maxOutputBytes: toNumber(outputBytes),
      enableNetwork,
    };
    try {
      onCreated(await createFunctionVersion(functionId, request));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="rounded-xl border border-border-subtle bg-surface-container-lowest/40 p-4">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="font-mono-sm text-[12px] font-semibold text-on-surface">New version</h3>
        <button
          type="button"
          onClick={onCancel}
          className="font-mono-sm text-[11px] text-on-surface-variant transition-colors hover:text-on-surface"
        >
          Cancel
        </button>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <span className={label}>Language</span>
          <select
            value={languageId}
            onChange={(event) => setLanguageId(event.target.value)}
            className={field}
          >
            <option value="">Select language…</option>
            {sortedLanguages.map((language) => (
              <option key={language.id} value={language.id}>
                {language.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <span className={label}>Source mode</span>
          <select
            value={sourceMode}
            onChange={(event) => setSourceMode(event.target.value as FunctionSourceMode)}
            className={field}
          >
            <option value="SINGLE_FILE">Single file</option>
            <option value="MULTI_FILE" disabled={!languages.some((language) => language.multiFileSupported)}>
              Multi file (zip)
            </option>
          </select>
        </div>
      </div>

      <div className="mt-3">
        {sourceMode === 'SINGLE_FILE' ? (
          <>
            <span className={label}>Source code (reads JSON on stdin, writes JSON to stdout)</span>
            <textarea
              value={sourceCode}
              onChange={(event) => setSourceCode(event.target.value)}
              spellCheck={false}
              placeholder={'import json, sys\ndata = json.load(sys.stdin)\nprint(json.dumps({"ok": True}))'}
              className="min-h-[200px] w-full resize-y rounded-lg border border-border-subtle bg-surface-container-lowest p-3 font-mono-sm text-[12px] leading-relaxed text-on-surface outline-none transition-colors placeholder:text-on-surface-variant/40 focus:border-primary/50"
            />
          </>
        ) : (
          <>
            <span className={label}>Additional files (base64-encoded zip bundle)</span>
            <textarea
              value={additionalFilesBase64}
              onChange={(event) => setAdditionalFilesBase64(event.target.value)}
              spellCheck={false}
              placeholder="UEsDBBQAAAAI…"
              className="min-h-[140px] w-full resize-y rounded-lg border border-border-subtle bg-surface-container-lowest p-3 font-mono-sm text-[11px] leading-relaxed text-on-surface outline-none transition-colors placeholder:text-on-surface-variant/40 focus:border-primary/50"
            />
          </>
        )}
      </div>

      <div className="mt-3 grid grid-cols-2 gap-3">
        <div>
          <span className={label}>Compiler options</span>
          <input value={compilerOptions} onChange={(e) => setCompilerOptions(e.target.value)} className={field} placeholder="optional" />
        </div>
        <div>
          <span className={label}>Command-line arguments</span>
          <input value={commandLineArguments} onChange={(e) => setCommandLineArguments(e.target.value)} className={field} placeholder="optional" />
        </div>
      </div>

      <div className="mt-3">
        <span className={label}>Limits (blank = server default, capped by Judge0)</span>
        <div className="grid grid-cols-2 gap-2 md:grid-cols-3">
          <LimitInput label="CPU s" value={cpu} onChange={setCpu} placeholder="2.0" />
          <LimitInput label="Wall s" value={wall} onChange={setWall} placeholder="10.0" />
          <LimitInput label="Memory KB" value={memory} onChange={setMemory} placeholder="131072" />
          <LimitInput label="Max file KB" value={fileSize} onChange={setFileSize} placeholder="1024" />
          <LimitInput label="Max output B" value={outputBytes} onChange={setOutputBytes} placeholder="65536" />
          <label className="flex items-center gap-2 rounded-lg border border-border-subtle bg-surface-container-lowest px-3 py-2">
            <input type="checkbox" checked={enableNetwork} onChange={(e) => setEnableNetwork(e.target.checked)} className="accent-primary" />
            <span className="font-mono-sm text-[11px] text-on-surface-variant">Enable network</span>
          </label>
        </div>
      </div>

      {error && (
        <div className="mt-3 rounded-lg border border-status-error/35 bg-status-error/5 px-3 py-2 font-mono-sm text-[11px] text-status-error">
          {error}
        </div>
      )}

      <div className="mt-4 flex justify-end">
        <button
          type="button"
          onClick={submit}
          disabled={!canSave || saving}
          className="flex h-9 items-center gap-2 rounded-lg border border-primary bg-primary px-4 font-body-sm text-body-sm font-medium text-on-primary transition-colors hover:bg-primary-fixed-dim disabled:cursor-not-allowed disabled:opacity-50"
        >
          {saving && <Loader2 size={15} className="animate-spin" />}
          Publish version
        </button>
      </div>
    </div>
  );
}

function LimitInput({ label: text, value, onChange, placeholder }: { label: string; value: string; onChange: (v: string) => void; placeholder: string }) {
  return (
    <label className="rounded-lg border border-border-subtle bg-surface-container-lowest px-3 py-1.5">
      <span className="block font-mono-sm text-[9px] uppercase tracking-[0.06em] text-on-surface-variant/60">{text}</span>
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        inputMode="decimal"
        className="w-full bg-transparent font-mono-sm text-[12px] text-on-surface outline-none placeholder:text-on-surface-variant/35"
      />
    </label>
  );
}
