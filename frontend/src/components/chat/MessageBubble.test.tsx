import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Message } from '../../types'
import { renderWithProviders } from '../../test/render'
import { expectNoAxeViolations } from '../../test/a11y'
import MessageBubble from './MessageBubble'

const SOURCE = {
  pageId: '131073',
  title: 'Password Reset Guide',
  url: 'http://confluence/display/IT/Password+Reset',
  anchorUrl: 'http://confluence/display/IT/Password+Reset#Self-Service',
  spaceKey: 'IT',
  score: 0.87,
  sectionHeading: 'Self Service',
  excerpt: 'Choose Forgot password on the login page and a reset link is emailed to you.',
}

const ANSWER: Message = {
  id: 'a1',
  role: 'assistant',
  content: 'Choose **Forgot password** on the login page [1].',
  sources: [SOURCE],
  citations: [{ marker: 1, pageId: '131073' }],
  confidence: 0.82,
  followUpQuestions: ['How do I enable two-factor authentication?'],
  createdAt: new Date().toISOString(),
}

function renderBubble(message: Message, overrides: Partial<{
  showSources: boolean
  showConfidence: boolean
  isStreaming: boolean
  onAsk: (question: string) => void
  onRetry: () => void
}> = {}) {
  return renderWithProviders(
    <MessageBubble
      message={message}
      showSources={overrides.showSources ?? true}
      showConfidence={overrides.showConfidence ?? true}
      isStreaming={overrides.isStreaming ?? false}
      onAsk={overrides.onAsk ?? (() => {})}
      onRetry={overrides.onRetry ?? (() => {})}
    />,
  )
}

describe('MessageBubble', () => {
  it('renders the answer with its citations resolved to links', async () => {
    renderBubble(ANSWER)

    const citation = await screen.findByRole('link', { name: /Source 1: Password Reset Guide/ })
    expect(citation).toHaveAttribute('href', SOURCE.anchorUrl)
    expect(citation).toHaveAttribute('target', '_blank')
    expect(citation).toHaveAttribute('rel', expect.stringContaining('noopener'))
  })

  it('leaves an unresolvable marker as text rather than a dead link', async () => {
    renderBubble({ ...ANSWER, content: 'See [4] for details.', citations: [] })

    await screen.findByText(/See \[4\] for details\./)
    expect(screen.queryByRole('link', { name: /Source 4/ })).not.toBeInTheDocument()
  })

  it('shows the sources panel with the excerpt and the relevance numeral', async () => {
    const user = userEvent.setup()
    renderBubble(ANSWER)

    await user.click(screen.getByRole('button', { name: /1 source/ }))

    const [panel] = screen.getAllByRole('listitem')
    expect(within(panel).getByText(/Choose Forgot password on the login page/)).toBeInTheDocument()
    expect(within(panel).getByText('IT › Self Service')).toBeInTheDocument()
    // The numeral, not only the bar: a bar alone encodes the value in size and colour.
    expect(within(panel).getByText('87%')).toBeInTheDocument()
  })

  // The two display preferences were saved by two settings screens and read by nothing.
  it('hides the sources when the preference is off', () => {
    renderBubble(ANSWER, { showSources: false })
    expect(screen.queryByRole('button', { name: /source/ })).not.toBeInTheDocument()
  })

  it('hides the match strength when the preference is off', () => {
    renderBubble(ANSWER, { showConfidence: false })
    expect(screen.queryByText(/source match/i)).not.toBeInTheDocument()
  })

  /**
   * The label states what was measured. "High confidence" would read as a claim that the answer
   * is correct, which is not what a retrieval score can support.
   */
  it('labels the score as a source match, not as confidence in the answer', () => {
    renderBubble(ANSWER)

    expect(screen.getByText('Strong source match')).toBeInTheDocument()
    expect(screen.queryByText(/high confidence/i)).not.toBeInTheDocument()
  })

  it('bands a weak retrieval differently', () => {
    renderBubble({ ...ANSWER, confidence: 0.2 })
    expect(screen.getByText('Weak source match')).toBeInTheDocument()
  })

  it('says the score is unknown for a turn recorded before scoring existed', () => {
    renderBubble({ ...ANSWER, confidence: null })
    expect(screen.getByText('Match unknown')).toBeInTheDocument()
  })

  it('shows follow-ups under the answer they belong to', async () => {
    const onAsk = vi.fn()
    const user = userEvent.setup()
    renderBubble(ANSWER, { onAsk })

    await user.click(screen.getByRole('button', { name: ANSWER.followUpQuestions![0] }))
    expect(onAsk).toHaveBeenCalledWith(ANSWER.followUpQuestions![0])
  })

  describe('when something went wrong', () => {
    const FAILED: Message = {
      ...ANSWER,
      content: 'Choose Forgot pass',
      error: { message: 'The connection was lost before the answer finished.', retryable: true },
      confidence: null,
      followUpQuestions: [],
    }

    /**
     * The failure annotates the answer it belongs to. Appending a second, red bubble made a
     * partial answer read as a good answer followed by a bad one.
     */
    it('keeps the partial answer and reports the failure against it', async () => {
      renderBubble(FAILED)

      await screen.findByText(/Choose Forgot pass/)
      const alert = screen.getByRole('alert')
      expect(alert).toHaveTextContent('The connection was lost before the answer finished.')
    })

    it('offers a retry that re-asks the question', async () => {
      const onRetry = vi.fn()
      const user = userEvent.setup()
      renderBubble(FAILED, { onRetry })

      await user.click(within(screen.getByRole('alert')).getByRole('button', { name: /retry/i }))
      expect(onRetry).toHaveBeenCalledOnce()
    })

    it('offers no retry when retrying cannot help', () => {
      renderBubble({
        ...FAILED,
        error: { message: "You're offline.", retryable: false },
      })

      expect(within(screen.getByRole('alert')).queryByRole('button')).not.toBeInTheDocument()
    })

    it('says an answer was stopped rather than that it failed', () => {
      renderBubble({ ...ANSWER, stopped: true, error: undefined })

      expect(screen.getByText(/Stopped — this answer was not saved\./)).toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })
  })

  describe('while streaming', () => {
    it('says what it is doing before the first token arrives', () => {
      renderBubble({ id: 'a2', role: 'assistant', content: '', streaming: true })
      expect(screen.getByText(/Searching your Confluence pages/)).toBeInTheDocument()
    })

    it('shows no actions, sources or follow-ups on an unfinished answer', () => {
      renderBubble({ ...ANSWER, streaming: true })

      expect(screen.queryByRole('button', { name: /source/ })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /Copy answer/ })).not.toBeInTheDocument()
      expect(screen.queryByText('Suggested next')).not.toBeInTheDocument()
    })
  })

  it('has no axe violations with the sources panel open', async () => {
    const user = userEvent.setup()
    const { container } = renderBubble(ANSWER)

    await screen.findByRole('link', { name: /Source 1/ })
    await user.click(screen.getByRole('button', { name: /1 source/ }))

    await waitFor(() => expect(screen.getAllByRole('listitem').length).toBeGreaterThan(0))
    await expectNoAxeViolations(container)
  })
})
