// The markdown under /docs is the single source of truth for these pages. Vite
// inlines it at build time, so editing a file there updates the Docs page with
// no copy step. The globs reach outside the frontend root, which is why
// vite.config.ts sets server.fs.allow and the Docker build context is the repo
// root rather than ./frontend.
const markdownModules = import.meta.glob('../../../../docs/*.md', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

const imageModules = import.meta.glob('../../../../docs/images/**/*.{png,svg,jpg,jpeg,gif}', {
  query: '?url',
  import: 'default',
  eager: true,
}) as Record<string, string>;

export type DocGroup = 'Guides' | 'Reference';

export type DocEntry = {
  /** URL slug: /docs/<slug>. Matches the markdown filename without .md. */
  slug: string;
  title: string;
  group: DocGroup;
  summary: string;
  markdown: string;
};

type DocMeta = Omit<DocEntry, 'markdown'>;

// Explicit rather than derived from the glob: this fixes reading order and
// keeps a stray .md file from silently appearing in the nav.
const DOC_META: DocMeta[] = [
  {
    slug: 'workflows',
    title: 'Workflows',
    group: 'Guides',
    summary: 'Create, validate, schedule, and run state machines.',
  },
  {
    slug: 'ai-workflows',
    title: 'AI Workflow Generator',
    group: 'Guides',
    summary: 'Build persistent AI chats and manual drafts that begin with the first workflow state.',
  },
  {
    slug: 'functions',
    title: 'Functions',
    group: 'Guides',
    summary: 'Versioned code that runs in the sandbox and plugs into Task states.',
  },
  {
    slug: 'mcp',
    title: 'MCP Servers',
    group: 'Guides',
    summary: 'Register external MCP servers and call their tools from workflows.',
  },
  {
    slug: 'ai-models',
    title: 'AI Models',
    group: 'Guides',
    summary: 'Add local and cloud models, discover catalogs, set defaults, and rank models.',
  },
  {
    slug: 'asl-jsonata',
    title: 'ASL with JSONata',
    group: 'Reference',
    summary: 'The dialect: states, expressions, and data flow.',
  },
  {
    slug: 'interpreter',
    title: 'Interpreter Internals',
    group: 'Reference',
    summary: 'How the runtime executes a workflow, with worked traces.',
  },
  {
    slug: 'secrets',
    title: 'Secrets',
    group: 'Reference',
    summary: 'Encrypted model and MCP credentials, master key, and migration.',
  },
  {
    slug: 'observability',
    title: 'AI Observability',
    group: 'Reference',
    summary: 'Turn telemetry, latency and token metrics, and Langfuse tracing.',
  },
  {
    slug: 'jenkins',
    title: 'Jenkins CI/CD',
    group: 'Reference',
    summary: 'Test, publish GHCR images, deploy locally, and roll back safely.',
  },
];

function markdownFor(slug: string): string | undefined {
  const key = Object.keys(markdownModules).find((path) => path.endsWith(`/${slug}.md`));
  return key ? markdownModules[key] : undefined;
}

export const DOCS: DocEntry[] = DOC_META.flatMap((meta) => {
  const markdown = markdownFor(meta.slug);
  return markdown ? [{ ...meta, markdown }] : [];
});

export const DOC_GROUPS: DocGroup[] = ['Guides', 'Reference'];

export const DEFAULT_DOC_SLUG = DOCS[0]?.slug ?? 'workflows';

export function findDoc(slug: string | undefined): DocEntry | undefined {
  if (!slug) return undefined;
  return DOCS.find((doc) => doc.slug === slug);
}

// Markdown references images relative to /docs, e.g. `images/mcp/01-foo.png`.
// Vite hashes them into dist/assets, so map the source-relative path to the
// emitted URL.
const imagesByRelativePath = new Map<string, string>(
  Object.entries(imageModules).map(([path, url]) => {
    const relative = path.slice(path.indexOf('/docs/') + '/docs/'.length);
    return [relative, url];
  }),
);

export function resolveDocImage(src: string): string | undefined {
  return imagesByRelativePath.get(src.replace(/^\.\//, ''));
}
