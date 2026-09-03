import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatProvider } from '../context/ChatContext'
import { ThemeProvider } from '../context/ThemeContext'
import { ConfirmProvider } from '../components/ui/ConfirmDialog'
import { ToastProvider } from '../components/ui/Toast'
import ChatRoute from './ChatRoute'
import NewChatRedirect from './NewChatRedirect'

/**
 * The conversation route against a real chat provider and a stubbed network.
 *
 * These cover the failure that made the app unusable: a conversation is named by the browser and
 * only reaches the database once it carries an answer, so every brand-new chat is a URL the server
 * 404s on. Reporting that as a load failure blocked the transcript underneath it, which meant the
 * composer still sent questions but no answer could ever be seen.
 */

const TRANSCRIPT_URL = /\/user\/chats\/[^/]+\/messages/

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()

/** Every call the app makes other than the transcript, which each test decides for itself. */
function stubBackground(url: string): Response | null {
  if (url.includes('/user/chats?') || url.endsWith('/user/chats')) {
    return json({ items: [], nextCursor: null })
  }
  if (url.includes('/preferences')) {
    return json({
      theme: 'system', language: 'en', responseStyle: 'balanced',
      showSources: true, showConfidence: true,
    })
  }
  return null
}

/** An answer delivered as one server-sent `token` event followed by `done`. */
function answerStream(answer: string): Response {
  const events = [
    { type: 'token', delta: answer },
    {
      type: 'done', chatId: 'server-chat', title: 'A title',
      followUpQuestions: [], citations: [], confidence: 0.9,
    },
  ]
  const body = events.map(event => `data: ${JSON.stringify(event)}\n\n`).join('') + 'data: [DONE]\n\n'
  return new Response(body, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

function renderRoute(route: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <ToastProvider>
          <ConfirmProvider>
            <MemoryRouter initialEntries={[route]}>
              <ChatProvider>
                <Routes>
                  <Route index element={<NewChatRedirect />} />
                  <Route path="chat/:chatId" element={<ChatRoute />} />
                </Routes>
              </ChatProvider>
            </MemoryRouter>
          </ConfirmProvider>
        </ToastProvider>
      </ThemeProvider>
    </QueryClientProvider>,
  )
}

const transcriptCalls = () =>
  fetchMock.mock.calls.map(call => String(call[0])).filter(url => TRANSCRIPT_URL.test(url))

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
  fetchMock.mockImplementation(async input => stubBackground(String(input)) ?? json({}, 404))
})

describe('opening a new chat', () => {
  it('lands on a usable conversation without asking the server for its transcript', async () => {
    renderRoute('/')

    await screen.findByRole('textbox', { name: /ask a question/i })

    expect(transcriptCalls()).toEqual([])
    expect(screen.queryByText(/could not load this conversation/i)).not.toBeInTheDocument()
  })

  it('does not report a conversation the server has never recorded as broken', async () => {
    // The reader reloaded, bookmarked or shared the URL, so this tab has no memory of minting it.
    fetchMock.mockImplementation(async input => {
      const url = String(input)
      if (TRANSCRIPT_URL.test(url)) {
        return json({ detail: 'Conversation not found: abc' }, 404)
      }
      return stubBackground(url) ?? json({}, 404)
    })

    renderRoute('/chat/1c8a4b0e-1d5f-4a3e-9c2b-7f0d5f4a1b2c')

    await waitFor(() => expect(transcriptCalls()).toHaveLength(1))
    await waitFor(() =>
      expect(screen.queryByText(/could not load this conversation/i)).not.toBeInTheDocument())

    // An empty conversation, ready for a question — not a dead end.
    expect(screen.getByRole('textbox', { name: /ask a question/i })).toBeInTheDocument()
  })

  it('still reports a genuine failure to read a conversation', async () => {
    fetchMock.mockImplementation(async input => {
      const url = String(input)
      if (TRANSCRIPT_URL.test(url)) return json({ detail: 'Database is down' }, 500)
      return stubBackground(url) ?? json({}, 404)
    })

    renderRoute('/chat/1c8a4b0e-1d5f-4a3e-9c2b-7f0d5f4a1b2c')

    expect(await screen.findByText(/could not load this conversation/i)).toBeInTheDocument()
    expect(screen.getByText(/database is down/i)).toBeInTheDocument()
  })
})

describe('a stream in a tab that is not being painted', () => {
  it('still shows tokens when no animation frame ever runs', async () => {
    // A backgrounded or throttled tab stops receiving frames. Nothing else about the stream
    // changes, so the tokens must still land.
    vi.stubGlobal('requestAnimationFrame', () => 1)
    vi.stubGlobal('cancelAnimationFrame', () => {})

    let push: (chunk: string) => void = () => {}
    let close: () => void = () => {}
    const open = new ReadableStream<Uint8Array>({
      start(controller) {
        const encoder = new TextEncoder()
        push = chunk => controller.enqueue(encoder.encode(chunk))
        close = () => controller.close()
      },
    })

    fetchMock.mockImplementation(async input => {
      const url = String(input)
      if (TRANSCRIPT_URL.test(url)) return json({ detail: 'not found' }, 404)
      if (url.includes('/chat/stream')) {
        return new Response(open, {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        })
      }
      return stubBackground(url) ?? json({}, 404)
    })

    renderRoute('/chat/1c8a4b0e-1d5f-4a3e-9c2b-7f0d5f4a1b2c')

    await userEvent.type(
      await screen.findByRole('textbox', { name: /ask a question/i }),
      'Where are the runbooks?{Enter}',
    )

    push(`data: ${JSON.stringify({ type: 'token', delta: 'In the SRE space.' })}\n\n`)

    // The answer is still generating: without the timer this text waits for the `done` event.
    const answer = await screen.findByRole('article', { name: /assistant answer/i })
    await waitFor(() => expect(answer).toHaveTextContent(/in the sre space/i))

    close()
  })
})

describe('asking a question after a failed read', () => {
  it('shows the answer instead of the read failure', async () => {
    fetchMock.mockImplementation(async input => {
      const url = String(input)
      if (TRANSCRIPT_URL.test(url)) return json({ detail: 'Database is down' }, 500)
      if (url.includes('/chat/stream')) return answerStream('Reset it from the login page.')
      return stubBackground(url) ?? json({}, 404)
    })

    renderRoute('/chat/1c8a4b0e-1d5f-4a3e-9c2b-7f0d5f4a1b2c')
    await screen.findByText(/could not load this conversation/i)

    await userEvent.type(
      screen.getByRole('textbox', { name: /ask a question/i }),
      'How do I reset my password?{Enter}',
    )

    expect(await screen.findByText(/reset it from the login page/i)).toBeInTheDocument()
    expect(screen.queryByText(/could not load this conversation/i)).not.toBeInTheDocument()
  })
})
