import {
  createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode,
} from 'react'
import type { ChatSession, Message } from '../types'
import { useChatController } from '../hooks/useChatController'
import { useSessions, type SessionsApi } from '../hooks/useSessions'
import { newChatId } from '../lib/id'

/**
 * Everything about conversations, in one provider above the router.
 *
 * It lives above the routes rather than inside them because a streaming answer must survive
 * navigation: opening another conversation while one is generating, then coming back, has to find
 * the answer still arriving. State inside a route component would be unmounted by the first click.
 */

export interface ChatContextValue extends SessionsApi {
  /** Free-text filter applied to the conversation list; also used by the command palette. */
  search: string
  setSearch: (value: string) => void

  messagesFor: (chatId: string | null) => Message[]
  isLoadingTranscript: (chatId: string | null) => boolean
  transcriptError: (chatId: string | null) => string | null
  openTranscript: (chatId: string) => void

  streamingChatId: string | null
  isStreaming: (chatId: string | null) => boolean
  send: (chatId: string, question: string) => void
  retry: (chatId: string) => void
  stop: () => void

  /** True while a conversation exists only in this browser tab. */
  isDraft: (chatId: string | null) => boolean
  /** A conversation to type into. Reuses the current untouched draft rather than making another. */
  startDraft: () => string
  deleteConversation: (chatId: string) => void
  sessionFor: (chatId: string | null) => ChatSession | null
}

const ChatContext = createContext<ChatContextValue | null>(null)

