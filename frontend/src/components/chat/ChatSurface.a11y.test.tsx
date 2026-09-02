import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Message } from '../../types'
import { renderWithProviders } from '../../test/render'
import { expectNoAxeViolations } from '../../test/a11y'
import MessageList from './MessageList'
import Composer from './Composer'
import WelcomePanel from './WelcomePanel'

/**
 * The delivery plan's Phase 6 acceptance criterion: zero axe violations on the chat route.
 *
 * The route's own shell needs a router, a query client and an authenticated session; what is
 * asserted here is everything inside it — the transcript, the composer and the empty state —
 * which is where the interactive controls actually live.
 */

const TRANSCRIPT: Message[] = [
  { id: 'q1', role: 'user', content: 'How do I reset my password?' },
  {
    id: 'a1',
    role: 'assistant',
    content: 'Choose **Forgot password** [1].\n\n```bash\nsudo systemctl restart auth\n```',
    sources: [{
      pageId: '1',
      title: 'Password Reset Guide',
      url: 'http://confluence/1',
      anchorUrl: 'http://confluence/1#reset',
      spaceKey: 'IT',
      score: 0.9,
      sectionHeading: 'Reset',
      excerpt: 'Choose Forgot password on the login page.',
    }],
    citations: [{ marker: 1, pageId: '1' }],
    confidence: 0.88,
    followUpQuestions: ['How do I enable two-factor authentication?'],
    createdAt: new Date().toISOString(),
  },
  {
    id: 'a2',
    role: 'assistant',
    content: 'Partial answer',
    error: { message: 'The connection was lost before the answer finished.', retryable: true },
  },
]

function ChatSurface(
  { messages = TRANSCRIPT, isStreaming = false }: { messages?: Message[]; isStreaming?: boolean },
) {
  return (
    <div>
      <MessageList
        messages={messages}
        showSources
        showConfidence
        isStreaming={isStreaming}
        onAsk={() => {}}
        onRetry={() => {}}
      />
      <Composer chatId="c1" onSend={() => {}} onStop={() => {}} isStreaming={isStreaming} />
    </div>
  )
}

describe('the chat surface', () => {
  it('has no axe violations with a full transcript', async () => {
    const { container } = renderWithProviders(<ChatSurface />)

    await screen.findByRole('link', { name: /Source 1/ })
    await expectNoAxeViolations(container)
  })

  it('has no axe violations with the sources panel expanded', async () => {
    const user = userEvent.setup()
    const { container } = renderWithProviders(<ChatSurface />)

    await screen.findByRole('link', { name: /Source 1/ })
    await user.click(screen.getByRole('button', { name: /1 source/ }))

    await expectNoAxeViolations(container)
  })

  it('has no axe violations on the empty state', async () => {
    const { container } = renderWithProviders(<WelcomePanel onSelect={() => {}} />)
    await expectNoAxeViolations(container)
  })

  it('mirrors a streaming answer into a live region for screen readers', () => {
    renderWithProviders(
      <ChatSurface
        isStreaming
        messages={[{ id: 'a', role: 'assistant', content: 'Half an answ', streaming: true }]}
      />,
    )

    const live = document.querySelector('[aria-live="polite"]')
    expect(live).toHaveTextContent('Half an answ')
  })

  it('reaches every control by keyboard alone', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChatSurface />)

    await screen.findByRole('link', { name: /Source 1/ })

    const reached = new Set<string>()
    for (let i = 0; i < 40; i++) {
      await user.tab()
      const active = document.activeElement as HTMLElement | null
      if (active && active !== document.body) reached.add(active.tagName)
    }

    expect(reached).toContain('BUTTON')
    expect(reached).toContain('A')
    expect(reached).toContain('TEXTAREA')
  })
})

describe('the composer', () => {
  it('sends on Enter and inserts a newline on Shift+Enter', async () => {
    const onSend = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(
      <Composer chatId="c-enter" onSend={onSend} onStop={() => {}} isStreaming={false} />,
    )

    const box = screen.getByRole('textbox', { name: 'Ask a question' })
    await user.click(box)
    await user.keyboard('First line{Shift>}{Enter}{/Shift}second line')
    expect(onSend).not.toHaveBeenCalled()

    await user.keyboard('{Enter}')
    expect(onSend).toHaveBeenCalledWith('First line\nsecond line')
  })

  it('recalls the previous question with ArrowUp in an empty box', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <Composer
        chatId="c-recall"
        onSend={() => {}}
        onStop={() => {}}
        isStreaming={false}
        lastQuestion="How do I deploy?"
      />,
    )

    const box = screen.getByRole('textbox', { name: 'Ask a question' })
    await user.click(box)
    await user.keyboard('{ArrowUp}')

    expect(box).toHaveValue('How do I deploy?')
  })

  it('keeps an unsent draft per conversation', async () => {
    const user = userEvent.setup()
    const { unmount } = renderWithProviders(
      <Composer chatId="c-draft" onSend={() => {}} onStop={() => {}} isStreaming={false} />,
    )

    await user.type(screen.getByRole('textbox', { name: 'Ask a question' }), 'A long question')
    unmount()

    renderWithProviders(
      <Composer chatId="c-draft" onSend={() => {}} onStop={() => {}} isStreaming={false} />,
    )
    expect(screen.getByRole('textbox', { name: 'Ask a question' })).toHaveValue('A long question')
  })

  it('offers a stop control while an answer is arriving', async () => {
    const onStop = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(
      <Composer chatId="c-stop" onSend={() => {}} onStop={onStop} isStreaming />,
    )

    expect(screen.queryByRole('button', { name: 'Send question' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Stop generating' }))
    expect(onStop).toHaveBeenCalledOnce()
  })
})
