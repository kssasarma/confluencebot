import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../context/AuthContext'
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
            <AuthProvider>
              <MemoryRouter initialEntries={[route]}>
                <ChatProvider>
                  <Routes>
                    <Route index element={<NewChatRedirect />} />
                    <Route path="chat/:chatId" element={<ChatRoute />} />
                  </Routes>
                </ChatProvider>
              </MemoryRouter>
            </AuthProvider>
          </ConfirmProvider>
        </ToastProvider>
      </ThemeProvider>
    </QueryClientProvider>,
  )
}

/** Same stack as {@link renderRoute}, plus one link per id so a test can navigate between chats. */
function renderRouteWithNav(route: string, linkedChatIds: string[]) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <ToastProvider>
          <ConfirmProvider>
            <AuthProvider>
              <MemoryRouter initialEntries={[route]}>
                <ChatProvider>
                  <nav>
                    {linkedChatIds.map(chatId => (
                      <Link key={chatId} to={`/chat/${chatId}`}>Open {chatId}</Link>
                    ))}
                  </nav>
                  <Routes>
                    <Route index element={<NewChatRedirect />} />
                    <Route path="chat/:chatId" element={<ChatRoute />} />
                  </Routes>
                </ChatProvider>
              </MemoryRouter>
            </AuthProvider>
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

  it('centres the question box, with the greeting above it and the suggestions below', async () => {
    renderRoute('/')

    const heading = await screen.findByRole('heading', { name: /how may i help you/i })
    const box = screen.getByRole('textbox', { name: /ask a question/i })
    const suggestion = screen.getByRole('button', { name: /steps to deploy to production/i })

    const follows = (first: Element, second: Element) =>
      Boolean(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING)

    expect(follows(heading, box)).toBe(true)
    expect(follows(box, suggestion)).toBe(true)

    // The caret is already in the box, which is also how a second new chat announces itself.
    expect(box).toHaveFocus()
  })

  it('keeps the caret in the question box once the first answer replaces the welcome', async () => {
    fetchMock.mockImplementation(async input => {
      const url = String(input)
      if (url.includes('/chat/stream')) return answerStream('In the SRE space.')
      if (TRANSCRIPT_URL.test(url)) return json({ detail: 'not found' }, 404)
      return stubBackground(url) ?? json({}, 404)
    })

    renderRoute('/')
    const box = await screen.findByRole('textbox', { name: /ask a question/i })

    await userEvent.type(box, 'Where are the runbooks?{Enter}')
    expect(await screen.findByText(/in the sre space/i)).toBeInTheDocument()

    // The composer moved from the middle of the screen to the bottom of it without being torn
    // down: the same element, still focused, ready for the follow-up question.
    expect(screen.getByRole('textbox', { name: /ask a question/i })).toBe(box)
    expect(box).toHaveFocus()
    expect(
      screen.queryByRole('button', { name: /steps to deploy to production/i }),
    ).not.toBeInTheDocument()
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

  it('never greets the reader in a conversation that already has a transcript', async () => {
    // The greeting is drawn from an empty transcript, and a conversation that has not been read
    // yet also has an empty transcript — so opening a saved conversation from a cold tab used to
    // put "Welcome, how may I help you?" on screen before its messages arrived.
    fetchMock.mockImplementation(async input => {
      const url = String(input)
      if (TRANSCRIPT_URL.test(url)) {
        return json([
          {
            id: 1, role: 'USER', content: 'Where are the runbooks?',
            sources: [], followUpQuestions: [], citations: [], confidence: null,
            createdAt: '2026-09-01T10:00:00Z',
          },
          {
            id: 2, role: 'ASSISTANT', content: 'In the SRE space.',
            sources: [], followUpQuestions: [], citations: [], confidence: 0.9,
            createdAt: '2026-09-01T10:00:01Z',
          },
        ])
      }
      return stubBackground(url) ?? json({}, 404)
    })

    renderRoute('/chat/1c8a4b0e-1d5f-4a3e-9c2b-7f0d5f4a1b2c')

    expect(await screen.findByText(/in the sre space/i)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /how may i help you/i })).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: /steps to deploy to production/i }),
    ).not.toBeInTheDocument()
  })

  it('does not resurrect the greeting when returning to a conversation after visiting a new one', async () => {
    // Reported symptom: reopening an old conversation left the welcome greeting on screen,
    // crowding out the real transcript. This drives the exact round trip that would expose it —
    // a saved conversation, away to a brand-new empty one, and back — within a single tab session
    // (the scenario a full reload cannot reproduce, since every id starts unvisited either way).
    const savedChatId = '1c8a4b0e-1d5f-4a3e-9c2b-7f0d5f4a1b2c'
    const draftChatId = '2d9b5c1f-2e6f-4b4f-8d3c-8f1e6b5c4d3e'

    fetchMock.mockImplementation(async input => {
      const url = String(input)
      if (url.includes(savedChatId) && TRANSCRIPT_URL.test(url)) {
        return json([
          {
            id: 1, role: 'USER', content: 'Where are the runbooks?',
            sources: [], followUpQuestions: [], citations: [], confidence: null,
            createdAt: '2026-09-01T10:00:00Z',
          },
          {
            id: 2, role: 'ASSISTANT', content: 'In the SRE space.',
            sources: [], followUpQuestions: [], citations: [], confidence: 0.9,
            createdAt: '2026-09-01T10:00:01Z',
          },
        ])
      }
      // The other conversation is one the server has never heard of — a fresh, empty draft.
      if (TRANSCRIPT_URL.test(url)) return json({ detail: 'not found' }, 404)
      return stubBackground(url) ?? json({}, 404)
    })

    renderRouteWithNav(`/chat/${savedChatId}`, [draftChatId, savedChatId])

    expect(await screen.findByText(/in the sre space/i)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('link', { name: `Open ${draftChatId}` }))
    expect(await screen.findByRole('heading', { name: /how may i help you/i })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('link', { name: `Open ${savedChatId}` }))

    expect(await screen.findByText(/in the sre space/i)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /how may i help you/i })).not.toBeInTheDocument()
    // Exactly one instance each — reopening must not have appended a second copy of either the
    // transcript or the greeting alongside it.
    expect(screen.getAllByText(/in the sre space/i)).toHaveLength(1)
    expect(screen.getAllByText(/where are the runbooks/i)).toHaveLength(1)
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
