import { useDeferredValue, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Loader2, MessageSquare, Plus, Search, Settings, ShieldCheck, X } from 'lucide-react'
import { cn } from '../../lib/cn'
import { groupByRecency } from '../../lib/time'
import { useAuth } from '../../context/AuthContext'
import { useChat } from '../../context/ChatContext'
import { useConfirm } from '../ui/ConfirmDialog'
import Button from '../ui/Button'
import EmptyState from '../ui/EmptyState'
import IconButton from '../ui/IconButton'
import { SkeletonRow } from '../ui/Skeleton'
import SessionItem from './SessionItem'
import AccountMenu from './AccountMenu'

interface SidebarProps {
  /** Closes the drawer after navigating. Only supplied on small screens. */
  onNavigate?: () => void
}

/**
 * The conversation list.
 *
 * Search runs against the server rather than against what happens to be loaded, so a phrase from
 * a conversation three months ago is findable — the previous list had no search at all, and
 * fetched every conversation in one unpaginated call.
 */
export default function Sidebar({ onNavigate }: SidebarProps) {
  const navigate = useNavigate()
  const { canAdminister } = useAuth()
  const confirm = useConfirm()
  const chat = useChat()

  const [query, setQuery] = useState('')
  const searchRef = useRef<HTMLInputElement>(null)

  // The list keeps rendering the previous results while the new ones load, so typing never
  // flashes an empty sidebar.
  const deferredQuery = useDeferredValue(query)

  // Depends on the setter, not on the whole context: the context value changes on every streamed
  // token, and taking it as a dependency would restart this timer continuously so the search
  // never fired while an answer was arriving.
  const { setSearch } = chat
  useEffect(() => {
    const timer = setTimeout(() => setSearch(deferredQuery.trim()), 200)
    return () => clearTimeout(timer)
  }, [setSearch, deferredQuery])

  async function handleDelete(chatId: string) {
    const confirmed = await confirm({
      title: 'Delete this conversation?',
      description: 'The conversation and its transcript are removed. This cannot be undone.',
      confirmLabel: 'Delete',
      tone: 'danger',
    })
    if (confirmed) chat.deleteConversation(chatId)
  }

  function handleNewChat() {
    navigate(`/chat/${chat.startDraft()}`)
    onNavigate?.()
  }

  const pinned = chat.sessions.filter(session => session.pinned)
  const rest = chat.sessions.filter(session => !session.pinned)
  const groups = groupByRecency(rest, session => session.updatedAt)
  const isSearching = chat.search.length > 0

  return (
    <div className="flex h-full min-h-0 flex-col bg-surface">
      <div className="flex items-center gap-2 border-b border-border p-3">
        <Link to="/" onClick={onNavigate} className="truncate rounded text-sm font-semibold text-foreground">
          Confluence Bot
        </Link>
      </div>

      <div className="space-y-2 p-2">
        <Button variant="secondary" block onClick={handleNewChat}>
          <Plus size={16} aria-hidden="true" />
          New chat
        </Button>

        <div className="relative">
          <Search
            size={14}
            aria-hidden="true"
            className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <input
            ref={searchRef}
            type="search"
            value={query}
            onChange={event => setQuery(event.target.value)}
            onKeyDown={event => { if (event.key === 'Escape') setQuery('') }}
            placeholder="Search conversations"
            aria-label="Search conversations"
            className={cn(
              'h-9 w-full rounded-lg border border-border bg-background pl-8 pr-8 text-sm',
              'text-foreground placeholder:text-muted-foreground',
              // The native clear affordance is unstyleable and inconsistent across browsers.
              '[&::-webkit-search-cancel-button]:hidden',
            )}
          />
          {query && (
            <IconButton
              size="sm"
              label="Clear search"
              icon={<X size={13} />}
              onClick={() => { setQuery(''); searchRef.current?.focus() }}
              className="absolute right-1 top-1/2 -translate-y-1/2"
            />
          )}
        </div>
      </div>

      <nav aria-label="Conversations" className="min-h-0 flex-1 space-y-4 overflow-y-auto px-2 pb-2">
        {chat.isLoading ? (
          <div aria-busy="true" aria-label="Loading conversations">
            {Array.from({ length: 6 }, (_, index) => <SkeletonRow key={index} />)}
          </div>
        ) : chat.error ? (
          <EmptyState
            tone="error"
            title="Could not load your conversations"
            description={chat.error}
            action={<Button size="sm" variant="secondary" onClick={chat.refresh}>Try again</Button>}
          />
        ) : chat.sessions.length === 0 ? (
          <EmptyState
            icon={<MessageSquare size={18} />}
            title={isSearching ? 'No matches' : 'No conversations yet'}
            description={isSearching
              ? 'Try a different word from the conversation you are looking for.'
              : 'Ask a question to start one.'}
          />
        ) : (
          <>
            {pinned.length > 0 && (
              <Group label="Pinned">
                {pinned.map(session => (
                  <SessionItem
                    key={session.chatId}
                    session={session}
                    onRename={chat.rename}
                    onTogglePin={chat.togglePin}
                    onDelete={handleDelete}
                    onNavigate={onNavigate}
                  />
                ))}
              </Group>
            )}

            {groups.map(group => (
              <Group key={group.label} label={group.label}>
                {group.items.map(session => (
                  <SessionItem
                    key={session.chatId}
                    session={session}
                    onRename={chat.rename}
                    onTogglePin={chat.togglePin}
                    onDelete={handleDelete}
                    onNavigate={onNavigate}
                  />
                ))}
              </Group>
            ))}

            {chat.hasMore && (
              <button
                onClick={chat.loadMore}
                disabled={chat.isFetchingMore}
                className="flex w-full items-center justify-center gap-2 rounded-lg py-2 text-2xs text-muted-foreground hover:bg-surface-hover"
              >
                {chat.isFetchingMore && <Loader2 size={12} className="animate-spin" aria-hidden="true" />}
                {chat.isFetchingMore ? 'Loading…' : 'Show older conversations'}
              </button>
            )}
          </>
        )}
      </nav>

      <div className="space-y-1 border-t border-border p-2">
        {canAdminister && (
          <Link
            to="/admin"
            onClick={onNavigate}
            className="flex items-center gap-2 rounded-lg p-2 text-sm text-muted-foreground hover:bg-surface-hover hover:text-foreground"
          >
            <ShieldCheck size={16} aria-hidden="true" />
            Admin
          </Link>
        )}
        <Link
          to="/settings"
          onClick={onNavigate}
          className="flex items-center gap-2 rounded-lg p-2 text-sm text-muted-foreground hover:bg-surface-hover hover:text-foreground"
        >
          <Settings size={16} aria-hidden="true" />
          Settings
        </Link>

        <AccountMenu />
      </div>
    </div>
  )
}

function Group({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <section aria-label={label}>
      <h2 className="px-2 pb-1 text-2xs font-medium uppercase tracking-wider text-muted-foreground">
        {label}
      </h2>
      <div className="space-y-0.5">{children}</div>
    </section>
  )
}
