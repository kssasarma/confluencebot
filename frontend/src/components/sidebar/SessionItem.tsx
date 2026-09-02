import { memo, useEffect, useRef, useState } from 'react'
import { NavLink } from 'react-router-dom'
import { MoreHorizontal, Pencil, Pin, PinOff, Trash2 } from 'lucide-react'
import { cn } from '../../lib/cn'
import { absoluteTime, relativeTime } from '../../lib/time'
import type { ChatSession } from '../../types'
import IconButton from '../ui/IconButton'
import Menu from '../ui/Menu'
import { HIGHLIGHT_CLOSE, HIGHLIGHT_OPEN } from '../../lib/highlight'

interface SessionItemProps {
  session: ChatSession
  onRename: (chatId: string, title: string) => void
  onTogglePin: (chatId: string) => void
  onDelete: (chatId: string) => void
  onNavigate?: () => void
}

/**
 * One conversation in the list.
 *
 * A `NavLink` rather than a button: a conversation has a URL, so it should be openable in a new
 * tab, bookmarkable and shareable. Middle-click and ⌘-click work for free once it is a real link.
 */
function SessionItem({ session, onRename, onTogglePin, onDelete, onNavigate }: SessionItemProps) {
  const [isRenaming, setIsRenaming] = useState(false)
  const [draft, setDraft] = useState(session.title ?? '')
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (isRenaming) inputRef.current?.select()
  }, [isRenaming])

  const title = session.title ?? 'New chat'

  function commit() {
    setIsRenaming(false)
    const trimmed = draft.trim()
    if (trimmed && trimmed !== session.title) onRename(session.chatId, trimmed)
  }

  if (isRenaming) {
    return (
      <div className="px-1 py-0.5">
        <input
          ref={inputRef}
          value={draft}
          aria-label={`Rename ${title}`}
          onChange={event => setDraft(event.target.value)}
          onBlur={commit}
          onKeyDown={event => {
            if (event.key === 'Enter') commit()
            if (event.key === 'Escape') {
              setDraft(session.title ?? '')
              setIsRenaming(false)
            }
          }}
          className="w-full rounded-lg bg-surface px-2 py-1.5 text-sm text-foreground ring-1 ring-primary"
        />
      </div>
    )
  }

  return (
    <div className="group relative flex items-center rounded-lg hover:bg-surface-hover">
      <NavLink
        to={`/chat/${session.chatId}`}
        onClick={onNavigate}
        title={title}
        className={({ isActive }) => cn(
          'min-w-0 flex-1 rounded-lg px-2 py-1.5 text-left',
          isActive && 'bg-muted',
        )}
      >
        <span className="flex items-center gap-1.5">
          {session.pinned && (
            <Pin size={11} className="shrink-0 text-primary-emphasis" aria-label="Pinned" />
          )}
          <span className="block truncate text-sm text-foreground">{title}</span>
        </span>

        {session.match?.snippet
          ? <SearchSnippet snippet={session.match.snippet} />
          : (
            <span
              className="block text-2xs text-muted-foreground"
              title={absoluteTime(session.updatedAt)}
            >
              {relativeTime(session.updatedAt)}
              {session.messageCount > 0 && ` · ${session.messageCount} messages`}
            </span>
          )}
      </NavLink>

      {/*
        Visible on hover for a pointer, and on keyboard focus for everyone else. `group-focus-within`
        is what stops the menu from being unreachable by Tab — the failure mode of every
        hover-only row action.
      */}
      <div className="pr-1 opacity-0 transition-opacity group-hover:opacity-100 group-focus-within:opacity-100">
        <Menu
          placement="bottom end"
          trigger={
            <IconButton
              size="sm"
              label={`Actions for ${title}`}
              icon={<MoreHorizontal size={14} />}
            />
          }
          actions={[
            {
              label: session.pinned ? 'Unpin' : 'Pin to top',
              icon: session.pinned ? <PinOff size={14} /> : <Pin size={14} />,
              onSelect: () => onTogglePin(session.chatId),
            },
            {
              label: 'Rename',
              icon: <Pencil size={14} />,
              onSelect: () => { setDraft(session.title ?? ''); setIsRenaming(true) },
            },
            {
              label: 'Delete',
              icon: <Trash2 size={14} />,
              tone: 'danger',
              separated: true,
              onSelect: () => onDelete(session.chatId),
            },
          ]}
        />
      </div>
    </div>
  )
}

/**
 * The matching passage from a search.
 *
 * The server delimits hits with `[[HL]]`…`[[/HL]]` rather than `<mark>` so this can highlight
 * them as React elements. Rendering server-provided HTML here would mean injecting whatever
 * somebody once pasted into a chat.
 */
function SearchSnippet({ snippet }: { snippet: string }) {
  const parts = snippet.split(HIGHLIGHT_OPEN).flatMap((chunk, index) => {
    if (index === 0) return [{ text: chunk, highlighted: false }]
    const [hit, ...rest] = chunk.split(HIGHLIGHT_CLOSE)
    return [{ text: hit, highlighted: true }, { text: rest.join(HIGHLIGHT_CLOSE), highlighted: false }]
  })

  return (
    <span className="mt-0.5 block truncate text-2xs text-muted-foreground">
      {parts.map((part, index) => part.highlighted
        ? <mark key={index} className="rounded bg-warning-soft px-0.5 text-warning-emphasis">{part.text}</mark>
        : <span key={index}>{part.text}</span>)}
    </span>
  )
}

// A conversation row only changes when its own data changes. Without this, a streamed token
// re-renders every row in the sidebar.
export default memo(SessionItem)
