import { useState } from 'react'
import { MessageSquare, Plus, Pin, Trash2, ChevronLeft, ChevronRight, Settings, LogOut } from 'lucide-react'
import { cn } from '../../lib/cn'
import { useAuth } from '../../context/AuthContext'
import type { ChatSession } from '../../types'

interface SidebarProps {
  sessions: ChatSession[]
  activeSessionId: string | null
  onCreateSession: () => void
  onSelectSession: (chatId: string) => void
  onDeleteSession: (chatId: string) => void
  onPinSession: (chatId: string) => void
  onRenameSession: (chatId: string, title: string) => void
  isCollapsed: boolean
  onToggleCollapse: () => void
  onOpenSettings: () => void
}

export default function Sidebar({
  sessions, activeSessionId, onCreateSession, onSelectSession,
  onDeleteSession, onPinSession, onRenameSession,
  isCollapsed, onToggleCollapse, onOpenSettings,
}: SidebarProps) {
  const { user, logout } = useAuth()
  const [renamingId, setRenamingId] = useState<string | null>(null)
  const [renameValue, setRenameValue] = useState('')

  const pinned = sessions.filter(s => s.pinned)
  const recent = sessions.filter(s => !s.pinned)

  function startRename(s: ChatSession) {
    setRenamingId(s.chatId)
    setRenameValue(s.title ?? '')
  }

  function commitRename(chatId: string) {
    if (renameValue.trim()) onRenameSession(chatId, renameValue.trim())
    setRenamingId(null)
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
        >
          {isCollapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}
        </button>
      </div>

      <div className="p-2">
        <button
          onClick={onCreateSession}
          className={cn(
            'flex items-center gap-2 w-full rounded-lg p-2 text-sm font-medium transition-colors',
            'text-foreground hover:bg-surface-hover',
            isCollapsed && 'justify-center',
          )}
        >
          <Plus size={16} />
          {!isCollapsed && 'New chat'}
        </button>
      </div>

      <div className="flex-1 overflow-y-auto px-2 py-1 space-y-4">
        {!isCollapsed && pinned.length > 0 && (
          <div>
            <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider px-2 mb-1">Pinned</p>
            <SessionList sessions={pinned} activeId={activeSessionId} renamingId={renamingId}
              renameValue={renameValue} onSelect={onSelectSession} onDelete={onDeleteSession}
              onPin={onPinSession} onStartRename={startRename}
              onChangeRename={setRenameValue} onCommitRename={commitRename} />
          </div>
        )}
        {!isCollapsed && (
          <div>
            {pinned.length > 0 && <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider px-2 mb-1">Recent</p>}
            <SessionList sessions={recent} activeId={activeSessionId} renamingId={renamingId}
              renameValue={renameValue} onSelect={onSelectSession} onDelete={onDeleteSession}
              onPin={onPinSession} onStartRename={startRename}
              onChangeRename={setRenameValue} onCommitRename={commitRename} />
          </div>
        )}
        {isCollapsed && sessions.map(s => (
          <button key={s.chatId} onClick={() => onSelectSession(s.chatId)}
            title={s.title ?? 'Chat'}
            className={cn('w-full flex justify-center p-2 rounded-lg hover:bg-surface-hover', activeSessionId === s.chatId && 'bg-muted')}
          >
            <MessageSquare size={16} className="text-muted-foreground" />
          </button>
        ))}
      </div>

      <div className={cn('border-t border-border p-2 space-y-1', isCollapsed && 'items-center')}>
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

function SessionList({ sessions, activeId, renamingId, renameValue, onSelect, onDelete, onPin, onStartRename, onChangeRename, onCommitRename }: {
  sessions: ChatSession[]
  activeId: string | null
  renamingId: string | null
  renameValue: string
  onSelect: (id: string) => void
  onDelete: (id: string) => void
  onPin: (id: string) => void
  onStartRename: (s: ChatSession) => void
  onChangeRename: (v: string) => void
  onCommitRename: (id: string) => void
}) {
  return (
    <div className="space-y-0.5">
      {sessions.map(s => (
        <div key={s.chatId}
          className={cn('group flex items-center rounded-lg hover:bg-surface-hover cursor-pointer', activeId === s.chatId && 'bg-muted')}
          onClick={() => onSelect(s.chatId)}
        >
          {renamingId === s.chatId ? (
            <input
              autoFocus
              value={renameValue}
              onChange={e => onChangeRename(e.target.value)}
              onBlur={() => onCommitRename(s.chatId)}
              onKeyDown={e => { if (e.key === 'Enter') onCommitRename(s.chatId); if (e.key === 'Escape') onCommitRename(s.chatId) }}
              onClick={e => e.stopPropagation()}
              className="flex-1 bg-transparent text-sm px-2 py-1.5 outline-none"
            />
          ) : (
            <span className="flex-1 text-sm text-foreground truncate px-2 py-1.5">
              {s.title ?? 'New chat'}
            </span>
          )}
          <div className="hidden group-hover:flex items-center gap-0.5 pr-1">
            <button onClick={e => { e.stopPropagation(); onPin(s.chatId) }} className="p-1 rounded hover:bg-muted text-muted-foreground" title={s.pinned ? 'Unpin' : 'Pin'}>
              <Pin size={12} className={s.pinned ? 'text-primary fill-primary' : ''} />
            </button>
            <button onClick={e => { e.stopPropagation(); onStartRename(s) }} className="p-1 rounded hover:bg-muted text-muted-foreground" title="Rename">
              <svg className="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" /></svg>
            </button>
            <button onClick={e => { e.stopPropagation(); onDelete(s.chatId) }} className="p-1 rounded hover:bg-muted text-danger" title="Delete">
              <Trash2 size={12} />
            </button>
          </div>
        </div>
      ))}
    </div>
  )
}
