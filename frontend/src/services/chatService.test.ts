import { beforeEach, describe, expect, it, vi } from 'vitest'
import { streamChatMessage, type ChatStreamHandlers } from './chatService'

/**
 * Contract tests for the answer stream, replayed against the real consumer.
 *
 * Each fixture is a transcript a deployment actually produces. The one that matters most is
 * `error-after-done`: it is the exact shape of the defect where a finished answer was followed by
 * a red error bubble, and it is the reason `consumeEventStream` tracks a settled flag.
 */

const REQUEST = { chatId: '0f2a5f1e-9c1c-4f1f-9a2b-6f0d5f4a1b2c', question: 'How do I deploy?' }

function sse(...events: unknown[]): string {
  return events.map(event => `data: ${JSON.stringify(event)}\n\n`).join('')
}

/** A response whose body yields the given chunks, then optionally fails. */
function streamingResponse(chunks: string[], failAfter?: Error): Response {
  const encoder = new TextEncoder()
  let index = 0

  const body = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (index < chunks.length) {
        controller.enqueue(encoder.encode(chunks[index++]))
        return
      }
      if (failAfter) controller.error(failAfter)
      else controller.close()
    },
  })

  return new Response(body, {
    status: 200,
    headers: { 'content-type': 'text/event-stream' },
  })
}

function handlers(): ChatStreamHandlers & {
  tokens: string[]
  done: unknown[]
  sources: unknown[]
  titles: string[]
} {
  const tokens: string[] = []
  const done: unknown[] = []
  const sources: unknown[] = []
  const titles: string[] = []
  return {
    tokens, done, sources, titles,
    onToken: delta => tokens.push(delta),
    onDone: result => done.push(result),
    onSources: value => sources.push(value),
    onTitle: (_chatId, title) => titles.push(title),
  }
}

const DONE_EVENT = {
  type: 'done',
  chatId: REQUEST.chatId,
  title: 'Deploying to production',
  followUpQuestions: ['How do I roll back?'],
  citations: [{ marker: 1, pageId: '131073' }],
  confidence: 0.81,
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn())
  localStorage.clear()
})

describe('streamChatMessage', () => {
  it('delivers sources, tokens and completion from a healthy stream', async () => {
    const h = handlers()
    vi.mocked(fetch).mockResolvedValue(streamingResponse([
      sse({ type: 'sources', sources: [{ pageId: '1', title: 'Deploys', url: 'u' }] }),
      sse({ type: 'token', delta: 'Run ' }, { type: 'token', delta: 'the pipeline [1].' }),
      sse(DONE_EVENT),
      'data: [DONE]\n\n',
    ]))

    await streamChatMessage(REQUEST, h)

    expect(h.sources).toHaveLength(1)
    expect(h.tokens.join('')).toBe('Run the pipeline [1].')
    expect(h.done).toEqual([{
      chatId: REQUEST.chatId,
      title: 'Deploying to production',
      followUpQuestions: ['How do I roll back?'],
      citations: [{ marker: 1, pageId: '131073' }],
      confidence: 0.81,
    }])
  })

  it('swallows a transport failure that happens after the answer is complete', async () => {
    const h = handlers()
    vi.mocked(fetch).mockResolvedValue(streamingResponse(
      [sse({ type: 'token', delta: 'All done.' }), sse(DONE_EVENT)],
      new TypeError('network error'),
    ))

    // The answer is whole and was persisted server-side. Reporting a failure here is the bug.
    await expect(streamChatMessage(REQUEST, h)).resolves.toBeUndefined()
    expect(h.tokens.join('')).toBe('All done.')
    expect(h.done).toHaveLength(1)
  })

  it('reports a transport failure that happens before the answer is complete', async () => {
    const h = handlers()
    vi.mocked(fetch).mockResolvedValue(streamingResponse(
      [sse({ type: 'token', delta: 'Half an ans' })],
      new TypeError('network error'),
    ))

    await expect(streamChatMessage(REQUEST, h)).rejects.toThrow('network error')
    expect(h.tokens.join('')).toBe('Half an ans')
    expect(h.done).toHaveLength(0)
  })

  it('surfaces a server-sent error event', async () => {
    const h = handlers()
    vi.mocked(fetch).mockResolvedValue(streamingResponse([
      sse({ type: 'error', message: 'The assistant is busy right now.' }),
    ]))

    await expect(streamChatMessage(REQUEST, h)).rejects.toMatchObject({
      status: 503,
      message: 'The assistant is busy right now.',
    })
  })

  it('reassembles an event split across two network chunks', async () => {
    const h = handlers()
    const payload = sse({ type: 'token', delta: 'split' })
    const half = Math.floor(payload.length / 2)

    vi.mocked(fetch).mockResolvedValue(streamingResponse([
      payload.slice(0, half),
      payload.slice(half),
      sse(DONE_EVENT),
    ]))

    await streamChatMessage(REQUEST, h)
    expect(h.tokens.join('')).toBe('split')
  })

  it('ignores keep-alive comment frames', async () => {
    const h = handlers()
    vi.mocked(fetch).mockResolvedValue(streamingResponse([
      ':keep-alive\n\n',
      sse({ type: 'token', delta: 'ok' }),
      ':keep-alive\n\n',
      sse(DONE_EVENT),
    ]))

    await streamChatMessage(REQUEST, h)
    expect(h.tokens.join('')).toBe('ok')
    expect(h.done).toHaveLength(1)
  })

  it('accepts CRLF line endings from a proxy that rewrites them', async () => {
    const h = handlers()
    vi.mocked(fetch).mockResolvedValue(streamingResponse([
      `data: ${JSON.stringify({ type: 'token', delta: 'crlf' })}\r\n\r\n`,
      `data: ${JSON.stringify(DONE_EVENT)}\r\n\r\n`,
    ]))

    await streamChatMessage(REQUEST, h)
    expect(h.tokens.join('')).toBe('crlf')
    expect(h.done).toHaveLength(1)
  })

  it('passes a late title refinement to the caller', async () => {
    const h = handlers()
    vi.mocked(fetch).mockResolvedValue(streamingResponse([
      sse({ type: 'token', delta: 'answer' }),
      sse(DONE_EVENT),
      sse({ type: 'title', chatId: REQUEST.chatId, title: 'Production deploy steps' }),
    ]))

    await streamChatMessage(REQUEST, h)
    expect(h.titles).toEqual(['Production deploy steps'])
  })

  it('falls back to the plain JSON endpoint when the stream route is absent', async () => {
    const h = handlers()
    vi.mocked(fetch)
      .mockResolvedValueOnce(new Response(null, { status: 404 }))
      .mockResolvedValueOnce(Response.json({
        answer: 'Whole answer [1].',
        sources: [{ pageId: '1', title: 'Deploys', url: 'u' }],
        followUpQuestions: [],
        citations: [{ marker: 1, pageId: '1' }],
        confidence: 0.6,
        chatId: REQUEST.chatId,
        title: 'Deploys',
      }))

    await streamChatMessage(REQUEST, h)

    expect(h.tokens).toEqual(['Whole answer [1].'])
    expect(h.done).toHaveLength(1)
  })

  it('falls back when a proxy answers the stream route with JSON', async () => {
    const h = handlers()
    vi.mocked(fetch).mockResolvedValue(Response.json({
      answer: 'Buffered by the proxy.',
      sources: [],
      followUpQuestions: [],
      citations: [],
      confidence: null,
      chatId: REQUEST.chatId,
      title: null,
    }))

    await streamChatMessage(REQUEST, h)
    expect(h.tokens).toEqual(['Buffered by the proxy.'])
  })
})
