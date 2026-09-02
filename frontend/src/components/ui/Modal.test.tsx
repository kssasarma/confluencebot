import { useState } from 'react'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../test/render'
import { expectNoAxeViolations } from '../../test/a11y'
import Modal, { ModalBody } from './Modal'
import Button from './Button'

/**
 * The behaviours a hand-rolled `fixed inset-0` div silently lacks — and which are invisible to
 * anyone testing with a mouse. Each of the three overlays this primitive replaced was missing
 * every one of them.
 */

function Harness({ dismissable = true }: { dismissable?: boolean }) {
  const [open, setOpen] = useState(false)

  return (
    <>
      <Button onClick={() => setOpen(true)}>Open settings</Button>
      <p>Behind the dialog</p>
      <Modal
        open={open}
        onClose={() => setOpen(false)}
        title="Settings"
        description="Account-wide preferences"
        dismissable={dismissable}
        footer={<Button onClick={() => setOpen(false)}>Done</Button>}
      >
        <ModalBody>
          <label htmlFor="field">Name</label>
          <input id="field" />
        </ModalBody>
      </Modal>
    </>
  )
}

describe('Modal', () => {
  it('is a labelled dialog', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Open settings' }))

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveAccessibleName('Settings')
    expect(dialog).toHaveAccessibleDescription('Account-wide preferences')
  })

  it('traps focus so Tab never reaches the page behind it', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Open settings' }))
    await screen.findByRole('dialog')

    const trigger = screen.getByRole('button', { name: 'Open settings' })

    // Ten tabs is more than the dialog contains; without a trap this walks out into the page.
    for (let i = 0; i < 10; i++) {
      await user.tab()
      expect(document.activeElement).not.toBe(trigger)
      expect(screen.getByRole('dialog')).toContainElement(document.activeElement as HTMLElement)
    }
  })

  it('closes on Escape and returns focus to the trigger', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    const trigger = screen.getByRole('button', { name: 'Open settings' })
    await user.click(trigger)
    await screen.findByRole('dialog')

    await user.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await waitFor(() => expect(document.activeElement).toBe(trigger))
  })

  it('closes from its own close button', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Open settings' }))
    await screen.findByRole('dialog')

    await user.click(screen.getByRole('button', { name: 'Close dialog' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('offers no dismissal affordance when it must be answered', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness dismissable={false} />)

    await user.click(screen.getByRole('button', { name: 'Open settings' }))
    await screen.findByRole('dialog')

    expect(screen.queryByRole('button', { name: 'Close dialog' })).not.toBeInTheDocument()

    // A close button that does nothing is worse than no close button; Escape is inert too.
    await user.keyboard('{Escape}')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('has no axe violations', async () => {
    const user = userEvent.setup()
    const { baseElement } = renderWithProviders(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Open settings' }))
    await screen.findByRole('dialog')

    await expectNoAxeViolations(baseElement)
  })
})
