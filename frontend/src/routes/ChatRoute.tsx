import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { AlertTriangle, SlidersHorizontal } from 'lucide-react'
import { useAuth } from '../context/AuthContext'
import { useChat } from '../context/ChatContext'
import { useChatPreferences, useEffectiveDisplayPreferences } from '../hooks/usePreferences'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { useEventCallback } from '../hooks/useEventCallback'
import { useSuggestions } from '../hooks/useSuggestions'
import {
  clearPendingChatPreferences, readPendingChatPreferences, readSpaceFilter, writePendingChatPreferences,
  writeSpaceFilter,
} from '../hooks/usePersistentState'
import type { ChatPreferences } from '../types'
import Button from '../components/ui/Button'
import EmptyState from '../components/ui/EmptyState'
import ErrorBoundary from '../components/ui/ErrorBoundary'
import IconButton from '../components/ui/IconButton'
import { SkeletonText } from '../components/ui/Skeleton'
import MessageList from '../components/chat/MessageList'
import { prefetchMarkdown } from '../components/chat/LazyMarkdown'
import Composer from '../components/chat/Composer'
import ChatHeader from '../components/chat/ChatHeader'
import SpaceSelector from '../components/chat/SpaceSelector'
import { WelcomeGreeting, WelcomeSuggestions } from '../components/chat/WelcomePanel'
import ChatPreferencesDialog from '../components/settings/ChatPreferencesDialog'
import PendingChatPreferencesDialog from '../components/settings/PendingChatPreferencesDialog'

/**
 * One conversation, addressed by URL — or, with no id, the stateless welcome screen `/chat`
 * renders before any conversation exists.
 *
 * A conversation having its own route is what makes it shareable, bookmarkable, and survivable
 * across a reload — none of which was possible while the open chat was a `useState` in the root
 * component. `chatId` defaults to the empty string when the route carries none (`/chat` itself),
 * which every check below already treats the same way a not-yet-loaded id would: no session to
 * open a transcript for, no draft to be, nothing saved. That is deliberate — the welcome screen is
 * not a conversation waiting to be named, it is the absence of one, and nothing is created here
 * until the reader sends a first message.
 */
