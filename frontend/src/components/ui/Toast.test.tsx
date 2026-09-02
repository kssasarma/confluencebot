import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../test/render'
import { expectNoAxeViolations } from '../../test/a11y'
import { useToast } from './Toast'
import Button from './Button'

function Harness() {
  const toast = useToast()
  return (
    <>
      <Button onClick={() => toast.success('Preferences saved')}>Save</Button>
      <Button onClick={() => toast.error('Could not save', 'The server is unavailable.')}>
        Fail
      </Button>
    </>
  )
}

describe('Toast', () => {
  it('reports a background success', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Save' }))

    // Twice on purpose: once in the visible card, once in the live region that
    // announces it. Both are load-bearing.
    expect(await screen.findAllByText('Preferences saved')).toHaveLength(2)
  })

  /**
   * The reason a live region exists separately from the toast nodes: assistive technology ignores
   * an `aria-live` element that is inserted already-populated, which is exactly how a toast
   * arrives. The region is mounted empty and the text is copied into it.
   */
  it('announces the message through a live region', async () => {
    const user = userEvent.setup()
    const { container } = renderWithProviders(<Harness />)

    const liveRegion = container.parentElement!.querySelector('[aria-live="polite"]')!
    expect(liveRegion).toBeEmptyDOMElement()

    await user.click(screen.getByRole('button', { name: 'Fail' }))
    await waitFor(() =>
      expect(liveRegion).toHaveTextContent('Could not save. The server is unavailable.'))
  })

  it('keeps a failure on screen until it is dismissed', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Fail' }))
    await screen.findAllByText('Could not save')

    await user.click(screen.getByRole('button', { name: 'Dismiss' }))
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Dismiss' })).not.toBeInTheDocument())
  })

  it('stacks several messages', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Save' }))
    await user.click(screen.getByRole('button', { name: 'Fail' }))

    // One dismiss button per card: the two coexist rather than replacing each other.
    expect(await screen.findAllByRole('button', { name: 'Dismiss' })).toHaveLength(2)
    expect(screen.getAllByText('Preferences saved').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Could not save').length).toBeGreaterThan(0)
  })

  it('has no axe violations', async () => {
    const user = userEvent.setup()
    const { baseElement } = renderWithProviders(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Fail' }))
    await screen.findAllByText('Could not save')

    await expectNoAxeViolations(baseElement)
  })
})
