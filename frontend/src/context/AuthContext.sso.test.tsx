import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { AuthProvider, useAuth } from './AuthContext'
import { exchangeSsoCode, getSsoConfig, getMe } from '../services/authService'
import { REFRESH_KEY, SSO_SESSION_KEY, TOKEN_KEY } from '../lib/token'
import type { AuthResponse } from '../types'

vi.mock('../services/authService', async importOriginal => ({
  ...(await importOriginal<typeof import('../services/authService')>()),
  getSsoConfig: vi.fn(),
  exchangeSsoCode: vi.fn(),
  getMe: vi.fn(),
}))

const mockGetSsoConfig = vi.mocked(getSsoConfig)
const mockExchange = vi.mocked(exchangeSsoCode)
const mockGetMe = vi.mocked(getMe)

/**
 * The moment the provider drops the browser back on this origin.
 *
 * Everything about that page load is decided by the URL fragment, and it has to be decided before
 * the app looks at where it is: the code has to be redeemed, the address bar has to be cleaned,
 * and a failure has to arrive on the sign-in screen as a sentence rather than as a blank page.
 */
describe('AuthProvider — returning from the identity provider', () => {
  // A JWT with no expiry claim, so the renewal timer is never armed during a test.
  const token = `header.${btoa(JSON.stringify({ mustChangePassword: false }))}.signature`

  function session(): AuthResponse {
    return {
      userId: 7, email: 'jane@corp.example', role: 'USER',
      token, refreshToken: 'refresh-token', mustChangePassword: false,
    }
  }

  function Probe() {
    const { user, isLoading, ssoError } = useAuth()
    if (isLoading) return <p>loading</p>
    return (
      <>
        <p data-testid="user">{user?.email ?? 'signed out'}</p>
        <p data-testid="error">{ssoError ?? ''}</p>
      </>
    )
  }

  beforeEach(() => {
    localStorage.clear()
    mockGetSsoConfig.mockResolvedValue({
      enabled: true, providerId: 'otds', providerName: 'OpenText',
      authorizationUrl: '/api/oauth2/authorization/otds', logoutUrl: null,
    })
    mockExchange.mockReset()
    mockGetMe.mockReset()
  })

  afterEach(() => window.history.replaceState(null, '', '/'))

  it('redeems the code and stores the session it buys', async () => {
    window.history.replaceState(null, '', '/sso/callback#sso_code=one-time-code&sso_provider=otds')
    mockExchange.mockResolvedValue(session())

    render(<AuthProvider><Probe /></AuthProvider>)

    await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('jane@corp.example'))
    expect(mockExchange).toHaveBeenCalledWith('one-time-code')
    expect(localStorage.getItem(TOKEN_KEY)).toBe(token)
    expect(localStorage.getItem(REFRESH_KEY)).toBe('refresh-token')
  })

  it('remembers which provider this session came from', () => {
    // Which is a different question from whether the account could have used one: an account
    // linked to a directory but signed in with a password has no provider session to end. The
    // provider's id rather than a flag, so a deployment re-pointed elsewhere signs people out at
    // the directory they actually came from.
    window.history.replaceState(null, '', '/sso/callback#sso_code=one-time-code&sso_provider=entra')
    mockExchange.mockResolvedValue(session())

    render(<AuthProvider><Probe /></AuthProvider>)

    return waitFor(() => expect(localStorage.getItem(SSO_SESSION_KEY)).toBe('entra'))
  })

  it('leaves a password session unmarked', async () => {
    localStorage.setItem(TOKEN_KEY, token)
    mockGetMe.mockResolvedValue(session())

    render(<AuthProvider><Probe /></AuthProvider>)

    await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('jane@corp.example'))
    expect(localStorage.getItem(SSO_SESSION_KEY)).toBeNull()
  })

  it('does not ask the server who it already is instead of redeeming the code', async () => {
    // A stale token in storage must not win over the sign-in that just happened.
    localStorage.setItem(TOKEN_KEY, 'a-stale-token')
    window.history.replaceState(null, '', '/sso/callback#sso_code=one-time-code')
    mockExchange.mockResolvedValue(session())

    render(<AuthProvider><Probe /></AuthProvider>)

    await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('jane@corp.example'))
    expect(mockGetMe).not.toHaveBeenCalled()
  })

  it('takes the code out of the address bar whether or not it works', async () => {
    window.history.replaceState(null, '', '/sso/callback#sso_code=one-time-code')
    mockExchange.mockResolvedValue(session())

    render(<AuthProvider><Probe /></AuthProvider>)

    await waitFor(() => expect(window.location.hash).toBe(''))
    expect(window.location.pathname).toBe('/')
  })

  it('surfaces a rejected code as a message rather than a blank screen', async () => {
    window.history.replaceState(null, '', '/sso/callback#sso_code=already-used')
    mockExchange.mockRejectedValue(new Error('This sign-in link is no longer valid.'))

    render(<AuthProvider><Probe /></AuthProvider>)

    await waitFor(() =>
      expect(screen.getByTestId('error')).toHaveTextContent('This sign-in link is no longer valid.'))
    expect(screen.getByTestId('user')).toHaveTextContent('signed out')
  })

  it('shows the provider-side failure the backend forwarded', async () => {
    window.history.replaceState(null, '', '/sso/callback#sso_error=This+account+has+been+disabled.')

    render(<AuthProvider><Probe /></AuthProvider>)

    await waitFor(() =>
      expect(screen.getByTestId('error')).toHaveTextContent('This account has been disabled.'))
    expect(mockExchange).not.toHaveBeenCalled()
  })

  it('leaves an ordinary page load alone', async () => {
    render(<AuthProvider><Probe /></AuthProvider>)

    await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('signed out'))
    expect(mockExchange).not.toHaveBeenCalled()
    expect(screen.getByTestId('error')).toHaveTextContent('')
  })
})
