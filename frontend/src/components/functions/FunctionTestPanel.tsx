import { useEffect, useState } from 'react';
import { CheckCircle2, Loader2, Play, PlayCircle, Plus, RotateCcw, Save, Trash2, XCircle } from 'lucide-react';
import { executeFunctionCode, type FunctionRunResult, type FunctionTestCase, type FunctionVersionDTO } from '../../api';

type Props = {
  versions: FunctionVersionDTO[];
  activeVersion: number | null;
  onSaveTestCases?: (version: FunctionVersionDTO, testCases: FunctionTestCase[]) => Promise<void>;
};

type TestCase = {
  id: string;
  name: string;
  input: string;
  expectedOutput: string;
  expectedError: string;
  checked: boolean;
};

type CaseStatus = 'pass' | 'fail' | 'none';

const statusTone: Record<string, string> = {
  SUCCEEDED: 'border-secondary/40 bg-secondary/10 text-secondary',
  FAILED: 'border-status-error/40 bg-status-error/10 text-status-error',
  RUNNING: 'border-status-info/40 bg-status-info/10 text-status-info',
};

function newCase(index: number): TestCase {
  return {
    id: crypto.randomUUID(),
    name: `Case ${index}`,
    input: '{\n  "amount": 100\n}',
    expectedOutput: '',
    expectedError: '',
    checked: true,
  };
}

// Load the cases saved with a version into the editor. Falls back to a single
// blank case when the version has none, so the panel is always runnable.
function seedCasesFromVersion(version?: FunctionVersionDTO): TestCase[] {
  const saved = version?.testCases ?? [];
  if (saved.length === 0) return [newCase(1)];
  return saved.map((testCase, index) => ({
    id: crypto.randomUUID(),
    name: testCase.name?.trim() || `Case ${index + 1}`,
    input: testCase.input ?? '',
    expectedOutput: testCase.expectedOutput ?? '',
    expectedError: testCase.expectedError ?? '',
    checked: true,
  }));
}

function toSavedTestCases(cases: TestCase[]): FunctionTestCase[] {
  return cases.map((testCase, index) => ({
    name: testCase.name.trim() || `Case ${index + 1}`,
    input: testCase.input,
    expectedOutput: testCase.expectedOutput,
    expectedError: testCase.expectedError,
  }));
}

function testCaseSignature(cases: TestCase[]) {
  return JSON.stringify(toSavedTestCases(cases));
}

function requireJsonField(testCase: TestCase, field: 'input' | 'expectedOutput', label: string, allowBlank = false) {
  const value = testCase[field].trim();
  if (!value) {
    if (allowBlank) return;
    throw new Error(`${testCase.name || 'Test case'}: ${label} must be valid JSON.`);
  }
  try {
    JSON.parse(value);
  } catch {
    throw new Error(`${testCase.name || 'Test case'}: ${label} must be valid JSON.`);
  }
}

function validateTestCaseJson(testCase: TestCase) {
  requireJsonField(testCase, 'input', 'input');
  requireJsonField(testCase, 'expectedOutput', 'expected output', true);
}

