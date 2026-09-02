import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ChatSession, Message, Source } from '../types'
import {
  fetchSessions, fetchTranscript, streamChatMessage,
  updateSession as apiUpdateSession, deleteSession as apiDeleteSession,
} from '../services/chatService'

const newId = (): string =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`

export interface ChatController {
  sessions: ChatSession[]
  /** The conversation being typed into that does not exist on the server yet. */
  draftChatId: string | null
  activeChatId: string | null
  activeSession: ChatSession | null
  messages: Message[]
  isLoadingMessages: boolean
  streamingChatId: string | null
  isStreaming: boolean
  /** True when "New chat" would do nothing, because an empty new chat is already open. */
  isOnEmptyDraft: boolean
  startNewChat: () => void
  selectChat: (chatId: string) => void
  sendMessage: (text: string) => Promise<void>
  stopStreaming: () => void
  renameChat: (chatId: string, title: string) => Promise<void>
  togglePin: (chatId: string) => Promise<void>
  removeChat: (chatId: string) => Promise<void>
}

/**
 * Owns every piece of conversation state: the list, the transcripts and the in-flight answer.
 *
 * A new conversation starts as a draft that lives only in the browser and is created server-side
 * by the first question — clicking "New chat" ten times can no longer leave ten empty
 * conversations behind. Transcripts are kept per conversation, so switching away from a streaming
 * answer and back does not lose it.
 */
export function useChatController(): ChatController {
  const [sessions, setSessions] = useState<ChatSession[]>([])
  const [messagesByChat, setMessagesByChat] = useState<Record<string, Message[]>>({})
  const [activeChatId, setActiveChatId] = useState<string | null>(null)
  const [draftChatId, setDraftChatId] = useState<string | null>(null)
  const [loadingChatId, setLoadingChatId] = useState<string | null>(null)
  const [streamingChatId, setStreamingChatId] = useState<string | null>(null)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    let cancelled = false
    fetchSessions()
      .then(loaded => { if (!cancelled) setSessions(sortSessions(loaded)) })
      .catch(() => { /* the sidebar simply stays empty; the composer still works */ })
    return () => { cancelled = true }
  }, [])

  const messages = useMemo(
    () => (activeChatId ? messagesByChat[activeChatId] ?? [] : []),
    [activeChatId, messagesByChat],
  )

  const patchMessage = useCallback((chatId: string, messageId: string, patch: Partial<Message>) => {
    setMessagesByChat(prev => ({
      ...prev,
      [chatId]: (prev[chatId] ?? []).map(m => (m.id === messageId ? { ...m, ...patch } : m)),
    }))
  }, [])

  const appendToken = useCallback((chatId: string, messageId: string, delta: string) => {
    setMessagesByChat(prev => ({
      ...prev,
      [chatId]: (prev[chatId] ?? []).map(m =>
        m.id === messageId ? { ...m, content: m.content + delta } : m),
    }))
  }, [])

  /** Adds or refreshes the conversation in the sidebar once the server has recorded a turn. */
  const registerSession = useCallback((chatId: string, title: string | null) => {
    setSessions(prev => {
      const existing = prev.find(s => s.chatId === chatId)
      const updatedAt = new Date().toISOString()
      const next = existing
        ? prev.map(s => s.chatId === chatId
            ? { ...s, title: title ?? s.title, messageCount: s.messageCount + 2, updatedAt }
            : s)
        : [{ chatId, title, pinned: false, messageCount: 2, updatedAt }, ...prev]
      return sortSessions(next)
    })
    setDraftChatId(current => (current === chatId ? null : current))
  }, [])

  const isOnEmptyDraft =
    activeChatId !== null && activeChatId === draftChatId && messages.length === 0

  const startNewChat = useCallback(() => {
    // Already sitting in an untouched new chat: keep it instead of opening another.
    if (isOnEmptyDraft) return
    const chatId = newId()
    setDraftChatId(chatId)
    setActiveChatId(chatId)
    setMessagesByChat(prev => ({ ...prev, [chatId]: [] }))
  }, [isOnEmptyDraft])

  const selectChat = useCallback((chatId: string) => {
    setActiveChatId(chatId)
    if (messagesByChat[chatId]) return

    setLoadingChatId(chatId)
    fetchTranscript(chatId)
      .then(loaded => setMessagesByChat(current => ({ ...current, [chatId]: loaded })))
      .catch(() => setMessagesByChat(current => ({ ...current, [chatId]: [] })))
      .finally(() => setLoadingChatId(current => (current === chatId ? null : current)))
  }, [messagesByChat])

  /**
   * Reports a failed answer without throwing away what already arrived: a stream that dies
   * halfway keeps its partial text and gets the reason underneath it.
   */
  const reportFailure = useCallback((chatId: string, answerId: string, message: string) => {
    setMessagesByChat(prev => {
      const existing = prev[chatId] ?? []
      const partial = existing.find(m => m.id === answerId)
      if (!partial?.content) {
        return {
          ...prev,
          [chatId]: existing.map(m =>
            m.id === answerId ? { ...m, streaming: false, failed: true, content: message } : m),
        }
      }
      return {
        ...prev,
        [chatId]: [
          ...existing.map(m => (m.id === answerId ? { ...m, streaming: false } : m)),
          { id: newId(), role: 'assistant' as const, content: message, failed: true },
        ],
      }
    })
  }, [])

  const sendMessage = useCallback(async (text: string) => {
    const question = text.trim()
    if (!question || streamingChatId) return

    const chatId = activeChatId ?? newId()
    if (chatId !== activeChatId) {
      setDraftChatId(chatId)
      setActiveChatId(chatId)
    }

    const answerId = newId()
    setMessagesByChat(prev => ({
      ...prev,
      [chatId]: [
        ...(prev[chatId] ?? []),
        { id: newId(), role: 'user', content: question },
        { id: answerId, role: 'assistant', content: '', streaming: true },
      ],
    }))
    setStreamingChatId(chatId)

    const controller = new AbortController()
    abortRef.current = controller

    try {
      await streamChatMessage(
        { chatId, question },
        {
          onSources: (sources: Source[]) => patchMessage(chatId, answerId, { sources }),
          onToken: (delta: string) => appendToken(chatId, answerId, delta),
          onDone: ({ chatId: persistedId, title, followUpQuestions }) => {
            setMessagesByChat(prev => ({
              ...prev,
              [chatId]: (prev[chatId] ?? []).map(m => m.id === answerId
                ? { ...m, content: m.content.trimEnd(), streaming: false, followUpQuestions }
                : m),
            }))
            registerSession(persistedId ?? chatId, title)
          },
        },
        controller.signal,
      )
    } catch (error) {
      if (controller.signal.aborted) {
        patchMessage(chatId, answerId, { streaming: false, stopped: true })
      } else {
        reportFailure(chatId, answerId, error instanceof Error
          ? error.message
          : 'The answer could not be generated. Please try again.')
      }
    } finally {
      setStreamingChatId(null)
      abortRef.current = null
    }
  }, [activeChatId, appendToken, patchMessage, registerSession, reportFailure, streamingChatId])

  const stopStreaming = useCallback(() => abortRef.current?.abort(), [])

  const renameChat = useCallback(async (chatId: string, title: string) => {
    const trimmed = title.trim()
    if (!trimmed) return
    setSessions(prev => prev.map(s => (s.chatId === chatId ? { ...s, title: trimmed } : s)))
    await apiUpdateSession(chatId, { title: trimmed })
  }, [])

  const togglePin = useCallback(async (chatId: string) => {
    const target = sessions.find(s => s.chatId === chatId)
    if (!target) return
    const pinned = !target.pinned
    setSessions(prev => sortSessions(prev.map(s => (s.chatId === chatId ? { ...s, pinned } : s))))
    await apiUpdateSession(chatId, { pinned })
  }, [sessions])

  const removeChat = useCallback(async (chatId: string) => {
    setSessions(prev => prev.filter(s => s.chatId !== chatId))
    setMessagesByChat(prev => {
      const { [chatId]: _removed, ...rest } = prev
      return rest
    })
    setDraftChatId(current => (current === chatId ? null : current))
    setActiveChatId(current => (current === chatId ? null : current))
    await apiDeleteSession(chatId).catch(() => { /* it is already gone from the user's view */ })
  }, [])

  const activeSession = useMemo(
    () => sessions.find(s => s.chatId === activeChatId) ?? null,
    [sessions, activeChatId],
  )

  return {
    sessions,
    draftChatId,
    activeChatId,
    activeSession,
    messages,
    isLoadingMessages: loadingChatId !== null && loadingChatId === activeChatId,
    streamingChatId,
    isStreaming: streamingChatId !== null && streamingChatId === activeChatId,
    isOnEmptyDraft,
    startNewChat,
    selectChat,
    sendMessage,
    stopStreaming,
    renameChat,
    togglePin,
    removeChat,
  }
}

/** Pinned conversations first, then most recently used. */
function sortSessions(sessions: ChatSession[]): ChatSession[] {
  return [...sessions].sort((a, b) => {
    if (a.pinned !== b.pinned) return a.pinned ? -1 : 1
    return (b.updatedAt ?? '').localeCompare(a.updatedAt ?? '')
  })
}
