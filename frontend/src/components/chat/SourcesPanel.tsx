import { useEffect, useRef, useState } from 'react'
import { ChevronDown, ExternalLink, FileText } from 'lucide-react'
import { cn } from '../../lib/cn'
import type { Source } from '../../types'
import Badge from '../ui/Badge'

interface SourcesPanelProps {
  sources: Source[]
  /** Set when an inline citation was clicked, to scroll-highlight the matching row. */
  focusedPageId?: string | null
}

/**
 * The pages an answer was built from.
 *
 * The row of truncated pills this replaces hid the relevance score inside a `title` attribute —
 * invisible on touch, inconsistently announced by screen readers — and showed no excerpt, no
 * section and no ordering. Everything shown here was already on the wire and was being discarded.
 */
export default function SourcesPanel({ sources, focusedPageId }: SourcesPanelProps) {
  const [expanded, setExpanded] = useState(false)
  const listRef = useRef<HTMLUListElement>(null)

  // Clicking an inline citation opens the panel and brings its row into view — the two views of
  // the same citation stay connected instead of being separate features.
  useEffect(() => {
    if (!focusedPageId) return
    setExpanded(true)

    const row = listRef.current?.querySelector(`[data-page-id="${CSS.escape(focusedPageId)}"]`)
    row?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [focusedPageId])

  if (sources.length === 0) return null

  return (
    <div className="mt-2">
      <button
        onClick={() => setExpanded(open => !open)}
        aria-expanded={expanded}
        className={cn(
          'inline-flex items-center gap-1.5 rounded-md px-1.5 py-1 text-2xs',
          'text-muted-foreground transition-colors hover:bg-surface-hover hover:text-foreground',
        )}
      >
        <FileText size={12} aria-hidden="true" />
        {sources.length} {sources.length === 1 ? 'source' : 'sources'}
        <ChevronDown
          size={12}
          aria-hidden="true"
          className={cn('transition-transform duration-fast', expanded && 'rotate-180')}
        />
      </button>

      {expanded && (
        <ul ref={listRef} className="mt-2 space-y-1.5">
          {sources.map((source, index) => (
            <SourceRow
              key={source.pageId ?? source.url ?? index}
              rank={index + 1}
              source={source}
              highlighted={!!focusedPageId && source.pageId === focusedPageId}
            />
          ))}
        </ul>
      )}
    </div>
  )
}

function SourceRow({
  rank, source, highlighted,
}: { rank: number; source: Source; highlighted: boolean }) {
  const relevance = source.score == null ? null : Math.round(source.score * 100)

  return (
    <li
      data-page-id={source.pageId}
      className={cn(
        'rounded-lg border p-2.5 transition-colors',
        highlighted ? 'border-primary bg-primary-soft' : 'border-border bg-surface',
      )}
    >
      <div className="flex items-start gap-2">
        <span
          aria-hidden="true"
          className="mt-0.5 flex h-4 w-4 shrink-0 items-center justify-center rounded bg-muted text-[0.625rem] font-semibold text-muted-foreground"
        >
          {rank}
        </span>

        <div className="min-w-0 flex-1">
          <a
            href={source.anchorUrl || source.url}
            target="_blank"
            rel="noopener noreferrer"
            className="group flex items-start gap-1.5"
          >
            <span className="text-2xs font-medium text-foreground group-hover:underline">
              {source.title}
            </span>
            <ExternalLink
              size={11}
              aria-hidden="true"
              className="mt-0.5 shrink-0 text-muted-foreground"
            />
            <span className="sr-only">Opens in Confluence</span>
          </a>

          {(source.spaceKey || source.sectionHeading) && (
            <p className="mt-0.5 truncate text-2xs text-muted-foreground">
              {[source.spaceKey, source.sectionHeading].filter(Boolean).join(' › ')}
            </p>
          )}

          {source.excerpt && (
            <p className="mt-1 line-clamp-3 text-2xs leading-relaxed text-muted-foreground">
              {source.excerpt}
            </p>
          )}

          {relevance !== null && (
            <div className="mt-1.5 flex items-center gap-2">
              {/*
                The numeral is shown alongside the bar rather than encoded only in its length:
                a bar on its own conveys the value by colour and size, which is exactly what a
                low-vision or colour-blind reader cannot use (WCAG 1.4.1).
              */}
              <div
                className="h-1 flex-1 overflow-hidden rounded-full bg-muted"
                role="img"
                aria-label={`Relevance ${relevance} percent`}
              >
                <div
                  className="h-full rounded-full bg-primary"
                  style={{ width: `${Math.max(relevance, 2)}%` }}
                />
              </div>
              <Badge tone="neutral">{relevance}%</Badge>
            </div>
          )}
        </div>
      </div>
    </li>
  )
}