export function ChatProvider({ children }: { children: ReactNode }) {
  const [search, setSearch] = useState('')

  /**
   * The conversations that exist only here, and have not reached the server.
   *
   * Held in a ref as well as in state because the two are read on different clocks. `isDraft` is
   * read while rendering, so it needs state; `openTranscript` is read in the same tick as the
   * `startDraft` that minted the id, so it needs an answer that does not wait for a re-render.
   * Reading the state snapshot there is what used to send a brand-new conversation's id to the
   * server and turn its 404 into "Could not load this conversation".
   */
  const draftsRef = useRef<Set<string>>(new Set())
  const [draftIds, setDraftIds] = useState<Set<string>>(() => new Set())

  const loadedRef = useRef<Set<string>>(new Set())

  const sessions = useSessions(search)
  const { upsert, applyTitle } = sessions

  /** Publishes the ref for rendering. Never called during a render of another component. */
  const publishDrafts = useCallback(() => setDraftIds(new Set(draftsRef.current)), [])

  const forgetDraft = useCallback((chatId: string) => {
    if (!draftsRef.current.delete(chatId)) return
    publishDrafts()
  }, [publishDrafts])

  const onSessionRecorded = useCallback(({ chatId, title }: { chatId: string; title: string | null }) => {
    upsert({ chatId, title })
    // It is the server's conversation now, not a local draft.
    forgetDraft(chatId)
  }, [forgetDraft, upsert])

  const onTitleRefined = useCallback(({ chatId, title }: { chatId: string; title: string | null }) => {
    if (title) applyTitle(chatId, title)
  }, [applyTitle])

  const controller = useChatController({ onSessionRecorded, onTitleRefined })
  const {
    messagesByChat, loadingChatId, loadErrorByChat, streamingChatId,
    loadTranscript, sendMessage, retry, stopStreaming, discardChat,
  } = controller

  /**
   * The transcripts, readable without being a dependency.
   *
   * `startDraft` needs to know which drafts are empty. Taking `messagesByChat` as a dependency
   * would rebuild it on every streamed token, and with it every callback memoised against it.
   */
  const messagesRef = useRef(messagesByChat)
  messagesRef.current = messagesByChat

  const isDraft = useCallback(
    (chatId: string | null) => chatId !== null && draftIds.has(chatId),
    [draftIds],
  )

  /**
   * Loads a conversation's transcript once.
   *
   * Drafts are skipped outright — they have no server-side transcript to read. Everything else is
   * fetched, and the answer decides: a conversation the server has never heard of is one nobody
   * has asked a question in yet, so it becomes a draft here and opens as an empty chat. That is
   * what makes a conversation's URL survive a reload, a bookmark and a second tab, none of which
   * carry this tab's memory of which ids it minted.
   */
  const openTranscript = useCallback((chatId: string) => {
    if (draftsRef.current.has(chatId) || loadedRef.current.has(chatId)) return
    loadedRef.current.add(chatId)

    void loadTranscript(chatId).then(outcome => {
      // A conversation with nothing in it is a draft however we found that out: a 404 because the
      // server has never recorded it, or a read that came back empty. Both mean the reader has yet
      // to ask anything, and the route reads this flag to tell "empty" apart from "not read yet".
      //
      // The emptiness comes from the outcome itself rather than from re-reading `messagesRef`
      // here: that ref is only synced to state on the next render, and this callback can run
      // before one happens — reading it back turned a handful of real, answered conversations into
      // permanent drafts, greeting the reader over their own transcript every time they reopened
      // one.
      if (outcome === 'unsaved' || outcome === 'loaded-empty') {
        draftsRef.current.add(chatId)
        publishDrafts()
        return
      }
      // A request that broke is worth another attempt the next time the reader opens it; keeping
      // the id marked as loaded would make the failure permanent for the life of the tab.
      if (outcome === 'failed') loadedRef.current.delete(chatId)
    })
  }, [loadTranscript, publishDrafts])

  /**
   * Returns a conversation to type into.
   *
   * Safe to call from an event handler or an effect, and it registers the new id synchronously so
   * the route that renders next can be told it is a draft without waiting for this state to land.
   */
  const startDraft = useCallback((): string => {
    // An untouched draft is reused, so clicking "New chat" ten times leaves one empty chat rather
    // than ten. A draft carrying a failed read is not untouched: handing it back would make
    // "New chat" a button that returns the reader to the error they are trying to leave.
    const untouched = [...draftsRef.current].find(id =>
      (messagesRef.current[id] ?? []).length === 0 && !loadErrorByChat[id])
    if (untouched) return untouched

    const chatId = newChatId()
    draftsRef.current.add(chatId)
    publishDrafts()
    return chatId
  }, [loadErrorByChat, publishDrafts])

  const deleteConversation = useCallback((chatId: string) => {
    sessions.remove(chatId)
    discardChat(chatId)
    loadedRef.current.delete(chatId)
    forgetDraft(chatId)
  }, [discardChat, forgetDraft, sessions])

  const value = useMemo<ChatContextValue>(() => ({
    ...sessions,
    search,
    setSearch,

    messagesFor: chatId => (chatId ? messagesByChat[chatId] ?? [] : []),
    isLoadingTranscript: chatId => chatId !== null && loadingChatId === chatId,
    transcriptError: chatId => (chatId ? loadErrorByChat[chatId] ?? null : null),
    openTranscript,

    streamingChatId,
    isStreaming: chatId => chatId !== null && streamingChatId === chatId,
    send: (chatId, question) => { void sendMessage(chatId, question) },
    retry: chatId => { void retry(chatId) },
    stop: stopStreaming,

    isDraft,
    startDraft,
    deleteConversation,
    sessionFor: chatId =>
      (chatId ? sessions.sessions.find(session => session.chatId === chatId) ?? null : null),
  }), [
    sessions, search, messagesByChat, loadingChatId, loadErrorByChat, openTranscript,
    streamingChatId, sendMessage, retry, stopStreaming, isDraft, startDraft, deleteConversation,
  ])

  return <ChatContext.Provider value={value}>{children}</ChatContext.Provider>
}

export function useChat(): ChatContextValue {
  const context = useContext(ChatContext)
  if (!context) throw new Error('useChat must be used inside ChatProvider')
  return context
}
