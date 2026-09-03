import { memo, useMemo } from 'react'
import ReactMarkdown, { type Components } from 'react-markdown'
import type { PluggableList } from 'unified'
import remarkGfm from 'remark-gfm'
import { cn } from '../../lib/cn'
import type { Citation as CitationRef, Source } from '../../types'
import { closeUnterminatedCodeFence, remarkCitations } from '../../lib/markdown/remarkCitations'
import { rehypeHighlightSubset } from '../../lib/markdown/rehypeHighlightSubset'
import CodeBlock from './CodeBlock'
import Citation from './Citation'

interface MarkdownContentProps {
  content: string
  citations?: CitationRef[]
  sources?: Source[]
  /** True while tokens are still arriving; enables the fence-closing workaround. */
  streaming?: boolean
  onFocusSource?: (pageId: string | undefined) => void
  className?: string
}

/**
 * Renders an answer.
 *
 * Three things are handled here that a bare `<ReactMarkdown>` gets wrong for streamed content:
 * an unterminated code fence, citation markers, and the `<pre><pre>` produced by overriding
 * `code` instead of `pre`. Each is documented at the point it is dealt with.
 */
function MarkdownContent({
  content, citations, sources, streaming, onFocusSource, className,
}: MarkdownContentProps) {
  /** marker → source, built once per render of an answer rather than per marker. */
  const byMarker = useMemo(() => {
    const map = new Map<number, Source>()
    if (!citations?.length || !sources?.length) return map

    const byPageId = new Map(sources.filter(s => s.pageId).map(s => [s.pageId!, s]))
    for (const citation of citations) {
      const source = byPageId.get(citation.pageId)
      if (source) map.set(citation.marker, source)
    }
    return map
  }, [citations, sources])

  const resolvable = useMemo(() => new Set(byMarker.keys()), [byMarker])

  const remarkPlugins = useMemo<PluggableList>(
    () => [remarkGfm, [remarkCitations, { resolvable }]],
    [resolvable],
  )

  const components = useMemo<Components>(() => ({
    // Overriding `pre` rather than `code`: react-markdown has already wrapped the code element,
    // so returning a second `<pre>` from the `code` override nests them.
    pre: ({ children }) => <CodeBlock>{children}</CodeBlock>,

    code: ({ className: codeClassName, children, ...props }) => {
      const isBlock = typeof codeClassName === 'string' && codeClassName.includes('language-')
      return isBlock
        ? <code className={codeClassName} {...props}>{children}</code>
        : (
          <code
            className="rounded bg-muted px-1 py-0.5 font-mono text-[0.85em] text-foreground"
            {...props}
          >
            {children}
          </code>
        )
    },

    a: ({ href, children }) => (
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer"
        className="text-primary-emphasis underline underline-offset-2"
      >
        {children}
      </a>
    ),

    table: ({ children }) => (
      // Wide tables scroll inside their own box; without this the whole page scrolls sideways.
      <div className="my-3 overflow-x-auto rounded-lg border border-border">
        <table className="w-full text-2xs">{children}</table>
      </div>
    ),
    th: ({ children }) => (
      <th className="border-b border-border bg-muted px-2.5 py-1.5 text-left font-medium">
        {children}
      </th>
    ),
    td: ({ children }) => <td className="border-b border-border px-2.5 py-1.5">{children}</td>,

    // The node type produced by remarkCitations. Unresolvable markers never reach here — the
    // plugin leaves those as plain text.
    'cite-marker': ({ node }: { node?: { properties?: Record<string, unknown> } }) => {
      // Both spellings: hast normalises `dataMarker`, but a stray literal attribute would arrive
      // under the dashed name, and a citation silently rendering as "[0]" is hard to spot.
      const properties = node?.properties ?? {}
      const marker = Number(properties.dataMarker ?? properties['data-marker'] ?? 0)
      const source = byMarker.get(marker)
      if (!source) return <span>[{marker}]</span>
      return <Citation marker={marker} source={source} onFocusSource={onFocusSource} />
    },
  } as Components), [byMarker, onFocusSource])

  // Only while streaming: closing the fence on a finished answer would append a stray fence to
  // a legitimately unbalanced one the model actually wrote.
  const markdown = streaming ? closeUnterminatedCodeFence(content) : content

  return (
    <div
      className={cn(
        'prose prose-sm max-w-none',
        'prose-p:my-2 prose-headings:mb-2 prose-headings:mt-4 prose-headings:text-sm',
        'prose-ul:my-2 prose-ol:my-2 prose-li:my-0.5 prose-pre:my-0 prose-pre:bg-transparent prose-pre:p-0',
        className,
      )}
    >
      <ReactMarkdown
        remarkPlugins={remarkPlugins}
        rehypePlugins={[rehypeHighlightSubset]}
        components={components}
      >
        {markdown}
      </ReactMarkdown>
    </div>
  )
}

// Answers are expensive to render and never change once complete. Memoising is what keeps a long
// transcript from re-parsing every message on each streamed token of the newest one.
export default memo(MarkdownContent)
