import { useEffect, useId, useState } from 'react';

type MermaidApi = typeof import('mermaid')['default'];

// mermaid pulls in ~2.5MB of parsers, so it is imported dynamically: Vite emits
// it as its own chunk that only downloads when a doc actually has a diagram.
// The promise is module-level so all diagrams on a page share one load+init.
let mermaidPromise: Promise<MermaidApi> | null = null;

function loadMermaid(): Promise<MermaidApi> {
  if (!mermaidPromise) {
    mermaidPromise = import('mermaid').then(({ default: mermaid }) => {
      mermaid.initialize({
        startOnLoad: false,
        // The docs are repo-authored, but strict keeps any injected HTML in a
        // node label from executing.
        securityLevel: 'strict',
        theme: 'base',
        fontFamily: 'JetBrains Mono, ui-monospace, monospace',
        themeVariables: {
          background: 'transparent',
          primaryColor: '#1e2024',
          primaryTextColor: '#e2e2e8',
          primaryBorderColor: '#f2795a',
          secondaryColor: '#1a1c20',
          secondaryTextColor: '#e2e2e8',
          secondaryBorderColor: '#84d5cd',
          tertiaryColor: '#111317',
          tertiaryTextColor: '#e2e2e8',
          tertiaryBorderColor: '#544242',
          lineColor: '#84d5cd',
          textColor: '#e2e2e8',
          mainBkg: '#1e2024',
          nodeBorder: '#f2795a',
          clusterBkg: '#111317',
          clusterBorder: '#544242',
          titleColor: '#f2795a',
          edgeLabelBackground: '#0c0e12',
          actorBkg: '#1e2024',
          actorBorder: '#f2795a',
          actorTextColor: '#e2e2e8',
          signalColor: '#84d5cd',
          signalTextColor: '#e2e2e8',
          labelBoxBkgColor: '#1e2024',
          labelBoxBorderColor: '#f2795a',
          labelTextColor: '#e2e2e8',
          loopTextColor: '#e2e2e8',
          noteBkgColor: '#282a2e',
          noteBorderColor: '#84d5cd',
          noteTextColor: '#e2e2e8',
        },
      });
      return mermaid;
    });
  }
  return mermaidPromise;
}

export function MermaidDiagram({ chart }: { chart: string }) {
  const [svg, setSvg] = useState('');
  const [failed, setFailed] = useState(false);
  // useId is unique per instance but contains ':', which is not valid in the
  // SVG id mermaid derives from it.
  const domId = `mermaid-${useId().replace(/[^a-zA-Z0-9]/g, '')}`;

  // Callers key this component by chart source, so a different diagram gets a
  // fresh instance and there is no stale state to reset here.
  useEffect(() => {
    let active = true;

    loadMermaid()
      .then((mermaid) => mermaid.render(domId, chart))
      .then((result) => {
        if (active) setSvg(result.svg);
      })
      .catch(() => {
        // mermaid appends an error node to <body> on a parse failure; drop it
        // so a broken diagram cannot leave debris on the page.
        document.getElementById(`d${domId}`)?.remove();
        if (active) setFailed(true);
      });

    return () => { active = false; };
  }, [chart, domId]);

  // Fall back to the source rather than swallowing the content.
  if (failed) {
    return (
      <pre className="my-4 overflow-x-auto rounded-DEFAULT border border-status-error/35 bg-surface-lowest p-3 text-[12px] leading-5">
        <code className="font-mono-sm text-on-surface-variant">{chart}</code>
      </pre>
    );
  }

  if (!svg) {
    return (
      <div className="my-4 rounded-DEFAULT border border-border-subtle bg-surface-lowest p-6 text-center font-mono-sm text-[11px] text-on-surface-variant">
        Rendering diagram…
      </div>
    );
  }

  return (
    <div
      className="voyager-mermaid my-4 overflow-x-auto rounded-DEFAULT border border-border-subtle bg-surface-lowest p-4"
      // Sanitized by mermaid under securityLevel: 'strict'.
      dangerouslySetInnerHTML={{ __html: svg }}
    />
  );
}
