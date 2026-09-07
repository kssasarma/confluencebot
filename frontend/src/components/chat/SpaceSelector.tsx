import { ChevronDown, LayoutGrid } from 'lucide-react'
import { cn } from '../../lib/cn'
import { useSpaces } from '../../hooks/useSpaces'
import Menu from '../ui/Menu'
import { buildSpaceActions } from './spaceActions'

interface SpaceSelectorProps {
  /** The space key questions are scoped to; `null` searches every ingested space. */
  value: string | null
  onChange: (spaceKey: string | null) => void
}

/**
 * Scopes the conversation's questions to a single Confluence space.
 *
 * There is no access control behind this yet — every space is offered to every signed-in user,
 * because space-level permissions do not exist in this application today. The filter is purely a
 * relevance tool: it exists to keep an answer about the HR space from being drowned out by
 * unrelated engineering pages, not to hide content anyone is disallowed from seeing.
 */
export default function SpaceSelector({ value, onChange }: SpaceSelectorProps) {
  const { spaces, isLoading } = useSpaces()

  if (isLoading || spaces.length === 0) return null

  const selectedName = value ? spaces.find(space => space.key === value)?.name ?? value : null

  return (
    <Menu
      placement="bottom start"
      trigger={
        <button
          type="button"
          className={cn(
            'flex h-7 items-center gap-1.5 rounded-md border px-2.5 text-2xs font-medium transition-colors',
            'duration-fast',
            value
              ? 'border-primary bg-primary-soft text-primary-emphasis'
              : 'border-border text-muted-foreground hover:bg-surface-hover hover:text-foreground',
          )}
          title="Scope this conversation to a Confluence space"
        >
          <LayoutGrid size={13} aria-hidden="true" />
          <span className="max-w-[10rem] truncate">{selectedName ?? 'All spaces'}</span>
          <ChevronDown size={12} aria-hidden="true" />
        </button>
      }
      actions={buildSpaceActions(spaces, value, onChange)}
    />
  )
}
