import { apiFetch, apiJson, apiVoid, jsonBody, toApiError, ApiError } from './http'
import type { ChatSession, Message, Source } from '../types'

// ── Conversations ───────────────────────────────────────────────────────────

export const fetchSessions = (): Promise<ChatSession[]> =>
  apiJson<ChatSession[]>('/user/chats')

export const createSession = (title?: string): Promise<ChatSession> =>
  apiJson<ChatSession>('/user/chats', { method: 'POST', ...jsonBody({ title: title ?? null }) })

export const updateSession = (
  chatId: string,
  patch: { title?: string; pinned?: boolean },
): Promise<ChatSession> =>
  apiJson<ChatSession>(`/user/chats/${chatId}`, { method: 'PATCH', ...jsonBody(patch) })

export const deleteSession = (chatId: string): Promise<void> =>
  apiVoid(`/user/chats/${chatId}`, { method: 'DELETE' })

interface TranscriptEntry {
  id: number
  role: 'USER' | 'ASSISTANT'
  content: string
  sources: Source[]
  followUpQuestions: string[]
  createdAt: string
}

export async function fetchTranscript(chatId: string): Promise<Message[]> {
  const entries = await apiJson<TranscriptEntry[]>(`/user/chats/${chatId}/messages`)
  return entries.map(entry => ({
    id: `m${entry.id}`,
    role: entry.role === 'USER' ? 'user' : 'assistant',
    content: entry.content,
    sources: entry.sources,
    followUpQuestions: entry.followUpQuestions,
    createdAt: entry.createdAt,
  }))
}

// ── Answering ───────────────────────────────────────────────────────────────

export interface ChatStreamHandlers {
  onSources: (sources: Source[]) => void
  onToken: (delta: string) => void
  onDone: (result: { chatId: string | null; title: string | null; followUpQuestions: string[] }) => void
}

interface ChatAnswer {
  answer: string
  sources: Source[]
  followUpQuestions: string[]
  chatId: string | null
  title: string | null
}

const SSE_SENTINEL = '[DONE]'

/**
 * Streams an answer over server-sent events.
 *
 * A backend that cannot stream — an older deployment, a proxy that will not pass event streams —
 * is not a dead end: the request falls back to the plain JSON endpoint and the whole answer is
 * delivered as a single token, so the conversation still works.
 */
export async function streamChatMessage(
  request: { chatId: string; question: string },
  handlers: ChatStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const response = await apiFetch('/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream, application/json' },
    body: JSON.stringify(request),
    signal,
  })

  if (response.status === 404 || response.status === 406) {
    return deliverWholeAnswer(await requestWholeAnswer(request, signal), handlers)
  }
  if (!response.ok) throw await toApiError(response)

  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('text/event-stream') || !response.body) {
    return deliverWholeAnswer(await response.json() as ChatAnswer, handlers)
  }

  await consumeEventStream(response.body, handlers)
}

async function requestWholeAnswer(
  request: { chatId: string; question: string },
  signal?: AbortSignal,
): Promise<ChatAnswer> {
  const response = await apiFetch('/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(request),
    signal,
  })
  if (!response.ok) throw await toApiError(response)
  return response.json() as Promise<ChatAnswer>
}

function deliverWholeAnswer(answer: ChatAnswer, handlers: ChatStreamHandlers): void {
  if (answer.sources?.length) handlers.onSources(answer.sources)
  handlers.onToken(answer.answer)
  handlers.onDone({
    chatId: answer.chatId,
    title: answer.title,
    followUpQuestions: answer.followUpQuestions ?? [],
  })
}

type StreamEvent =
  | { type: 'sources'; sources: Source[] }
  | { type: 'token'; delta: string }
  | { type: 'done'; chatId: string | null; title: string | null; followUpQuestions: string[] }
  | { type: 'error'; message: string }

async function consumeEventStream(body: ReadableStream<Uint8Array>, handlers: ChatStreamHandlers) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    // Events are separated by a blank line; anything after the last one is a partial event.
    const blocks = buffer.split(/\n\n/)
    buffer = blocks.pop() ?? ''

    for (const block of blocks) {
      const payload = block
        .split('\n')
        .filter(line => line.startsWith('data:'))
        .map(line => line.slice(5).trimStart())
        .join('\n')

      if (!payload || payload === SSE_SENTINEL) continue
      handleEvent(payload, handlers)
    }
  }
}

function handleEvent(payload: string, handlers: ChatStreamHandlers): void {
  let event: StreamEvent
  try {
    event = JSON.parse(payload) as StreamEvent
  } catch {
    return // a keep-alive or a comment line — nothing to do
  }

  switch (event.type) {
    case 'sources':
      handlers.onSources(event.sources ?? [])
      break
    case 'token':
      handlers.onToken(event.delta ?? '')
      break
    case 'done':
      handlers.onDone({
        chatId: event.chatId,
        title: event.title,
        followUpQuestions: event.followUpQuestions ?? [],
      })
      break
    case 'error':
      throw new ApiError(503, event.message || 'The answer could not be generated.')
  }
}
