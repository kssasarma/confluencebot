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
  const [draftIds, setDraftIds] = useState<Set<string>>(() => new Set())
  const loadedRef = useRef<Set<string>>(new Set())

  const sessions = useSessions(search)
  const { upsert, applyTitle } = sessions

  const onSessionRecorded = useCallback(({ chatId, title }: { chatId: string; title: string | null }) => {
    upsert({ chatId, title })
    // It is the server's conversation now, not a local draft.
    setDraftIds(current => {
      if (!current.has(chatId)) return current
      const next = new Set(current)
      next.delete(chatId)
      return next
    })
  }, [upsert])

  const onTitleRefined = useCallback(({ chatId, title }: { chatId: string; title: string | null }) => {
    if (title) applyTitle(chatId, title)
  }, [applyTitle])

  const controller = useChatController({ onSessionRecorded, onTitleRefined })
  const {
    messagesByChat, loadingChatId, loadErrorByChat, streamingChatId,
    loadTranscript, sendMessage, retry, stopStreaming, discardChat,
  } = controller

  const isDraft = useCallback(
    (chatId: string | null) => chatId !== null && draftIds.has(chatId),
    [draftIds],
  )

  /**
   * Loads a conversation's transcript once.
   *
   * Drafts are skipped: they have no server-side transcript, and requesting one would 404 on
   * every render of a brand-new chat.
   */
  const openTranscript = useCallback((chatId: string) => {
    if (draftIds.has(chatId) || loadedRef.current.has(chatId)) return
    loadedRef.current.add(chatId)
    loadTranscript(chatId)
  }, [draftIds, loadTranscript])

  const startDraft = useCallback((): string => {
    // An untouched draft is reused, so clicking "New chat" ten times leaves one empty chat rather
    // than ten.
    const untouched = [...draftIds].find(id => (messagesByChat[id] ?? []).length === 0)
    if (untouched) return untouched

    const chatId = newChatId()
    setDraftIds(current => new Set(current).add(chatId))
    return chatId
  }, [draftIds, messagesByChat])

  const deleteConversation = useCallback((chatId: string) => {
    sessions.remove(chatId)
    discardChat(chatId)
    loadedRef.current.delete(chatId)
    setDraftIds(current => {
      if (!current.has(chatId)) return current
      const next = new Set(current)
      next.delete(chatId)
      return next
    })
  }, [discardChat, sessions])

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
