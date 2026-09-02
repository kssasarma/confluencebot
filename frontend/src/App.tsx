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
import { useChatController } from './hooks/useChatController'

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
  const chat = useChatController()

  const [draft, setDraft] = useState('')
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)
  const [showSettings, setShowSettings] = useState(false)
  const [showChatPrefs, setShowChatPrefs] = useState(false)
  const [showAdmin, setShowAdmin] = useState(false)

  function handleSend() {
    const question = draft.trim()
    if (!question || chat.isStreaming) return
    setDraft('')
    void chat.sendMessage(question)
  }

  function handleAsk(question: string) {
    if (chat.isStreaming) return
    setDraft('')
    void chat.sendMessage(question)
  }

  // Per-conversation settings only exist once the conversation does.
  const canConfigureChat = chat.activeSession !== null

  return (
    <div className="flex h-screen bg-background overflow-hidden">
      <Sidebar
        sessions={chat.sessions}
        activeChatId={chat.activeChatId}
        draftChatId={chat.draftChatId}
        isOnEmptyDraft={chat.isOnEmptyDraft}
        onCreateSession={chat.startNewChat}
        onSelectSession={chat.selectChat}
        onDeleteSession={chatId => void chat.removeChat(chatId)}
        onPinSession={chatId => void chat.togglePin(chatId)}
        onRenameSession={(chatId, title) => void chat.renameChat(chatId, title)}
        isCollapsed={sidebarCollapsed}
        onToggleCollapse={() => setSidebarCollapsed(collapsed => !collapsed)}
        onOpenSettings={() => setShowSettings(true)}
        onOpenAdmin={() => setShowAdmin(true)}
      />

      <main className="flex-1 flex flex-col min-w-0 relative">
        {canConfigureChat && (
          <div className="absolute top-3 right-3 z-10">
            <button
              onClick={() => setShowChatPrefs(true)}
              className="text-xs text-muted-foreground border border-border rounded-lg px-2.5 py-1.5 hover:bg-surface transition-colors bg-background"
            >
              Chat settings
            </button>
          </div>
        )}
        <ChatArea
          chatId={chat.activeChatId}
          messages={chat.messages}
          isLoading={chat.isLoadingMessages}
          isStreaming={chat.isStreaming}
          draft={draft}
          onDraftChange={setDraft}
          onSend={handleSend}
          onStop={chat.stopStreaming}
          onAsk={handleAsk}
        />
      </main>

      {showSettings && <UserPreferencesPage onClose={() => setShowSettings(false)} />}
      {showAdmin && <AdminPage onClose={() => setShowAdmin(false)} />}
      {showChatPrefs && chat.activeSession && (
        <ChatPreferencesPanel
          chatId={chat.activeSession.chatId}
          onClose={() => setShowChatPrefs(false)}
        />
      )}
    </div>
  )
}
