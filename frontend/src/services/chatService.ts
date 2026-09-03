import { apiFetch, apiJson, apiVoid, jsonBody, toApiError, ApiError } from './http'
import type {
  ChatSession, ChatSessionPage, Citation, Message, Source,
} from '../types'

// ── Conversations ───────────────────────────────────────────────────────────

export interface SessionQuery {
  /** Free text matched against titles and transcript contents. */
  q?: string
  cursor?: string | null
  limit?: number
}

export function fetchSessions(query: SessionQuery = {}): Promise<ChatSessionPage> {
  const params = new URLSearchParams()
  if (query.q?.trim()) params.set('q', query.q.trim())
  if (query.cursor) params.set('cursor', query.cursor)
  if (query.limit) params.set('limit', String(query.limit))

  const search = params.toString()
  return apiJson<ChatSessionPage>(`/user/chats${search ? `?${search}` : ''}`)
}

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
  citations: Citation[]
  confidence: number | null
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
    citations: entry.citations,
    confidence: entry.confidence,
    createdAt: entry.createdAt,
  }))
}

// ── Answering ───────────────────────────────────────────────────────────────

export interface StreamCompletion {
  chatId: string | null
  title: string | null
  followUpQuestions: string[]
  citations: Citation[]
  confidence: number | null
}

export interface ChatStreamHandlers {
  onSources: (sources: Source[]) => void
  onToken: (delta: string) => void
  onDone: (result: StreamCompletion) => void
  /** A summarised conversation title, which can arrive shortly after the answer completes. */
  onTitle?: (chatId: string, title: string) => void
}

interface ChatAnswer {
  answer: string
  sources: Source[]
  followUpQuestions: string[]
  citations: Citation[]
  confidence: number | null
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
    citations: answer.citations ?? [],
    confidence: answer.confidence ?? null,
  })
}

type StreamEvent =
  | { type: 'sources'; sources: Source[] }
  | { type: 'token'; delta: string }
  | { type: 'title'; chatId: string; title: string }
  | {
      type: 'done'
      chatId: string | null
      title: string | null
      followUpQuestions: string[]
      citations: Citation[]
      confidence: number | null
    }
  | { type: 'error'; message: string }

/**
 * Reads the event stream to its end.
 *
 * ── The settled guard, which is the point of this function ──────────────────
 * The server sends `done`, then the `[DONE]` sentinel, then completes. That is a real window, and
 * a transport can die inside it: a proxy idle timeout, a load-balancer connection cap, a laptop
 * going to sleep, a background tab being throttled. When it does, `reader.read()` rejects.
 *
 * Letting that rejection propagate produces the worst failure mode this app had: a complete,
 * correct answer on screen — already persisted server-side, and perfect after a reload — with a
 * red error underneath it. The UI lies about work it actually finished.
 *
 * So the stream is marked settled the moment `done` is dispatched, and any transport failure
 * after that is swallowed. Nothing is lost by doing so: everything the caller needs has already
 * been delivered.
 *
 * The reader is cancelled in a `finally` for the other half of the problem: a handler that throws
 * — the `error` event does — would otherwise leave the response body locked and its connection
 * held open.
 */
async function consumeEventStream(
  body: ReadableStream<Uint8Array>,
  handlers: ChatStreamHandlers,
): Promise<void> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let settled = false

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })

      // Events are separated by a blank line; anything after the last one is a partial event.
      // \r\n is accepted because some proxies rewrite the line endings on the way through.
      const blocks = buffer.split(/\r?\n\r?\n/)
      buffer = blocks.pop() ?? ''

      for (const block of blocks) {
        const payload = block
          .split(/\r?\n/)
          .filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).trimStart())
          .join('\n')

        if (!payload || payload === SSE_SENTINEL) continue
        if (handleEvent(payload, handlers)) settled = true
      }
    }
  } catch (error) {
    // Before `done`, the answer is genuinely incomplete and the caller must hear about it.
    if (!settled) throw error
  } finally {
    // `cancel` rejects if the stream is already errored; there is nothing left to do about it.
    await reader.cancel().catch(() => {})
  }
}

/** @returns true when this event completes the answer. */
function handleEvent(payload: string, handlers: ChatStreamHandlers): boolean {
  let event: StreamEvent
  try {
    event = JSON.parse(payload) as StreamEvent
  } catch {
    return false // a keep-alive or a comment line — nothing to do
  }

  switch (event.type) {
    case 'sources':
      handlers.onSources(event.sources ?? [])
      return false
    case 'token':
      handlers.onToken(event.delta ?? '')
      return false
    case 'title':
      handlers.onTitle?.(event.chatId, event.title)
      return false
    case 'done':
      handlers.onDone({
        chatId: event.chatId,
        title: event.title,
        followUpQuestions: event.followUpQuestions ?? [],
        citations: event.citations ?? [],
        confidence: event.confidence ?? null,
      })
      return true
    case 'error':
      throw new ApiError(503, event.message || 'The answer could not be generated.')
    default:
      return false
  }
}