export function FunctionTestPanel({ versions, activeVersion, onSaveTestCases }: Props) {
  const [versionChoice, setVersionChoice] = useState('active');

  const runnableVersions = versions.filter((version) => version.status === 'AVAILABLE');
  const selectedVersion = versionChoice === 'active'
    ? runnableVersions.find((version) => version.version === activeVersion)
    : runnableVersions.find((version) => version.version === Number(versionChoice));
  const selectedVersionNumber = selectedVersion?.version ?? null;

  const [cases, setCases] = useState<TestCase[]>(() => seedCasesFromVersion(selectedVersion));
  const [activeCaseId, setActiveCaseId] = useState(() => cases[0]?.id || '');
  const [runningCaseId, setRunningCaseId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [savedMessage, setSavedMessage] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [results, setResults] = useState<Record<string, FunctionRunResult>>({});
  const [resultVersions, setResultVersions] = useState<Record<string, number>>({});
  const [savedSignature, setSavedSignature] = useState(() => testCaseSignature(cases));

  // Reload the editor with the selected version's saved cases whenever the
  // chosen version changes. Edits here are for the run only and are not
  // persisted, so switching versions discards them by design.
  useEffect(() => {
    const seeded = seedCasesFromVersion(selectedVersion);
    setCases(seeded);
    setActiveCaseId(seeded[0]?.id || '');
    setResults({});
    setResultVersions({});
    setError(null);
    setSavedMessage(null);
    setSavedSignature(testCaseSignature(seeded));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedVersionNumber]);

  const activeCase = cases.find((testCase) => testCase.id === activeCaseId) || cases[0];
  const checkedCases = cases.filter((testCase) => testCase.checked);
  const running = runningCaseId !== null;
  const currentSignature = testCaseSignature(cases);
  const hasUnsavedChanges = currentSignature !== savedSignature;
  const canSaveTests = Boolean(onSaveTestCases && selectedVersion) && !running && !saving && cases.length > 0 && hasUnsavedChanges;

  const caseStatus = (testCase: TestCase): CaseStatus => {
    const result = results[testCase.id];
    if (!result) return 'none';
    if (result.status !== 'SUCCEEDED') return 'fail';
    const expected = testCase.expectedOutput.trim();
    if (!expected) return 'pass';
    try {
      return JSON.stringify(JSON.parse(expected)) === JSON.stringify(result.output ?? null) ? 'pass' : 'fail';
    } catch {
      return (result.stdout || '').trim() === expected ? 'pass' : 'fail';
    }
  };

  const updateCase = (id: string, patch: Partial<TestCase>) => {
    setSavedMessage(null);
    setCases((current) => current.map((testCase) => (
      testCase.id === id ? { ...testCase, ...patch } : testCase
    )));
  };

  const addCase = () => {
    setSavedMessage(null);
    const next = newCase(cases.length + 1);
    setCases((current) => [...current, next]);
    setActiveCaseId(next.id);
  };

  const resetToSaved = () => {
    const seeded = seedCasesFromVersion(selectedVersion);
    setCases(seeded);
    setActiveCaseId(seeded[0]?.id || '');
    setResults({});
    setResultVersions({});
    setError(null);
    setSavedMessage(null);
    setSavedSignature(testCaseSignature(seeded));
  };

  const removeCase = (id: string) => {
    if (cases.length <= 1) return;
    setSavedMessage(null);
    setCases((current) => current.filter((testCase) => testCase.id !== id));
    setResults((current) => {
      const next = { ...current };
      delete next[id];
      return next;
    });
    setResultVersions((current) => {
      const next = { ...current };
      delete next[id];
      return next;
    });
    if (activeCaseId === id) {
      const nextCase = cases.find((testCase) => testCase.id !== id);
      setActiveCaseId(nextCase?.id || '');
    }
  };

  const validateSelectedVersion = () => {
    if (!selectedVersion) {
      throw new Error('Select an available version before checking tests.');
    }
    if (selectedVersion.sourceMode === 'SINGLE_FILE' && !selectedVersion.sourceCode?.trim()) {
      throw new Error(`v${selectedVersion.version} does not have source code to check.`);
    }
    if (selectedVersion.sourceMode === 'MULTI_FILE' && !selectedVersion.additionalFilesBase64?.trim()) {
      throw new Error(`v${selectedVersion.version} does not have a file bundle to check.`);
    }
    return selectedVersion;
  };

  const runCase = async (testCase: TestCase, version: FunctionVersionDTO) => {
    validateTestCaseJson(testCase);
    const input = JSON.parse(testCase.input.trim());
    setRunningCaseId(testCase.id);
    const runResult = await executeFunctionCode({
      languageId: version.languageId,
      sourceMode: version.sourceMode,
      sourceCode: version.sourceMode === 'SINGLE_FILE' ? version.sourceCode : undefined,
      additionalFilesBase64: version.sourceMode === 'MULTI_FILE' ? version.additionalFilesBase64 : undefined,
      compilerOptions: version.compilerOptions,
      commandLineArguments: version.commandLineArguments,
      cpuTimeLimitSeconds: version.cpuTimeLimitSeconds,
      wallTimeLimitSeconds: version.wallTimeLimitSeconds,
      memoryLimitKb: version.memoryLimitKb,
      maxFileSizeKb: version.maxFileSizeKb,
      maxOutputBytes: version.maxOutputBytes,
      enableNetwork: version.enableNetwork,
      input,
    });
    setResults((current) => ({ ...current, [testCase.id]: runResult }));
    setResultVersions((current) => ({ ...current, [testCase.id]: version.version }));
  };

  const runCases = async (list: TestCase[], emptyMessage: string) => {
    if (running) return;
    try {
      setError(null);
      const version = validateSelectedVersion();
      if (list.length === 0) {
        throw new Error(emptyMessage);
      }
      for (const testCase of list) {
        await runCase(testCase, version);
      }
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setRunningCaseId(null);
    }
  };

  const runAll = () => runCases(cases, 'Add at least one test case.');
  const runChecked = () => runCases(checkedCases, 'Check at least one test case.');
  const runSingle = (testCase: TestCase) => {
    setActiveCaseId(testCase.id);
    return runCases([testCase], 'Add at least one test case.');
  };

  const saveTests = async () => {
    if (!onSaveTestCases || !selectedVersion || saving) return;
    try {
      setSaving(true);
      setError(null);
      setSavedMessage(null);
      if (cases.length > 100) {
        throw new Error('A version may have at most 100 saved test cases.');
      }
      cases.forEach((testCase) => {
        if ((testCase.name || '').length > 200) {
          throw new Error(`${testCase.name || 'Test case'}: name must be 200 characters or less.`);
        }
        validateTestCaseJson(testCase);
      });
      const saved = toSavedTestCases(cases);
      await onSaveTestCases(selectedVersion, saved);
      setSavedSignature(JSON.stringify(saved));
      setSavedMessage(`Saved ${saved.length} test ${saved.length === 1 ? 'case' : 'cases'} to v${selectedVersion.version}.`);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const activeResult = activeCase ? results[activeCase.id] : null;
  const activeResultVersion = activeCase ? resultVersions[activeCase.id] : null;

  return (
    <div className="space-y-4">
      <div className="rounded-lg border border-border-subtle bg-surface-container-lowest/55 p-4">
        <div className="flex flex-wrap items-end gap-3">
          <label className="min-w-0 flex-1">
            <span className="mb-1.5 block font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant/70">Version</span>
            <select
              data-testid="function-test-version"
              value={versionChoice}
              onChange={(event) => setVersionChoice(event.target.value)}
              className="h-9 w-full rounded-lg border border-border-subtle bg-surface-container-lowest px-3 font-mono-sm text-[12px] text-on-surface outline-none focus:border-primary/50"
            >
              <option value="active">Current{activeVersion ? ` (v${activeVersion})` : ''}</option>
              {runnableVersions.map((version) => (
                <option key={version.id} value={version.version}>
                  v{version.version}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            data-testid="function-test-save"
            onClick={saveTests}
            disabled={!canSaveTests}
            className="flex h-9 items-center justify-center gap-2 rounded-lg border border-border-subtle px-4 text-[12px] font-semibold text-on-surface-variant transition-colors hover:border-primary/45 hover:text-on-surface disabled:cursor-not-allowed disabled:opacity-50"
            title="Save these test cases to the selected version"
          >
            {saving ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />}
            Save tests
          </button>
          <button
            type="button"
            data-testid="function-test-run-selected"
            onClick={runChecked}
            disabled={running}
            className="flex h-9 items-center justify-center gap-2 rounded-lg border border-border-subtle px-4 text-[12px] font-semibold text-on-surface-variant transition-colors hover:border-primary/45 hover:text-on-surface disabled:cursor-not-allowed disabled:opacity-50"
            title="Run only the checked test cases"
          >
            <Play size={14} />
            Run selected
          </button>
          <button
            type="button"
            data-testid="function-test-run-all"
            onClick={runAll}
            disabled={running}
            className="flex h-9 items-center justify-center gap-2 rounded-lg border border-primary bg-primary px-4 text-[12px] font-semibold text-on-primary transition-colors hover:bg-primary-fixed-dim disabled:cursor-not-allowed disabled:opacity-50"
            title="Run every test case"
          >
            {running ? <Loader2 size={15} className="animate-spin" /> : <PlayCircle size={15} />}
            Run all
          </button>
        </div>
        {error && <p className="mt-2 font-mono-sm text-[11px] text-status-error">{error}</p>}
        {savedMessage && <p className="mt-2 font-mono-sm text-[11px] text-secondary">{savedMessage}</p>}
      </div>

      <div className="grid gap-4 lg:grid-cols-[300px_minmax(0,1fr)]">
        <div className="rounded-lg border border-border-subtle bg-surface-container-lowest/55 p-3">
          <div className="mb-3 flex items-center justify-between gap-2">
            <span className="text-[13px] font-semibold text-on-surface">Test cases</span>
            <div className="flex items-center gap-1.5">
              {(selectedVersion?.testCases?.length ?? 0) > 0 && (
                <button
                  type="button"
                  onClick={resetToSaved}
                  disabled={running}
                  className="flex h-8 items-center gap-1.5 rounded-lg border border-border-subtle px-3 text-[12px] text-on-surface-variant transition-colors hover:border-primary/45 hover:text-on-surface disabled:opacity-40"
                  title="Discard edits and reload the cases saved with this version"
                >
                  <RotateCcw size={13} />
                  Reset to saved
                </button>
              )}
              <button
                type="button"
                data-testid="function-test-add-case"
                onClick={addCase}
                className="flex h-8 items-center gap-1.5 rounded-lg border border-primary/45 px-3 text-[12px] text-primary hover:bg-primary/10"
              >
                <Plus size={13} />
                Add
              </button>
            </div>
          </div>
          <div className="space-y-2">
            {cases.map((testCase) => {
              const active = activeCase?.id === testCase.id;
              const status = caseStatus(testCase);
              return (
                <div
                  key={testCase.id}
                  className={`flex items-center gap-2 rounded-lg border p-2.5 transition-colors ${
                    active
                      ? 'border-primary/55 bg-primary/10'
                      : 'border-border-subtle bg-surface-container-lowest/50 hover:border-primary/35'
                  }`}
                >
                <input
                  data-testid={`function-test-case-check-${testCase.id}`}
                    type="checkbox"
                    checked={testCase.checked}
                    onChange={(event) => updateCase(testCase.id, { checked: event.target.checked })}
                    className="h-4 w-4 rounded border-border-subtle bg-surface-container text-primary focus:ring-primary"
                  />
                  <button
                    type="button"
                    onClick={() => setActiveCaseId(testCase.id)}
                    className="min-w-0 flex-1 truncate text-left text-[12px] font-semibold text-on-surface"
                  >
                    {testCase.name}
                  </button>
                  {runningCaseId === testCase.id ? (
                    <Loader2 size={13} className="animate-spin text-primary" />
                  ) : status === 'pass' ? (
                    <CheckCircle2 size={13} className="text-secondary" />
                  ) : status === 'fail' ? (
                    <XCircle size={13} className="text-status-error" />
                  ) : null}
                  <button
                    type="button"
                    onClick={() => runSingle(testCase)}
                    disabled={running}
                    title="Run this case"
                    className="flex h-6 w-6 shrink-0 items-center justify-center rounded-md border border-border-subtle text-on-surface-variant transition-colors hover:border-primary/45 hover:text-primary disabled:opacity-40"
                  >
                    <Play size={12} />
                  </button>
                </div>
              );
            })}
          </div>
        </div>

        <div className="space-y-4">
          {activeCase && (
            <div className="rounded-lg border border-border-subtle bg-surface-container-lowest/55 p-4">
              <div className="mb-3 flex items-center justify-between gap-3">
                <input
                  data-testid="function-test-case-name"
                  value={activeCase.name}
                  onChange={(event) => updateCase(activeCase.id, { name: event.target.value })}
                  className="h-8 min-w-0 flex-1 rounded-md border border-border-subtle bg-surface-container-lowest px-2 text-[12px] font-semibold text-on-surface outline-none focus:border-primary/50"
                />
                <button
                  type="button"
                  onClick={() => runSingle(activeCase)}
                  disabled={running}
                  className="flex h-8 shrink-0 items-center gap-1.5 rounded-lg border border-primary/45 px-3 text-[12px] font-semibold text-primary transition-colors hover:bg-primary/10 disabled:opacity-40"
                  title="Run this case"
                >
                  {runningCaseId === activeCase.id ? <Loader2 size={13} className="animate-spin" /> : <Play size={13} />}
                  Run
                </button>
                <button
                  type="button"
                  onClick={() => removeCase(activeCase.id)}
                  disabled={cases.length <= 1}
                  className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-border-subtle text-on-surface-variant hover:border-status-error/45 hover:text-status-error disabled:opacity-40"
                  title="Remove test case"
                >
                  <Trash2 size={14} />
                </button>
              </div>
              <span className="mb-2 block font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant/70">Input JSON</span>
              <textarea
                data-testid="function-test-input"
                value={activeCase.input}
                onChange={(event) => updateCase(activeCase.id, { input: event.target.value })}
                spellCheck={false}
                className="min-h-[160px] w-full resize-y rounded-lg border border-border-subtle bg-surface-container-lowest p-3 font-mono-sm text-[12px] leading-relaxed text-on-surface outline-none transition-colors focus:border-primary/50"
              />
              <span className="mb-2 mt-3 block font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant/70">Expected output JSON</span>
              <textarea
                data-testid="function-test-expected-output"
                value={activeCase.expectedOutput}
                onChange={(event) => updateCase(activeCase.id, { expectedOutput: event.target.value })}
                spellCheck={false}
                placeholder="Leave blank to just check it runs"
                className="min-h-[90px] w-full resize-y rounded-lg border border-border-subtle bg-surface-container-lowest p-3 font-mono-sm text-[12px] leading-relaxed text-secondary outline-none transition-colors placeholder:text-on-surface-variant/40 focus:border-primary/50"
              />
            </div>
          )}

          <div className="rounded-lg border border-border-subtle bg-surface-container-lowest/55 p-4">
            <span className="mb-2 block font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant/70">Result</span>
            {!activeResult ? (
              <div className="flex min-h-[220px] items-center justify-center rounded-lg border border-dashed border-border-subtle text-body-sm text-on-surface-variant/70">
                Select cases and check them to see output
              </div>
            ) : (
              <div className="space-y-3" data-testid="function-test-result">
                <div className="flex flex-wrap items-center gap-2">
                  {activeCase && caseStatus(activeCase) !== 'none' && (
                    <span className={`rounded-full border px-2.5 py-0.5 font-mono-sm text-[10px] uppercase tracking-[0.06em] ${
                      caseStatus(activeCase) === 'pass'
                        ? 'border-status-success/40 bg-status-success/10 text-status-success'
                        : 'border-status-error/40 bg-status-error/10 text-status-error'
                    }`}>
                      {caseStatus(activeCase) === 'pass' ? 'Pass' : 'Fail'}
                    </span>
                  )}
                  <span className={`rounded-full border px-2.5 py-0.5 font-mono-sm text-[10px] uppercase tracking-[0.06em] ${statusTone[activeResult.status] || statusTone.RUNNING}`}>
                    {activeResult.status}
                  </span>
                  {activeResultVersion != null && <Meta label={`v${activeResultVersion}`} />}
                  {activeResult.timeSeconds != null && <Meta label={`${activeResult.timeSeconds}s`} />}
                  {activeResult.memoryKb != null && <Meta label={`${activeResult.memoryKb} KB`} />}
                  {activeResult.exitCode != null && <Meta label={`exit ${activeResult.exitCode}`} />}
                </div>
                {activeResult.errorName && (
                  <Block title="Error" tone="error" body={`${activeResult.errorName}${activeResult.errorMessage ? `: ${activeResult.errorMessage}` : ''}`} />
                )}
                {activeResult.output != null && <Block title="Output" tone="ok" body={JSON.stringify(activeResult.output, null, 2)} />}
                {activeResult.stdout && <Block title="stdout" body={activeResult.stdout} />}
                {activeResult.stderr && <Block title="stderr" tone="error" body={activeResult.stderr} />}
                {activeResult.compileOutput && <Block title="compile output" tone="error" body={activeResult.compileOutput} />}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function Meta({ label }: { label: string }) {
  return <span className="rounded border border-border-subtle px-1.5 py-0.5 font-mono-sm text-[10px] text-on-surface-variant">{label}</span>;
}

function Block({ title, body, tone }: { title: string; body: string; tone?: 'ok' | 'error' }) {
  const color = tone === 'ok' ? 'text-secondary' : tone === 'error' ? 'text-status-error' : 'text-on-surface-variant';
  return (
    <div>
      <div className={`mb-1 font-mono-sm text-[10px] uppercase tracking-[0.06em] ${color}`}>{title}</div>
      <pre className="max-h-56 overflow-auto rounded-md border border-border-subtle bg-surface-base p-2.5 font-mono-sm text-[11px] leading-relaxed text-on-surface">{body}</pre>
    </div>
  );
}
