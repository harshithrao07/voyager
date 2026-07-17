import { Children, isValidElement, useCallback, useEffect, useMemo, useRef, type ReactNode } from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeSlug from 'rehype-slug';
import { DOCS, DOC_GROUPS, DEFAULT_DOC_SLUG, findDoc, resolveDocImage } from '../components/docs/docsContent';
import { MermaidDiagram } from '../components/docs/MermaidDiagram';

type Props = {
  slug?: string;
  onNavigate: (path: string) => void;
};

// `functions.md`, `./mcp.md#the-trust-ladder` -> the doc slug and optional anchor.
function parseDocLink(href: string): { slug: string; hash: string } | null {
  const match = href.match(/^\.?\/?([A-Za-z0-9._-]+)\.md(#.*)?$/);
  if (!match?.[1]) return null;
  return { slug: match[1], hash: match[2]?.slice(1) ?? '' };
}

// A ```mermaid fence arrives as <pre><code class="language-mermaid">. Pull the
// source back out so it can be drawn instead of printed.
function mermaidSource(children: ReactNode): string | null {
  const child = Children.toArray(children)[0];
  if (!isValidElement<{ className?: string; children?: ReactNode }>(child)) return null;
  if (!child.props.className?.includes('language-mermaid')) return null;

  const text = Children.toArray(child.props.children)
    .filter((node): node is string => typeof node === 'string')
    .join('');
  // The markdown is authored on Windows; mermaid's parser wants plain \n.
  const normalized = text.replace(/\r\n/g, '\n').trim();
  return normalized || null;
}

export function DocsPage({ slug, onNavigate }: Props) {
  const doc = findDoc(slug) ?? findDoc(DEFAULT_DOC_SLUG);
  const scrollRef = useRef<HTMLDivElement>(null);
  const pendingHashRef = useRef<string>('');

  const scrollToHeading = useCallback((id: string) => {
    const target = document.getElementById(id);
    if (target) target.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }, []);

  // A cross-doc link can carry an anchor; the heading only exists once the new
  // doc has rendered, so resolve it here rather than at click time.
  useEffect(() => {
    const hash = pendingHashRef.current;
    pendingHashRef.current = '';
    if (hash) {
      scrollToHeading(hash);
      return;
    }
    scrollRef.current?.scrollTo({ top: 0 });
  }, [doc?.slug, scrollToHeading]);

  const openDoc = useCallback(
    (nextSlug: string, hash = '') => {
      pendingHashRef.current = hash;
      if (nextSlug === doc?.slug) {
        if (hash) scrollToHeading(hash);
        else scrollRef.current?.scrollTo({ top: 0, behavior: 'smooth' });
        pendingHashRef.current = '';
        return;
      }
      onNavigate(`/docs/${nextSlug}`);
    },
    [doc?.slug, onNavigate, scrollToHeading],
  );

  const markdownComponents = useMemo<Components>(
    () => ({
      // `id` comes from rehype-slug and is what the in-page anchors target, so
      // it is forwarded explicitly. The rest of the props (notably `node`) are
      // react-markdown internals and must not reach the DOM.
      h1: ({ children, id }) => (
        <h1 id={id} className="mb-3 font-display text-[22px] font-semibold leading-8 text-on-surface">
          {children}
        </h1>
      ),
      h2: ({ children, id }) => (
        <h2
          id={id}
          className="mt-9 mb-3 scroll-mt-6 border-b border-border-subtle pb-2 font-display text-[16px] font-semibold leading-6 text-on-surface"
        >
          {children}
        </h2>
      ),
      h3: ({ children, id }) => (
        <h3 id={id} className="mt-6 mb-2 scroll-mt-6 font-display text-[13px] font-semibold leading-5 text-secondary">
          {children}
        </h3>
      ),
      p: ({ children }) => <p className="my-3 leading-6 text-on-surface-variant">{children}</p>,
      ul: ({ children }) => <ul className="my-3 list-disc space-y-1.5 pl-5 text-on-surface-variant">{children}</ul>,
      ol: ({ children }) => <ol className="my-3 list-decimal space-y-1.5 pl-5 text-on-surface-variant">{children}</ol>,
      li: ({ children }) => <li className="pl-1 leading-6">{children}</li>,
      strong: ({ children }) => <strong className="font-semibold text-on-surface">{children}</strong>,
      em: ({ children }) => <em className="text-secondary">{children}</em>,
      hr: () => <hr className="my-8 border-0 border-t border-border-subtle" />,
      blockquote: ({ children }) => (
        <blockquote className="my-4 rounded-DEFAULT border border-border-subtle border-l-2 border-l-primary/40 bg-surface-container-lowest px-4 py-1 text-on-surface-variant">
          {children}
        </blockquote>
      ),
      table: ({ children }) => (
        <div className="my-4 overflow-x-auto rounded-DEFAULT border border-border-subtle">
          <table className="w-full border-collapse text-left font-mono-sm text-[12px]">{children}</table>
        </div>
      ),
      thead: ({ children }) => <thead className="bg-surface-container-low">{children}</thead>,
      th: ({ children }) => (
        <th className="border-b border-border-subtle px-3 py-2 font-semibold text-on-surface">{children}</th>
      ),
      td: ({ children }) => (
        <td className="border-b border-border-subtle px-3 py-2 align-top text-on-surface-variant">{children}</td>
      ),
      pre: ({ children }) => {
        const chart = mermaidSource(children);
        if (chart) return <MermaidDiagram key={chart} chart={chart} />;
        return (
          <pre className="my-4 overflow-x-auto rounded-DEFAULT border border-border-subtle bg-surface-lowest p-3 text-[12px] leading-5">
            {children}
          </pre>
        );
      },
      code: ({ className, children }) => {
        // react-markdown only sets className (language-*) on fenced blocks;
        // bare inline code gets the pill treatment.
        const isBlock = Boolean(className);
        return (
          <code
            className={
              isBlock
                ? `${className || ''} bg-transparent p-0 font-mono-sm text-primary`
                : 'rounded-DEFAULT border border-border-subtle bg-surface-container px-1 py-0.5 font-mono-sm text-[12px] text-primary'
            }
          >
            {children}
          </code>
        );
      },
      img: ({ src, alt }) => {
        const resolved = typeof src === 'string' ? resolveDocImage(src) : undefined;
        if (!resolved) return null;
        return (
          <img
            src={resolved}
            alt={alt || ''}
            loading="lazy"
            className="my-4 w-full rounded-DEFAULT border border-border-subtle bg-surface-lowest"
          />
        );
      },
      a: ({ href, children }) => {
        if (typeof href !== 'string') return <span>{children}</span>;

        if (href.startsWith('#')) {
          return (
            <a
              href={href}
              onClick={(event) => {
                event.preventDefault();
                scrollToHeading(href.slice(1));
              }}
              className="text-primary underline decoration-primary/30 underline-offset-2 hover:decoration-primary"
            >
              {children}
            </a>
          );
        }

        const docLink = parseDocLink(href);
        if (docLink && findDoc(docLink.slug)) {
          return (
            <a
              href={`/docs/${docLink.slug}`}
              onClick={(event) => {
                event.preventDefault();
                openDoc(docLink.slug, docLink.hash);
              }}
              className="text-primary underline decoration-primary/30 underline-offset-2 hover:decoration-primary"
            >
              {children}
            </a>
          );
        }

        return (
          <a
            href={href}
            target="_blank"
            rel="noreferrer"
            className="text-primary underline decoration-primary/30 underline-offset-2 hover:decoration-primary"
          >
            {children}
          </a>
        );
      },
    }),
    [openDoc, scrollToHeading],
  );

  if (!doc) {
    return (
      <div className="flex h-full items-center justify-center px-6">
        <p className="font-mono-sm text-[12px] text-on-surface-variant">No documentation is bundled with this build.</p>
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0">
      <nav className="hidden w-64 shrink-0 overflow-y-auto border-r border-border-subtle px-3 py-5 lg:block">
        {DOC_GROUPS.map((group) => {
          const entries = DOCS.filter((entry) => entry.group === group);
          if (!entries.length) return null;
          return (
            <div key={group} className="mb-5">
              <p className="px-2 pb-2 font-mono-sm text-[10px] uppercase tracking-wider text-on-surface-variant">
                {group}
              </p>
              <div className="space-y-0.5">
                {entries.map((entry) => {
                  const active = entry.slug === doc.slug;
                  return (
                    <button
                      key={entry.slug}
                      type="button"
                      onClick={() => openDoc(entry.slug)}
                      aria-current={active ? 'page' : undefined}
                      className={`block w-full rounded-lg px-2 py-2 text-left transition-colors ${
                        active
                          ? 'bg-primary/10 text-primary'
                          : 'text-on-surface hover:bg-surface-container-low hover:text-primary'
                      }`}
                    >
                      <span className="block font-body-sm text-body-sm font-medium">{entry.title}</span>
                      <span className="mt-0.5 block font-mono-sm text-[10px] leading-4 text-on-surface-variant">
                        {entry.summary}
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>
          );
        })}
      </nav>

      <div ref={scrollRef} className="min-h-0 flex-1 overflow-y-auto px-6 py-6 md:px-10">
        <div className="mx-auto w-full max-w-[820px] pb-16 font-body-sm text-body-sm">
          <div className="mb-6 flex flex-wrap gap-1.5 lg:hidden">
            {DOCS.map((entry) => (
              <button
                key={entry.slug}
                type="button"
                onClick={() => openDoc(entry.slug)}
                className={`rounded-lg border px-2.5 py-1 font-mono-sm text-[11px] transition-colors ${
                  entry.slug === doc.slug
                    ? 'border-primary/40 bg-primary/10 text-primary'
                    : 'border-border-subtle text-on-surface-variant hover:text-primary'
                }`}
              >
                {entry.title}
              </button>
            ))}
          </div>
          <ReactMarkdown
            remarkPlugins={[remarkGfm]}
            rehypePlugins={[rehypeSlug]}
            components={markdownComponents}
          >
            {doc.markdown}
          </ReactMarkdown>
        </div>
      </div>
    </div>
  );
}
