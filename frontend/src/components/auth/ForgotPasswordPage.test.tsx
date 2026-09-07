import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/render'
import ForgotPasswordPage from './ForgotPasswordPage'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
})

describe('the forgot-password flow', () => {
  it('requests a code, then redeems it for a new password', async () => {
    fetchMock.mockImplementation(async input => {
      const url = String(input)
      if (url.includes('/forgot-password/request')) return json({ emailSent: true })
      if (url.includes('/forgot-password/reset')) {
        return json({
          userId: 1, email: 'reader@example.com', name: 'Reader', roles: ['USER'],
          token: 'access-token', refreshToken: 'refresh-token', mustChangePassword: false,
        })
      }
      return json({}, 404)
    })

    const onBack = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(<ForgotPasswordPage onBack={onBack} />)

    await user.type(screen.getByLabelText(/email/i), 'reader@example.com')
    await user.click(screen.getByRole('button', { name: /send reset code/i }))

    expect(await screen.findByRole('status')).toHaveTextContent(/reset code was just emailed/i)

    await user.type(screen.getByLabelText(/reset code/i), '123456')
    await user.type(screen.getByLabelText(/^new password$/i), 'newPassword1')
    await user.type(screen.getByLabelText(/confirm new password/i), 'newPassword1')
    await user.click(screen.getByRole('button', { name: /^reset password$/i }))

    const resetCall = fetchMock.mock.calls.find(call => String(call[0]).includes('/forgot-password/reset'))
    expect(resetCall).toBeDefined()
    expect(JSON.parse(String(resetCall![1]?.body))).toEqual({
      email: 'reader@example.com', otp: '123456', newPassword: 'newPassword1',
    })
  })

  it('tells the reader to ask an admin when the email cannot be sent', async () => {
    fetchMock.mockImplementation(async () => json({ emailSent: false }))

    const user = userEvent.setup()
    renderWithProviders(<ForgotPasswordPage onBack={vi.fn()} />)

    await user.type(screen.getByLabelText(/email/i), 'reader@example.com')
    await user.click(screen.getByRole('button', { name: /send reset code/i }))

    expect(await screen.findByRole('status')).toHaveTextContent(/ask an admin to re-share/i)
  })

  it('rejects mismatched passwords before calling the server', async () => {
    fetchMock.mockImplementation(async input => {
      const url = String(input)
      if (url.includes('/forgot-password/request')) return json({ emailSent: true })
      return json({}, 404)
    })

    const user = userEvent.setup()
    renderWithProviders(<ForgotPasswordPage onBack={vi.fn()} />)

    await user.type(screen.getByLabelText(/email/i), 'reader@example.com')
    await user.click(screen.getByRole('button', { name: /send reset code/i }))
    await screen.findByRole('status')

    await user.type(screen.getByLabelText(/reset code/i), '123456')
    await user.type(screen.getByLabelText(/^new password$/i), 'newPassword1')
    await user.type(screen.getByLabelText(/confirm new password/i), 'somethingElse1')
    await user.click(screen.getByRole('button', { name: /^reset password$/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/do not match/i)
    expect(fetchMock.mock.calls.some(call => String(call[0]).includes('/forgot-password/reset'))).toBe(false)
  })

  it('calls onBack from the request step', async () => {
    const onBack = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(<ForgotPasswordPage onBack={onBack} />)

    await user.click(screen.getByRole('button', { name: /back to sign in/i }))
    expect(onBack).toHaveBeenCalledOnce()
  })
})
