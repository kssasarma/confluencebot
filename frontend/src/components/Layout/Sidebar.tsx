import { useState } from 'react'
import {
  MessageSquare, Plus, Pin, Trash2, ChevronLeft, ChevronRight,
  Settings, LogOut, ShieldCheck, Pencil, Check, X,
} from 'lucide-react'
import { cn } from '../../lib/cn'
import { useAuth } from '../../context/AuthContext'
import { relativeTime } from '../../lib/time'
import type { ChatSession } from '../../types'

interface SidebarProps {
  sessions: ChatSession[]
  activeChatId: string | null
  /** The unsaved conversation, shown at the top until its first question is asked. */
  draftChatId: string | null
  /** True when "New chat" would be a no-op because an empty one is already open. */
  isOnEmptyDraft: boolean
  onCreateSession: () => void
  onSelectSession: (chatId: string) => void
  onDeleteSession: (chatId: string) => void
  onPinSession: (chatId: string) => void
  onRenameSession: (chatId: string, title: string) => void
  isCollapsed: boolean
  onToggleCollapse: () => void
  onOpenSettings: () => void
  onOpenAdmin: () => void
}

export default function Sidebar({
  sessions, activeChatId, draftChatId, isOnEmptyDraft,
  onCreateSession, onSelectSession, onDeleteSession, onPinSession, onRenameSession,
  isCollapsed, onToggleCollapse, onOpenSettings, onOpenAdmin,
}: SidebarProps) {
  const { user, logout, isAdmin } = useAuth()
  const [renamingId, setRenamingId] = useState<string | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null)

  const pinned = sessions.filter(s => s.pinned)
  const recent = sessions.filter(s => !s.pinned)
  const draftIsUnsaved = draftChatId !== null && !sessions.some(s => s.chatId === draftChatId)

  function startRename(session: ChatSession) {
    setRenamingId(session.chatId)
    setRenameValue(session.title ?? '')
  }

  function commitRename(chatId: string) {
    if (renameValue.trim()) onRenameSession(chatId, renameValue.trim())
    setRenamingId(null)
  }

  const listProps = {
    activeChatId,
    renamingId,
    renameValue,
    pendingDeleteId,
    onSelect: onSelectSession,
    onAskDelete: setPendingDeleteId,
    onConfirmDelete: (chatId: string) => { setPendingDeleteId(null); onDeleteSession(chatId) },
    onCancelDelete: () => setPendingDeleteId(null),
    onPin: onPinSession,
    onStartRename: startRename,
    onChangeRename: setRenameValue,
    onCommitRename: commitRename,
    onCancelRename: () => setRenamingId(null),
  }

  return (
    <div className={cn(
      'flex flex-col h-full bg-surface border-r border-border transition-all duration-200',
      isCollapsed ? 'w-14' : 'w-64',
    )}>
      <div className="flex items-center justify-between p-3 border-b border-border">
        {!isCollapsed && <span className="font-semibold text-sm text-foreground truncate">Confluence Bot</span>}
        <button
          onClick={onToggleCollapse}
          className="p-1.5 rounded-md hover:bg-surface-hover text-muted-foreground ml-auto"
          title={isCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        >
          {isCollapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}
        </button>
      </div>

      <div className="p-2">
        <button
          onClick={onCreateSession}
          disabled={isOnEmptyDraft}
          title={isOnEmptyDraft ? 'You are already in a new chat' : 'New chat'}
          className={cn(
            'flex items-center gap-2 w-full rounded-lg p-2 text-sm font-medium transition-colors',
            isOnEmptyDraft
              ? 'text-muted-foreground cursor-not-allowed'
              : 'text-foreground hover:bg-surface-hover',
            isCollapsed && 'justify-center',
          )}
        >
          <Plus size={16} />
          {!isCollapsed && 'New chat'}
        </button>
      </div>

      <div className="flex-1 overflow-y-auto px-2 py-1 space-y-4">
        {!isCollapsed && (
          <>
            {draftIsUnsaved && (
              <button
                onClick={() => onSelectSession(draftChatId)}
                className={cn(
                  'flex items-center gap-2 w-full rounded-lg px-2 py-1.5 text-sm text-left',
                  activeChatId === draftChatId ? 'bg-muted text-foreground' : 'text-muted-foreground hover:bg-surface-hover',
                )}
              >
                <MessageSquare size={14} className="flex-shrink-0" />
                <span className="truncate">New chat</span>
                <span className="ml-auto text-[10px] uppercase tracking-wider text-muted-foreground">draft</span>
              </button>
            )}

            {pinned.length > 0 && (
              <div>
                <SectionLabel>Pinned</SectionLabel>
                <SessionList sessions={pinned} {...listProps} />
              </div>
            )}

            <div>
              {pinned.length > 0 && <SectionLabel>Recent</SectionLabel>}
              {recent.length === 0 && !draftIsUnsaved ? (
                <p className="text-xs text-muted-foreground px-2 py-3">
                  No conversations yet. Ask a question to start one.
                </p>
              ) : (
                <SessionList sessions={recent} {...listProps} />
              )}
            </div>
          </>
        )}

        {isCollapsed && sessions.map(session => (
          <button
            key={session.chatId}
            onClick={() => onSelectSession(session.chatId)}
            title={session.title ?? 'New chat'}
            className={cn(
              'w-full flex justify-center p-2 rounded-lg hover:bg-surface-hover',
              activeChatId === session.chatId && 'bg-muted',
            )}
          >
            <MessageSquare size={16} className="text-muted-foreground" />
          </button>
        ))}
      </div>

      <div className={cn('border-t border-border p-2 space-y-1', isCollapsed && 'items-center')}>
        {isAdmin && (
          <button onClick={onOpenAdmin}
            className={cn('flex items-center gap-2 w-full rounded-lg p-2 text-sm hover:bg-surface-hover text-muted-foreground', isCollapsed && 'justify-center')}
          >
            <ShieldCheck size={16} />
            {!isCollapsed && 'Admin'}
          </button>
        )}
        <button onClick={onOpenSettings}
          className={cn('flex items-center gap-2 w-full rounded-lg p-2 text-sm hover:bg-surface-hover text-muted-foreground', isCollapsed && 'justify-center')}
        >
          <Settings size={16} />
          {!isCollapsed && 'Settings'}
        </button>
        {!isCollapsed && user && (
          <div className="flex items-center justify-between px-2 py-1">
            <span className="text-xs text-muted-foreground truncate">{user.email}</span>
            <button onClick={logout} className="p-1 rounded hover:bg-surface-hover text-muted-foreground" title="Sign out">
              <LogOut size={14} />
            </button>
          </div>
        )}
        {isCollapsed && (
          <button onClick={logout} className="flex justify-center w-full p-2 rounded-lg hover:bg-surface-hover text-muted-foreground" title="Sign out">
            <LogOut size={16} />
          </button>
        )}
      </div>
    </div>
  )
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider px-2 mb-1">
      {children}
    </p>
  )
}