export default function ChatRoute() {
  const { chatId = '' } = useParams()
  const isWelcome = chatId === ''
  const navigate = useNavigate()
  const chat = useChat()
  const { user } = useAuth()
  const [showPreferences, setShowPreferences] = useState(false)
  const [spaceKey, setSpaceKey] = useState<string | null>(() => (chatId ? readSpaceFilter(chatId) : null))
  const [pendingPreferences, setPendingPreferences] = useState<ChatPreferences>(
    () => (chatId ? readPendingChatPreferences<ChatPreferences>(chatId) ?? {} : {}),
  )

  const { suggestions } = useSuggestions(spaceKey)

  const messages = chat.messagesFor(chatId)
  const session = chat.sessionFor(chatId)
  const isDraft = chat.isDraft(chatId)
  const isStreaming = chat.isStreaming(chatId)
  const loadError = chat.transcriptError(chatId)

  /**
   * Whether the server holds this conversation yet.
   *
   * Per-conversation settings only exist once it does — reading or writing them any earlier is a
   * request the backend answers with a 404, because a conversation reaches the database on its
   * first answer and not before.
   */
  const isSaved = !isWelcome && !isDraft && messages.length > 0

  const { showSources, showConfidence } = useEffectiveDisplayPreferences(isSaved ? chatId : null)

  // Only mounted once there is something to replay — most conversations never had a pending
  // preference and have no reason to pay for a preferences fetch they will not use. Gated on
  // `isSaved` as well as on the pending value itself: the endpoint this hook's `save` calls 404s
  // until the conversation has a row, i.e. until it stops being a draft.
  const hasPendingToFlush =
    isSaved && chatId !== '' && readPendingChatPreferences<ChatPreferences>(chatId) !== null
  const chatPreferences = useChatPreferences(hasPendingToFlush ? chatId : null)
  const flushPendingPreferences = useEventCallback(chatPreferences.save)

  // The bare welcome screen (`/chat`, nothing started yet) shows just the app name; once a
  // conversation exists it takes over the tab, first with a placeholder and then its own title.
  useDocumentTitle(isWelcome ? null : (session?.title ?? (messages.length > 0 ? 'Conversation' : 'New chat')))

  // Depends on the conversation, not on the whole context: the context value changes on every
  // streamed token, and taking it as a dependency re-runs this on each one.
  const openTranscript = useEventCallback(chat.openTranscript)
  useEffect(() => {
    if (chatId) openTranscript(chatId)
  }, [chatId, openTranscript])

  // Warm the renderer while the reader is still typing, so the first answer never waits on it.
  useEffect(prefetchMarkdown, [])

  // Each conversation keeps its own space filter, the same way the composer keeps its own draft.
  useEffect(() => setSpaceKey(chatId ? readSpaceFilter(chatId) : null), [chatId])

  // Mirrors the space filter above, but for the preference overrides chosen before this
  // conversation had anywhere to save them.
  useEffect(() => {
    setPendingPreferences(chatId ? (readPendingChatPreferences<ChatPreferences>(chatId) ?? {}) : {})
  }, [chatId])

  // The moment a conversation stops being a draft, it has a row on the server — so whatever
  // preferences were picked before that (on the welcome screen, or in this same still-empty draft)
  // can finally be saved for real. Nothing runs here if the reader never touched them.
  useEffect(() => {
    if (!hasPendingToFlush) return
    const pending = readPendingChatPreferences<ChatPreferences>(chatId)
    if (pending) flushPendingPreferences(pending)
    clearPendingChatPreferences(chatId)
  }, [chatId, hasPendingToFlush, flushPendingPreferences])

  const changeSpace = (next: string | null) => {
    setSpaceKey(next)
    if (chatId) writeSpaceFilter(chatId, next)
  }

  const updatePendingPreferences = (next: ChatPreferences) => {
    setPendingPreferences(next)
    if (chatId) writePendingChatPreferences(chatId, next)
  }

  const lastQuestion = messages.findLast(message => message.role === 'user')?.content

  /**
   * Sends the first message from the welcome screen.
   *
   * The conversation is named here, in the browser, rather than by a round trip to the server —
   * the id is what lets "New chat" avoid breeding an empty row every time it is clicked, and
   * minting it up front would reintroduce exactly that. Whatever the reader picked on this screen
   * — a space, a chat preference — is carried over to the new id before the answer starts, so it is
   * there for the effect above to save once the conversation itself exists.
   */
  const startAndAsk = (question: string) => {
    const id = chat.startDraft()
    writeSpaceFilter(id, spaceKey)
    if (Object.keys(pendingPreferences).length > 0) writePendingChatPreferences(id, pendingPreferences)
    navigate(`/chat/${id}`, { replace: true })
    chat.send(id, question, spaceKey)
  }

  const ask = useEventCallback((question: string) => {
    if (isWelcome) startAndAsk(question)
    else chat.send(chatId, question, spaceKey)
  })
  const retry = useEventCallback(() => chat.retry(chatId, spaceKey))

  /**
   * Nothing said here yet, and nothing preventing it being said.
   *
   * This is the one state where the composer is not a bar along the bottom: the greeting, the
   * question box and the suggestions become a single centred column, so that opening a new chat
   * looks like an invitation rather than like a conversation whose messages failed to load.
   *
   * The welcome screen itself (`isWelcome`) always qualifies — there is no server to ask, because
   * there is no id yet. An id that already exists is gated on being a draft, because a draft is the
   * only one we *know* is empty. An empty transcript otherwise means "not read yet": the fetch
   * starts in an effect, so the first render of any saved conversation — a reload, a bookmark, a
   * second tab, a click in the sidebar — has no messages and no request in flight, and greeting the
   * reader there flashed "Welcome, how may I help you?" over the top of every conversation they
   * opened. Conversations the server has never heard of become drafts when their read 404s, so the
   * genuinely-empty ones still land here too.
   */
  const showWelcome = isWelcome || (isDraft && messages.length === 0 && !loadError)

  /** Read but not yet resolved: neither a known-empty draft nor a transcript we hold. */
  const isPending = messages.length === 0 && !showWelcome && !loadError

  return (
    <div className="flex h-full min-h-0 flex-col">
      <ChatHeader
        chatId={chatId}
        title={session?.title ?? null}
        titleGenerated={session?.titleGenerated ?? false}
        onRename={title => chat.rename(chatId, title)}
        actions={
          <>
            <SpaceSelector value={spaceKey} onChange={changeSpace} />
            <IconButton
              label="Chat settings"
              icon={<SlidersHorizontal size={16} />}
              onClick={() => setShowPreferences(true)}
            />
          </>
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
        {isPending ? (
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
                  <Button onClick={() => navigate('/chat')}>Start a new chat</Button>
                </div>
              }
            />
          </div>
        ) : (
          <WelcomeGreeting name={user?.name ?? ''} />
        )}
      </ErrorBoundary>

      {/*
        The composer keeps this position in the tree in both layouts, and only its variant
        changes. Moving the element into the welcome column instead would remount it on the first
        question — dropping the caret out of the box at the exact moment the reader is most likely
        to type the next one.
      */}
      <Composer
        chatId={chatId}
        onSend={ask}
        onStop={chat.stop}
        isStreaming={isStreaming}
        lastQuestion={lastQuestion}
        variant={showWelcome ? 'centred' : 'docked'}
      />

      {/*
        Keyed by conversation as well, so that starting another new chat replays the entrance
        animation. Two empty conversations are identical to look at; the movement is what says
        one has been replaced by the other.

        The key is namespaced rather than the bare `chatId` the `ErrorBoundary` above uses: the two
        are siblings, and a shared key on siblings — even of different element types — is a
        duplicate key as far as React's reconciler is concerned. That collision showed up as a
        conversation with a real transcript still showing the greeting underneath it after the
        reader had switched away and back: React mismatched which committed DOM node belonged to
        which of the two identically-keyed elements, so removing the greeting outlived its own key.
      */}
      {showWelcome && (
        <WelcomeSuggestions key={`welcome-${chatId}`} suggestions={suggestions} onSelect={ask} />
      )}

      {showPreferences && (
        isSaved ? (
          <ChatPreferencesDialog chatId={chatId} onClose={() => setShowPreferences(false)} />
        ) : (
          <PendingChatPreferencesDialog
            value={pendingPreferences}
            onSave={updatePendingPreferences}
            onClose={() => setShowPreferences(false)}
          />
        )
      )}
    </div>
  )
}
