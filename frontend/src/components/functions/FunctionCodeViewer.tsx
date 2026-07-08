import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import Editor from '@monaco-editor/react';
import { Braces, FileCode2, Maximize2, Minimize2 } from 'lucide-react';
import { fileLanguage } from './FunctionVersionWorkbench';

export type CodeFile = {
  path: string;
  content: string;
};

type Props = {
  files: CodeFile[];
  languageName?: string;
  emptyMessage?: string;
};

/**
 * Read-only source viewer that mirrors the create/edit workbench editor layout
 * (file tabs, syntax highlighting, find widget, fullscreen) but never allows edits.
 */
export function FunctionCodeViewer({ files, languageName, emptyMessage = 'Source preview is not available for this version.' }: Props) {
  const [activePath, setActivePath] = useState(files[0]?.path || '');
  const [fullscreen, setFullscreen] = useState(false);

  const activeFile = files.find((file) => file.path === activePath) || files[0] || null;

  useEffect(() => {
    setActivePath(files[0]?.path || '');
  }, [files]);

  useEffect(() => {
    if (!fullscreen) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setFullscreen(false);
    };
    window.addEventListener('keydown', onKey);
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = previousOverflow;
    };
  }, [fullscreen]);

  if (!activeFile || !activeFile.content) {
    return (
      <div className="flex min-h-[260px] items-center justify-center rounded-lg border border-border-subtle bg-surface-lowest/70 px-4 text-center text-[12px] text-on-surface-variant">
        {emptyMessage}
      </div>
    );
  }

  const multiFile = files.length > 1;
  const shellClass = fullscreen
    ? 'fixed inset-0 z-[2147483647] flex min-h-0 flex-col bg-surface-container-lowest'
    : 'flex h-[520px] min-h-0 flex-col rounded-lg border border-border-subtle bg-surface-lowest/70';

  const viewer = (
    <div className={shellClass}>
      <div className="flex h-10 shrink-0 items-center justify-between border-b border-border-subtle">
        <div className="flex h-full min-w-0 flex-1 items-center overflow-x-auto overflow-y-hidden">
          {multiFile ? (
            files.map((file) => (
              <button
                key={file.path}
                type="button"
                onClick={() => setActivePath(file.path)}
                className={`flex h-full min-w-[118px] max-w-[180px] shrink-0 items-center gap-2 border-r px-3 text-[12px] transition-colors ${
                  file.path === activeFile.path
                    ? 'border-primary/40 border-t-2 border-t-primary text-on-surface'
                    : 'border-border-subtle text-on-surface-variant hover:text-on-surface'
                }`}
                title={file.path}
              >
                {file.path.endsWith('.json')
                  ? <Braces size={13} className="shrink-0 text-secondary" />
                  : <FileCode2 size={13} className="shrink-0 text-primary" />}
                <span className="min-w-0 truncate">{file.path.split('/').pop()}</span>
              </button>
            ))
          ) : (
            <div className="flex h-full items-center gap-2 border-r border-primary/40 border-t-2 border-t-primary px-4 text-[12px] text-on-surface">
              <FileCode2 size={14} className="text-primary" />
              <span className="min-w-0 truncate font-mono-sm">{activeFile.path}</span>
            </div>
          )}
        </div>
        <div className="flex h-full shrink-0 items-center gap-1 border-l border-border-subtle px-2">
          <span className="rounded border border-border-subtle px-1.5 py-0.5 font-mono-sm text-[9px] uppercase tracking-[0.06em] text-on-surface-variant">
            Read-only
          </span>
          <button
            type="button"
            onClick={() => setFullscreen((current) => !current)}
            className="rounded-md p-1.5 text-on-surface-variant transition-colors hover:bg-surface-container-low hover:text-on-surface"
            title={fullscreen ? 'Exit fullscreen (Esc)' : 'Fullscreen viewer'}
          >
            {fullscreen ? <Minimize2 size={14} /> : <Maximize2 size={14} />}
          </button>
        </div>
      </div>

      <div className="relative min-h-0 flex-1">
        <Editor
          height="100%"
          theme="vs-dark"
          path={activeFile.path}
          language={fileLanguage(activeFile.path, languageName)}
          value={activeFile.content}
          options={{
            readOnly: true,
            domReadOnly: true,
            minimap: { enabled: true },
            scrollBeyondLastLine: false,
            fontSize: 12,
            lineHeight: 20,
            fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
            wordWrap: 'on',
            tabSize: 2,
            lineNumbersMinChars: 3,
            padding: { top: 14, bottom: 14 },
          }}
        />
      </div>
    </div>
  );

  // Render fullscreen through a portal on <body> so it escapes any ancestor
  // stacking context (app header, sidebar, main's z-index) and sits above all of it.
  return fullscreen ? createPortal(viewer, document.body) : viewer;
}
