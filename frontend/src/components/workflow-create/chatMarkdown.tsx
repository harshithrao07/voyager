export const chatMarkdownComponents = {
  p: ({ children }: any) => <p className="mb-3 last:mb-0">{children}</p>,
  ul: ({ children }: any) => <ul className="my-3 list-disc space-y-1 pl-5">{children}</ul>,
  ol: ({ children }: any) => <ol className="my-3 list-decimal space-y-1 pl-5">{children}</ol>,
  li: ({ children }: any) => <li className="pl-1">{children}</li>,
  h1: ({ children }: any) => <h3 className="mb-2 mt-4 font-mono-sm text-[13px] font-semibold leading-5 text-primary first:mt-0">{children}</h3>,
  h2: ({ children }: any) => <h3 className="mb-2 mt-4 font-mono-sm text-[13px] font-semibold leading-5 text-primary first:mt-0">{children}</h3>,
  h3: ({ children }: any) => <h3 className="mb-2 mt-3 font-mono-sm text-[12px] font-semibold leading-5 text-primary first:mt-0">{children}</h3>,
  strong: ({ children }: any) => <strong className="font-semibold text-primary">{children}</strong>,
  em: ({ children }: any) => <em className="text-secondary">{children}</em>,
  a: ({ href, children }: any) => (
    <a href={href} className="text-primary underline underline-offset-2 hover:text-primary-fixed" target="_blank" rel="noreferrer">
      {children}
    </a>
  ),
  blockquote: ({ children }: any) => (
    <blockquote className="my-3 border-l border-primary/30 pl-3 text-on-surface-variant">
      {children}
    </blockquote>
  ),
  pre: ({ children }: any) => (
    <pre className="my-3 overflow-x-auto rounded-DEFAULT border border-border-subtle bg-surface-lowest p-3 text-[12px] leading-5">
      {children}
    </pre>
  ),
  code: ({ className, children, ...props }: any) => {
    const isBlock = Boolean(className);
    return (
      <code
        className={isBlock
          ? `${className || ''} bg-transparent p-0 font-mono-sm text-primary`
          : 'rounded-DEFAULT border border-border-subtle bg-surface-container px-1 py-0.5 font-mono-sm text-[12px] text-primary'}
        {...props}
      >
        {children}
      </code>
    );
  },
};
