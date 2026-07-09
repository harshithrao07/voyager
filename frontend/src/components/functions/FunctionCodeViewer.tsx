import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import Editor from '@monaco-editor/react';
import { Braces, ChevronRight, FileCode2, Folder, Maximize2, Minimize2 } from 'lucide-react';
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

type TreeNode = {
  name: string;
  path: string;
  kind: 'file' | 'directory';
  children: TreeNode[];
};

function buildTree(files: CodeFile[]): TreeNode[] {
  const roots: TreeNode[] = [];
  const dirs = new Map<string, TreeNode>();

  const ensureDir = (path: string): TreeNode | null => {
    if (!path) return null;
    const existing = dirs.get(path);
    if (existing) return existing;
    const parts = path.split('/');
    const node: TreeNode = { name: parts[parts.length - 1], path, kind: 'directory', children: [] };
    dirs.set(path, node);
    const parent = ensureDir(parts.slice(0, -1).join('/'));
    (parent ? parent.children : roots).push(node);
    return node;
  };

  for (const file of files) {
    const parts = file.path.replace(/^\/+/, '').split('/');
    const parent = ensureDir(parts.slice(0, -1).join('/'));
    (parent ? parent.children : roots).push({
      name: parts[parts.length - 1],
      path: file.path,
      kind: 'file',
      children: [],
    });
  }

  const sort = (nodes: TreeNode[]) => {
    nodes.sort((a, b) => (a.kind !== b.kind ? (a.kind === 'directory' ? -1 : 1) : a.name.localeCompare(b.name)));
    nodes.forEach((node) => sort(node.children));
  };
  sort(roots);
  return roots;
}

function TreeRow({ node, depth, activePath, onSelect }: {
  node: TreeNode;
  depth: number;
  activePath: string;
  onSelect: (path: string) => void;
}) {
  const [open, setOpen] = useState(true);
  const indent = { paddingLeft: `${depth * 12 + 8}px` };

  if (node.kind === 'directory') {
    return (
      <>
        <button
          type="button"
          onClick={() => setOpen((current) => !current)}
          style={indent}
          className="flex h-7 w-full items-center gap-1.5 pr-2 text-left text-[12px] text-on-surface-variant transition-colors hover:text-on-surface"
        >
          <ChevronRight size={12} className={`shrink-0 transition-transform ${open ? 'rotate-90' : ''}`} />
          <Folder size={13} className="shrink-0 text-primary/80" />
          <span className="min-w-0 truncate">{node.name}</span>
        </button>
        {open && node.children.map((child) => (
          <TreeRow key={child.path} node={child} depth={depth + 1} activePath={activePath} onSelect={onSelect} />
        ))}
      </>
    );
  }

  const active = node.path === activePath;
  return (
    <button
      type="button"
      onClick={() => onSelect(node.path)}
      style={indent}
      title={node.path}
      className={`flex h-7 w-full items-center gap-1.5 pr-2 text-left text-[12px] transition-colors ${
        active ? 'bg-primary/12 text-on-surface' : 'text-on-surface-variant hover:text-on-surface'
      }`}
    >
      <span className="w-3 shrink-0" />
      {node.name.endsWith('.json')
        ? <Braces size={13} className="shrink-0 text-secondary" />
        : <FileCode2 size={13} className="shrink-0 text-primary" />}
      <span className="min-w-0 truncate">{node.name}</span>
    </button>
  );
}

/**
 * Read-only source viewer that mirrors the create/edit workbench layout — a file
 * tree with folders on the left and a syntax-highlighted editor on the right —
 * but never allows edits. Supports find (Ctrl/Cmd+F) and fullscreen.
 */
export function FunctionCodeViewer({ files, languageName, emptyMessage = 'Source preview is not available for this version.' }: Props) {
  const [activePath, setActivePath] = useState(files[0]?.path || '');
  const [fullscreen, setFullscreen] = useState(false);

  const tree = useMemo(() => buildTree(files), [files]);
  const activeFile = files.find((file) => file.path === activePath) || files[0] || null;
  // Show the folder tree when there are multiple files or any nested paths.
  const showTree = files.length > 1 || files.some((file) => file.path.includes('/'));

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

  const shellClass = fullscreen
    ? 'fixed inset-0 z-[2147483647] flex min-h-0 flex-col bg-surface-container-lowest'
    : 'flex h-[520px] min-h-0 flex-col overflow-hidden rounded-lg border border-border-subtle bg-surface-lowest/70';

  const viewer = (
    <div className={shellClass}>
      <div className="flex h-10 shrink-0 items-center justify-between gap-2 border-b border-border-subtle px-3">
        <div className="flex min-w-0 items-center gap-2 text-[12px] text-on-surface">
          <FileCode2 size={14} className="shrink-0 text-primary" />
          <span className="min-w-0 truncate font-mono-sm">{activeFile.path}</span>
        </div>
        <div className="flex shrink-0 items-center gap-1">
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

      <div className="flex min-h-0 flex-1">
        {showTree && (
          <aside className="flex w-56 shrink-0 flex-col border-r border-border-subtle bg-surface-container-lowest/50">
            <div className="flex h-8 shrink-0 items-center border-b border-border-subtle px-3 font-mono-sm text-[10px] uppercase tracking-[0.08em] text-on-surface-variant">
              Files
            </div>
            <div className="min-h-0 flex-1 overflow-y-auto py-1">
              {tree.map((node) => (
                <TreeRow key={node.path} node={node} depth={0} activePath={activeFile.path} onSelect={setActivePath} />
              ))}
            </div>
          </aside>
        )}
        <div className="relative min-h-0 min-w-0 flex-1">
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
    </div>
  );

  // Render fullscreen through a portal on <body> so it escapes any ancestor
  // stacking context (app header, sidebar, main's z-index) and sits above all of it.
  return fullscreen ? createPortal(viewer, document.body) : viewer;
}