interface SessionListProps {
  sessions: ChatSession[]
  activeChatId: string | null
  renamingId: string | null
  renameValue: string
  pendingDeleteId: string | null
  onSelect: (chatId: string) => void
  onAskDelete: (chatId: string) => void
  onConfirmDelete: (chatId: string) => void
  onCancelDelete: () => void
  onPin: (chatId: string) => void
  onStartRename: (session: ChatSession) => void
  onChangeRename: (value: string) => void
  onCommitRename: (chatId: string) => void
  onCancelRename: () => void
}

function SessionList({
  sessions, activeChatId, renamingId, renameValue, pendingDeleteId,
  onSelect, onAskDelete, onConfirmDelete, onCancelDelete,
  onPin, onStartRename, onChangeRename, onCommitRename, onCancelRename,
}: SessionListProps) {
  return (
    <div className="space-y-0.5">
      {sessions.map(session => {
        const isRenaming = renamingId === session.chatId
        const isDeleting = pendingDeleteId === session.chatId

        return (
          <div
            key={session.chatId}
            className={cn(
              'group flex items-center rounded-lg hover:bg-surface-hover',
              activeChatId === session.chatId && 'bg-muted',
            )}
          >
            {isRenaming ? (
              <input
                autoFocus
                value={renameValue}
                onChange={e => onChangeRename(e.target.value)}
                onBlur={() => onCommitRename(session.chatId)}
                onKeyDown={e => {
                  if (e.key === 'Enter') onCommitRename(session.chatId)
                  if (e.key === 'Escape') onCancelRename()
                }}
                className="flex-1 bg-transparent text-sm px-2 py-1.5 outline-none ring-1 ring-primary rounded-lg"
              />
            ) : isDeleting ? (
              <div className="flex items-center justify-between w-full px-2 py-1.5 gap-1">
                <span className="text-xs text-muted-foreground truncate">Delete this chat?</span>
                <div className="flex items-center gap-0.5">
                  <button
                    onClick={() => onConfirmDelete(session.chatId)}
                    className="p-1 rounded hover:bg-danger/10 text-danger"
                    title="Confirm delete"
                  >
                    <Check size={13} />
                  </button>
                  <button onClick={onCancelDelete} className="p-1 rounded hover:bg-muted text-muted-foreground" title="Cancel">
                    <X size={13} />
                  </button>
                </div>
              </div>
            ) : (
              <>
                <button
                  onClick={() => onSelect(session.chatId)}
                  className="flex-1 min-w-0 text-left px-2 py-1.5"
                  title={session.title ?? 'New chat'}
                >
                  <span className="block text-sm text-foreground truncate">
                    {session.title ?? 'New chat'}
                  </span>
                  <span className="block text-[10px] text-muted-foreground">
                    {relativeTime(session.updatedAt)}
                    {session.messageCount > 0 && ` · ${session.messageCount} messages`}
                  </span>
                </button>
                <div className="hidden group-hover:flex items-center gap-0.5 pr-1">
                  <button
                    onClick={() => onPin(session.chatId)}
                    className="p-1 rounded hover:bg-muted text-muted-foreground"
                    title={session.pinned ? 'Unpin' : 'Pin'}
                  >
                    <Pin size={12} className={session.pinned ? 'text-primary fill-primary' : ''} />
                  </button>
                  <button
                    onClick={() => onStartRename(session)}
                    className="p-1 rounded hover:bg-muted text-muted-foreground"
                    title="Rename"
                  >
                    <Pencil size={12} />
                  </button>
                  <button
                    onClick={() => onAskDelete(session.chatId)}
                    className="p-1 rounded hover:bg-muted text-danger"
                    title="Delete"
                  >
                    <Trash2 size={12} />
                  </button>
                </div>
              </>
            )}
          </div>
        )
      })}
    </div>
  )
}
