import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Dialog, DialogPanel, DialogTitle, Transition, TransitionChild } from '@headlessui/react'
import { Command } from 'cmdk'
import { MessageSquare, Moon, Plus, Settings, ShieldCheck, Sun } from 'lucide-react'
import { useAuth } from '../../context/AuthContext'
import { useChat } from '../../context/ChatContext'
import { useTheme } from '../../context/ThemeContext'
import { fetchSessions } from '../../services/chatService'
import { stripHighlights } from '../../lib/highlight'
import type { ChatSession } from '../../types'

interface CommandPaletteProps {
  open: boolean
  onClose: () => void
}

/**
 * ⌘K: jump to a conversation or run an action without touching the sidebar.
 *
 * Its own search rather than the sidebar's: the palette should find a conversation regardless of
 * what the sidebar is currently filtered to, and closing it must not leave the sidebar filtered
 * by whatever was typed here.
 *
 * `shouldFilter={false}` — the matching is the server's full-text search over transcripts, which
 * finds conversations by a phrase inside them. cmdk's client-side filter would then discard
 * exactly those results, because the phrase is not in the title.
 */
export default function CommandPalette({ open, onClose }: CommandPaletteProps) {
  const navigate = useNavigate()
  const chat = useChat()
  const { theme, setTheme } = useTheme()
  const { isAdmin } = useAuth()

  const [query, setQuery] = useState('')
  const [results, setResults] = useState<ChatSession[]>([])
  const [searching, setSearching] = useState(false)

  useEffect(() => {
    if (!open) return

    const trimmed = query.trim()
    if (!trimmed) {
      setResults(chat.sessions.slice(0, 8))
      setSearching(false)
      return
    }

    // Debounced, and cancelled by the cleanup so a slow response for an old query cannot
    // overwrite the results of a newer one.
    let cancelled = false
    setSearching(true)

    const timer = setTimeout(() => {
      fetchSessions({ q: trimmed, limit: 12 })
        .then(page => { if (!cancelled) setResults(page.items) })
        .catch(() => { if (!cancelled) setResults([]) })
        .finally(() => { if (!cancelled) setSearching(false) })
    }, 180)

    return () => { cancelled = true; clearTimeout(timer) }
  }, [chat.sessions, open, query])

  useEffect(() => { if (!open) setQuery('') }, [open])

  function go(path: string) {
    onClose()
    navigate(path)
  }

  return (
    <Transition show={open}>
      <Dialog onClose={onClose} className="relative z-palette">
        <TransitionChild
          enter="duration-fast ease-out-expo" enterFrom="opacity-0" enterTo="opacity-100"
          leave="duration-fast ease-out-expo" leaveFrom="opacity-100" leaveTo="opacity-0"
        >
          <div className="fixed inset-0 bg-foreground/40 backdrop-blur-[2px]" aria-hidden="true" />
        </TransitionChild>

        <div className="fixed inset-0 flex items-start justify-center p-4 pt-[12vh]">
          <TransitionChild
            enter="duration-base ease-out-expo" enterFrom="opacity-0 scale-95" enterTo="opacity-100 scale-100"
            leave="duration-fast ease-out-expo" leaveFrom="opacity-100 scale-100" leaveTo="opacity-0 scale-95"
          >
            <DialogPanel className="w-full max-w-xl overflow-hidden rounded-2xl border border-border bg-surface shadow-overlay">
              <DialogTitle className="sr-only">Search conversations and run commands</DialogTitle>

              <Command shouldFilter={false} loop>
                <Command.Input
                  autoFocus
                  value={query}
                  onValueChange={setQuery}
                  placeholder="Search conversations or type a command…"
                  className="w-full border-b border-border bg-transparent px-4 py-3.5 text-sm text-foreground outline-none placeholder:text-muted-foreground"
                />

                <Command.List className="max-h-[min(24rem,60vh)] overflow-y-auto p-2">
                  <Command.Empty className="px-3 py-8 text-center text-sm text-muted-foreground">
                    {searching ? 'Searching…' : 'No matches.'}
                  </Command.Empty>

                  <Command.Group
                    heading="Actions"
                    className="[&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:pb-1 [&_[cmdk-group-heading]]:text-2xs [&_[cmdk-group-heading]]:font-medium [&_[cmdk-group-heading]]:uppercase [&_[cmdk-group-heading]]:tracking-wider [&_[cmdk-group-heading]]:text-muted-foreground"
                  >
                    <Item icon={<Plus size={15} />} onSelect={() => go(`/chat/${chat.startDraft()}`)}>
                      New chat
                    </Item>
                    <Item icon={<Settings size={15} />} onSelect={() => go('/settings')}>
                      Settings
                    </Item>
                    {isAdmin && (
                      <Item icon={<ShieldCheck size={15} />} onSelect={() => go('/admin')}>
                        Admin
                      </Item>
                    )}
                    <Item
                      icon={theme === 'dark' ? <Sun size={15} /> : <Moon size={15} />}
                      onSelect={() => { setTheme(theme === 'dark' ? 'light' : 'dark'); onClose() }}
                    >
                      Switch to {theme === 'dark' ? 'light' : 'dark'} theme
                    </Item>
                  </Command.Group>

                  {results.length > 0 && (
                    <Command.Group
                      heading={query.trim() ? 'Matching conversations' : 'Recent'}
                      className="mt-2 [&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:pb-1 [&_[cmdk-group-heading]]:text-2xs [&_[cmdk-group-heading]]:font-medium [&_[cmdk-group-heading]]:uppercase [&_[cmdk-group-heading]]:tracking-wider [&_[cmdk-group-heading]]:text-muted-foreground"
                    >
                      {results.map(session => (
                        <Item
                          key={session.chatId}
                          value={session.chatId}
                          icon={<MessageSquare size={15} />}
                          onSelect={() => go(`/chat/${session.chatId}`)}
                        >
                          <span className="min-w-0">
                            <span className="block truncate">{session.title ?? 'New chat'}</span>
                            {session.match?.snippet && (
                              <span className="block truncate text-2xs text-muted-foreground">
                                {stripHighlights(session.match.snippet)}
                              </span>
                            )}
                          </span>
                        </Item>
                      ))}
                    </Command.Group>
                  )}
                </Command.List>
              </Command>
            </DialogPanel>
          </TransitionChild>
        </div>
      </Dialog>
    </Transition>
  )
}

function Item({
  icon, children, onSelect, value,
}: {
  icon: React.ReactNode
  children: React.ReactNode
  onSelect: () => void
  value?: string
}) {
  return (
    <Command.Item
      value={value}
      onSelect={onSelect}
      className="flex cursor-pointer items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm text-foreground data-[selected=true]:bg-surface-hover"
    >
      <span aria-hidden="true" className="shrink-0 text-muted-foreground">{icon}</span>
      {children}
    </Command.Item>
  )
}
