import { useEffect } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { AlertTriangle, SlidersHorizontal } from 'lucide-react'
import { useChat } from '../context/ChatContext'
import { useEffectiveDisplayPreferences } from '../hooks/usePreferences'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { useEventCallback } from '../hooks/useEventCallback'
import Button from '../components/ui/Button'
import EmptyState from '../components/ui/EmptyState'
import ErrorBoundary from '../components/ui/ErrorBoundary'
import IconButton from '../components/ui/IconButton'
import { SkeletonText } from '../components/ui/Skeleton'
import MessageList from '../components/chat/MessageList'
import { prefetchMarkdown } from '../components/chat/LazyMarkdown'
import Composer from '../components/chat/Composer'
import ChatHeader from '../components/chat/ChatHeader'
import WelcomePanel from '../components/chat/WelcomePanel'
import ChatPreferencesDialog from '../components/settings/ChatPreferencesDialog'
import { useState } from 'react'

/**
 * One conversation, addressed by URL.
 *
 * A conversation having its own route is what makes it shareable, bookmarkable, and survivable
 * across a reload — none of which was possible while the open chat was a `useState` in the root
 * component.
 */
export default function ChatRoute() {
  const { chatId = '' } = useParams()
  const navigate = useNavigate()
  const chat = useChat()
  const [showPreferences, setShowPreferences] = useState(false)

  const messages = chat.messagesFor(chatId)
  const session = chat.sessionFor(chatId)
  const isDraft = chat.isDraft(chatId)
  const isStreaming = chat.isStreaming(chatId)
  const loadError = chat.transcriptError(chatId)

  const { showSources, showConfidence } = useEffectiveDisplayPreferences(isDraft ? null : chatId)

  useDocumentTitle(session?.title ?? (messages.length > 0 ? 'Conversation' : 'New chat'))

  useEffect(() => {
    if (chatId) chat.openTranscript(chatId)
  }, [chat, chatId])

  // Warm the renderer while the reader is still typing, so the first answer never waits on it.
  useEffect(prefetchMarkdown, [])

  const lastQuestion = messages.findLast(message => message.role === 'user')?.content

  const ask = useEventCallback((question: string) => chat.send(chatId, question))
  const retry = useEventCallback(() => chat.retry(chatId))

  return (
    <div className="flex h-full min-h-0 flex-col">
      <ChatHeader
        chatId={chatId}
        title={session?.title ?? null}
        titleGenerated={session?.titleGenerated ?? false}
        onRename={title => chat.rename(chatId, title)}
        actions={
          !isDraft && (
            <IconButton
              label="Chat settings"
              icon={<SlidersHorizontal size={16} />}
              onClick={() => setShowPreferences(true)}
            />
          )
        }
      />

      {/*
        Keyed by conversation so a crash in one does not persist after switching away. Without the
        key React keeps the boundary's error state across the switch and every other conversation
        looks broken too.
      */}
      <ErrorBoundary key={chatId} title="This conversation could not be displayed">
        {chat.isLoadingTranscript(chatId) ? (
          <div className="mx-auto w-full max-w-3xl flex-1 px-4 py-6" aria-busy="true">
            <SkeletonText lines={4} className="mb-8" />
            <SkeletonText lines={6} />
          </div>
        ) : loadError ? (
          <div className="flex flex-1 items-center justify-center">
            <EmptyState
              tone="error"
              icon={<AlertTriangle size={18} />}
              title="Could not load this conversation"
              description={loadError}
              action={
                <div className="flex gap-2">
                  <Button variant="secondary" onClick={() => window.location.reload()}>
                    Reload
                  </Button>
                  <Button onClick={() => navigate('/')}>Start a new chat</Button>
                </div>
              }
            />
          </div>
        ) : messages.length === 0 ? (
          <div className="min-h-0 flex-1 overflow-y-auto">
            <WelcomePanel onSelect={ask} />
          </div>
        ) : (
          <MessageList
            messages={messages}
            showSources={showSources}
            showConfidence={showConfidence}
            isStreaming={isStreaming}
            onAsk={ask}
            onRetry={retry}
          />
        )}
      </ErrorBoundary>

      <Composer
        chatId={chatId}
        onSend={ask}
        onStop={chat.stop}
        isStreaming={isStreaming}
        lastQuestion={lastQuestion}
      />

      {showPreferences && (
        <ChatPreferencesDialog chatId={chatId} onClose={() => setShowPreferences(false)} />
      )}
    </div>
  )
}
