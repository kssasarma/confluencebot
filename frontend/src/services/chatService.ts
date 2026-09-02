import { API_BASE } from '../config/env'
import { authHeader } from '../lib/token'
import type { ChatSession, Source } from '../types'

export async function fetchSessions(): Promise<ChatSession[]> {
  const res = await fetch(`${API_BASE}/user/chats`, { headers: authHeader() })
  if (!res.ok) throw new Error('Failed to fetch sessions')
  const raw = await res.json() as Array<{ chatId: string; title: string | null; pinned: boolean }>
  return raw.map(s => ({ ...s, messages: [] }))
}

export async function createSession(): Promise<ChatSession> {
  const res = await fetch(`${API_BASE}/user/chats`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeader() },
    body: JSON.stringify({}),
  })
  if (!res.ok) throw new Error('Failed to create session')
  const raw = await res.json() as { chatId: string; title: string | null; pinned: boolean }
  return { ...raw, messages: [] }
}

export async function updateSession(chatId: string, patch: { title?: string; pinned?: boolean }): Promise<void> {
  await fetch(`${API_BASE}/user/chats/${chatId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', ...authHeader() },
    body: JSON.stringify(patch),
  })
}

export async function deleteSession(chatId: string): Promise<void> {
  await fetch(`${API_BASE}/user/chats/${chatId}`, {
    method: 'DELETE',
    headers: authHeader(),
  })
}

export interface StreamChatOptions {
  chatId: string
  message: string
  preferences?: { responseStyle?: string | null; customPrompt?: string | null } | null
}

export async function streamChatMessage(
  options: StreamChatOptions,
  onToken: (delta: string) => void,
  onSources: (sources: Source[]) => void,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch(`${API_BASE}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...authHeader() },
    body: JSON.stringify({ chatId: options.chatId, question: options.message, preferences: options.preferences }),
    signal,
  })

  if (!res.ok || !res.body) throw new Error(`Chat failed: ${res.status}`)

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() ?? ''
    for (const line of lines) {
      if (!line.startsWith('data:')) continue
      const data = line.slice(5).trim()
      if (data === '[DONE]') return
      try {
        const evt = JSON.parse(data) as { type: string; delta?: string; sources?: Source[] }
        if (evt.type === 'token' && evt.delta) onToken(evt.delta)
        else if (evt.type === 'sources' && evt.sources) onSources(evt.sources)
      } catch { /* ignore */ }
    }
  }
}
