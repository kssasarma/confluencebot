import { useState, useCallback, useEffect } from 'react'
import type { ChatSession } from '../types'
import { fetchSessions, createSession as apiCreate, updateSession as apiUpdate, deleteSession as apiDelete } from '../services/chatService'

export function useChatSessions() {
  const [sessions, setSessions] = useState<ChatSession[]>([])
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null)

  useEffect(() => {
    fetchSessions().then(setSessions).catch(() => {})
  }, [])

  const createSession = useCallback(async () => {
    const session = await apiCreate()
    setSessions(prev => [session, ...prev])
    setActiveSessionId(session.chatId)
  }, [])

  const selectSession = useCallback((chatId: string) => {
    setActiveSessionId(chatId)
  }, [])

  const deleteSession = useCallback(async (chatId: string) => {
    await apiDelete(chatId)
    setSessions(prev => prev.filter(s => s.chatId !== chatId))
    setActiveSessionId(prev => prev === chatId ? null : prev)
  }, [])

  const updateSessionLocal = useCallback((chatId: string, patch: Partial<ChatSession>) => {
    setSessions(prev => prev.map(s => s.chatId === chatId ? { ...s, ...patch } : s))
  }, [])

  const activeSession = sessions.find(s => s.chatId === activeSessionId) ?? undefined

  return {
    sessions, activeSession, activeSessionId,
    createSession, selectSession, deleteSession, updateSessionLocal,
  }
}
