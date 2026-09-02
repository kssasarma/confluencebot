import { useState } from 'react'
import { ExternalLink } from 'lucide-react'
import { cn } from '../../lib/cn'
import type { Source } from '../../types'

interface CitationProps {
  marker: number
  source: Source
  /** Scroll-highlights the matching row in the sources panel. */
  onFocusSource?: (pageId: string | undefined) => void
}

/**
 * An inline `[n]` rendered as a link to the page it came from.
 *
 * The hover card is supplementary — the link itself already works, and the accessible name spells
 * out what the marker refers to, so nothing here is reachable only by hovering.
 */
export default function Citation({ marker, source, onFocusSource }: CitationProps) {
  const [open, setOpen] = useState(false)
  const href = source.anchorUrl || source.url

  return (
    <span
      className="relative inline-block"
      onPointerEnter={() => setOpen(true)}
      onPointerLeave={() => setOpen(false)}
    >
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer"
        onClick={() => onFocusSource?.(source.pageId)}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        aria-label={`Source ${marker}: ${source.title}. Opens in Confluence.`}
        className={cn(
          'mx-px inline-flex h-4 min-w-4 items-center justify-center rounded px-1',
          'align-super text-[0.625rem] font-semibold leading-none no-underline',
          'bg-primary-soft text-primary-emphasis transition-colors hover:bg-primary/20',
        )}
      >
        {marker}
      </a>

      {open && (
        <span
          role="tooltip"
          className={cn(
            'pointer-events-none absolute bottom-full left-1/2 z-popover mb-2 w-72 -translate-x-1/2',
            'animate-fade-in rounded-lg border border-border bg-surface p-3 text-left shadow-overlay',
          )}
        >
          <span className="block text-2xs font-semibold text-foreground">{source.title}</span>

          {(source.spaceKey || source.sectionHeading) && (
            <span className="mt-0.5 block text-2xs text-muted-foreground">
              {[source.spaceKey, source.sectionHeading].filter(Boolean).join(' › ')}
            </span>
          )}

          {source.excerpt && (
            <span className="mt-1.5 block text-2xs leading-relaxed text-muted-foreground line-clamp-3">
              {source.excerpt}
            </span>
          )}

          <span className="mt-2 flex items-center gap-1 text-2xs text-primary-emphasis">
            <ExternalLink size={11} aria-hidden="true" />
            Open in Confluence
          </span>
        </span>
      )}
    </span>
  )
}
