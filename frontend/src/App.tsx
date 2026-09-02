import { useState } from 'react'
import { useAuth } from './context/AuthContext'
import LoginPage from './components/Auth/LoginPage'
import ChangePasswordPage from './components/Auth/ChangePasswordPage'
import Sidebar from './components/Layout/Sidebar'
import ChatArea from './components/Chat/ChatArea'
import UserPreferencesPage from './components/Settings/UserPreferencesPage'
import ChatPreferencesPanel from './components/Settings/ChatPreferencesPanel'
import AdminPage from './components/Admin/AdminPage'
import Spinner from './components/ui/Spinner'
import { useChatSessions } from './hooks/useChatSessions'
import { updateSession } from './services/chatService'

export default function App() {
  const { user, isLoading } = useAuth()

  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-background">
        <Spinner size="lg" />
      </div>
    )
  }

  if (!user) return <LoginPage />
  if (user.mustChangePassword) return <ChangePasswordPage />
  return <MainLayout />
}

function MainLayout() {
  const {
    sessions, activeSessionId, activeSession,
    createSession, deleteSession, selectSession, updateSessionLocal,
  } = useChatSessions()

  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)
  const [showSettings, setShowSettings] = useState(false)
  const [showChatPrefs, setShowChatPrefs] = useState(false)
  const [showAdmin, setShowAdmin] = useState(false)

  async function handleRename(chatId: string, title: string) {
    await updateSession(chatId, { title })
    updateSessionLocal(chatId, { title })
  }

  async function handlePin(chatId: string) {
    const s = sessions.find(x => x.chatId === chatId)
    if (!s) return
    const pinned = !s.pinned
    await updateSession(chatId, { pinned })
    updateSessionLocal(chatId, { pinned })
  }

  function handleFirstMessage(sessionId: string, firstMsg: string) {
    const title = firstMsg.slice(0, 50) + (firstMsg.length > 50 ? '…' : '')
    handleRename(sessionId, title)
  }

  return (
    <div className="flex h-screen bg-background overflow-hidden">
      <Sidebar
        sessions={sessions}
        activeSessionId={activeSessionId}
        onCreateSession={createSession}
        onSelectSession={selectSession}
        onDeleteSession={deleteSession}
        onPinSession={handlePin}
        onRenameSession={handleRename}
        isCollapsed={sidebarCollapsed}
        onToggleCollapse={() => setSidebarCollapsed(c => !c)}
        onOpenSettings={() => setShowSettings(true)}
        onOpenAdmin={() => setShowAdmin(true)}
      />

      <main className="flex-1 flex flex-col min-w-0 relative">
        {activeSession && (
          <div className="absolute top-3 right-3 z-10">
            <button
              onClick={() => setShowChatPrefs(true)}
              className="text-xs text-muted-foreground border border-border rounded-lg px-2.5 py-1.5 hover:bg-surface transition-colors bg-background"
            >
              Chat settings
            </button>
          </div>
        )}
        <ChatArea session={activeSession ?? null} onFirstMessage={handleFirstMessage} />
      </main>

      {showSettings && <UserPreferencesPage onClose={() => setShowSettings(false)} />}
      {showAdmin && <AdminPage onClose={() => setShowAdmin(false)} />}
      {showChatPrefs && activeSession && (
        <ChatPreferencesPanel chatId={activeSession.chatId} onClose={() => setShowChatPrefs(false)} />
      )}
    </div>
  )
}
