import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/render'
import LogoutPage from './LogoutPage'

describe('LogoutPage', () => {
  it('confirms the sign-out and offers a way back in', () => {
    renderWithProviders(<LogoutPage onLoginClick={() => {}} />)

    expect(screen.getByText(/you've been signed out/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /login here/i })).toBeInTheDocument()
  })

  it('hands off to the sign-in screen when clicked', async () => {
    const onLoginClick = vi.fn()
    renderWithProviders(<LogoutPage onLoginClick={onLoginClick} />)

    await userEvent.click(screen.getByRole('button', { name: /login here/i }))

    expect(onLoginClick).toHaveBeenCalledTimes(1)
  })
})
