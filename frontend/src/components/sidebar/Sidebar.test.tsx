import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../../context/AuthContext'
import { ChatProvider } from '../../context/ChatContext'
import { ThemeProvider } from '../../context/ThemeContext'
import { ConfirmProvider } from '../ui/ConfirmDialog'
import { ToastProvider } from '../ui/Toast'
import ChatRoute from '../../routes/ChatRoute'
import Sidebar from './Sidebar'
import type { MenuAction } from '../ui/Menu'

/**
 * Deleting the conversation currently open used to leave its route pointing at a chat id that no
 * longer existed anywhere: not a draft (it once had messages), not an error (the delete succeeded),
 * just an empty transcript forever — which `ChatRoute` reads as "not loaded yet" and shows the
 * loading skeleton for, permanently. This drives that exact sequence through the real sidebar.
 *
 * `Menu` is stubbed out: its real dropdown is anchored by floating-ui, which never settles against
 * this environment's stubbed `ResizeObserver` (see the note in `ProfileMenu.test.tsx`) and hangs
 * any test that opens it. The stub renders every action as a plain, always-visible button instead,
 * so `Sidebar`'s own delete-and-navigate logic — the thing under test — still runs for real.
 */
vi.mock('../ui/Menu', () => ({
  default: ({ actions }: { actions: MenuAction[] }) => (
    <>
      {actions.map(action => (
        <button key={action.label} onClick={action.onSelect} disabled={action.disabled}>
          {action.label}
        </button>
      ))}
    </>
  ),
}))

const TRANSCRIPT_URL = /\/user\/chats\/[^/]+\/messages/
const CHAT_ID = '1c8a4b0e-1d5f-4a3e-9c2b-7f0d5f4a1b2c'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()

function LocationDisplay() {
  const location = useLocation()
  return <div data-testid="location">{location.pathname}</div>
}

function renderShell(route: string) {
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
                  <LocationDisplay />
                  <Sidebar />
                  <Routes>
                    <Route index element={<Navigate to="/chat" replace />} />
                    <Route path="chat" element={<ChatRoute />} />
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

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
  fetchMock.mockImplementation(async (input, init) => {
    const url = String(input)
    if (url.includes('/user/chats?') || url.endsWith('/user/chats')) {
      return json({
        items: [{
          chatId: CHAT_ID, title: 'Runbook question', pinned: false,
          messageCount: 2, updatedAt: '2026-09-01T10:00:01Z', titleGenerated: true,
        }],
        nextCursor: null,
      })
    }
    if (url.includes(`/user/chats/${CHAT_ID}`) && init?.method === 'DELETE') return json({}, 204)
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
    if (url.includes('/preferences')) {
      return json({
        theme: 'system', language: 'en', responseStyle: 'balanced',
        showSources: true, showConfidence: true,
      })
    }
    if (url.includes('/spaces/suggestions')) return json([])
    if (url.includes(`/user/chats/${CHAT_ID}`)) return json({}, 200)
    return json({}, 404)
  })
})

describe('deleting the conversation that is currently open', () => {
  it('lands on the welcome screen instead of stuck loading', async () => {
    renderShell(`/chat/${CHAT_ID}`)

    expect(await screen.findByText(/in the sre space/i)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /^delete$/i }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: /^delete$/i }))

    // Exact match: `/chat/${CHAT_ID}` also contains the substring `/chat`, so anything looser than
    // an exact match would pass before the navigation away from the deleted conversation happens.
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/chat$/))
    expect(await screen.findByRole('heading', { name: /how may i help you/i })).toBeInTheDocument()
    expect(screen.queryByText(/in the sre space/i)).not.toBeInTheDocument()
  })
})
