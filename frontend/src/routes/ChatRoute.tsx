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
  const isLoading = chat.isLoadingTranscript(chatId)
  const loadError = chat.transcriptError(chatId)

  /**
   * Whether the server holds this conversation yet.
   *
   * Per-conversation settings only exist once it does — reading or writing them any earlier is a
   * request the backend answers with a 404, because a conversation reaches the database on its
   * first answer and not before.
   */
  const isSaved = !isDraft && messages.length > 0

  const { showSources, showConfidence } = useEffectiveDisplayPreferences(isSaved ? chatId : null)

  useDocumentTitle(session?.title ?? (messages.length > 0 ? 'Conversation' : 'New chat'))

  // Depends on the conversation, not on the whole context: the context value changes on every
  // streamed token, and taking it as a dependency re-runs this on each one.
  const openTranscript = useEventCallback(chat.openTranscript)
  useEffect(() => {
    if (chatId) openTranscript(chatId)
  }, [chatId, openTranscript])

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
          isSaved && (
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

        Inside it, the transcript outranks the failure. A read that broke says nothing about the
        question the reader has just asked, and the answer to it streams into this conversation
        whether or not the earlier request worked — so a failure that replaced the transcript
        wholesale left them typing into a composer whose answers they could not see until they
        reloaded the page.
      */}
      <ErrorBoundary key={chatId} title="This conversation could not be displayed">
        {isLoading && messages.length === 0 ? (
          <div className="mx-auto w-full max-w-3xl flex-1 px-4 py-6" aria-busy="true">
            <SkeletonText lines={4} className="mb-8" />
            <SkeletonText lines={6} />
          </div>
        ) : messages.length > 0 ? (
          <MessageList
            messages={messages}
            showSources={showSources}
            showConfidence={showConfidence}
            isStreaming={isStreaming}
            onAsk={ask}
            onRetry={retry}
          />
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
        ) : (
          <div className="min-h-0 flex-1 overflow-y-auto">
            <WelcomePanel onSelect={ask} />
          </div>
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
