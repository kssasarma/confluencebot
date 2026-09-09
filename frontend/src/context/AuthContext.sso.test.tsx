import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuthProvider, useAuth } from './AuthContext'
import { exchangeSsoCode, getSsoConfig, getMe, revokeSession } from '../services/authService'
import { REFRESH_KEY, SSO_SESSION_KEY, TOKEN_KEY } from '../lib/token'
import type { AuthResponse } from '../types'

vi.mock('../services/authService', async importOriginal => ({
  ...(await importOriginal<typeof import('../services/authService')>()),
  getSsoConfig: vi.fn(),
  exchangeSsoCode: vi.fn(),
  getMe: vi.fn(),
  revokeSession: vi.fn(),
}))

const mockGetSsoConfig = vi.mocked(getSsoConfig)
const mockExchange = vi.mocked(exchangeSsoCode)
const mockGetMe = vi.mocked(getMe)
const mockRevokeSession = vi.mocked(revokeSession)

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
      userId: 7, email: 'jane@corp.example', name: 'Jane', roles: ['USER'],
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
      authorizationUrl: '/api/oauth2/authorization/otds', logoutUrl: null, enforced: false,
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

/**
 * Signing out of a directory-originated session, when the deployment can also end it there.
 *
 * With SSO enforced, the sign-in screen this app renders the instant it goes unauthenticated
 * redirects straight back to the provider on its own. So the redirect to the provider's own
 * logout endpoint cannot wait on anything — least of all a network round trip to revoke the
 * local refresh token — or the enforced redirect wins the race, finds the provider's session
 * still alive, and signs back in before the browser ever leaves for the logout endpoint.
 */
describe('AuthProvider — signing out of a directory session', () => {
  const token = `header.${btoa(JSON.stringify({ mustChangePassword: false }))}.signature`

  function session(): AuthResponse {
    return {
      userId: 7, email: 'jane@corp.example', name: 'Jane', roles: ['USER'],
      token, refreshToken: 'refresh-token', mustChangePassword: false,
    }
  }

  function LogoutProbe() {
    const { user, isLoading, logout } = useAuth()
    if (isLoading) return <p>loading</p>
    return (
      <>
        <p data-testid="user">{user?.email ?? 'signed out'}</p>
        <button onClick={logout}>Sign out</button>
      </>
    )
  }

  beforeEach(() => {
    localStorage.clear()
    mockGetMe.mockReset()
    mockRevokeSession.mockReset()
  })

  afterEach(() => {
    window.history.replaceState(null, '', '/')
    vi.restoreAllMocks()
  })

  it('leaves for the provider logout endpoint without waiting for the revoke request to settle', async () => {
    mockGetSsoConfig.mockResolvedValue({
      enabled: true, providerId: 'otds', providerName: 'OpenText',
      authorizationUrl: '/api/oauth2/authorization/otds',
      logoutUrl: 'https://otds.example.com/otdsws/logout', enforced: true,
    })
    localStorage.setItem(TOKEN_KEY, token)
    localStorage.setItem(REFRESH_KEY, 'refresh-token')
    localStorage.setItem(SSO_SESSION_KEY, 'otds')
    mockGetMe.mockResolvedValue(session())
    // Never resolves during the test — proves the redirect below does not wait on it.
    mockRevokeSession.mockReturnValue(new Promise(() => {}))
    const assign = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, assign } as Location)

    render(<AuthProvider><LogoutProbe /></AuthProvider>)
    await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('jane@corp.example'))

    await userEvent.click(screen.getByRole('button', { name: /sign out/i }))

    const [target] = assign.mock.calls[0]
    const url = new URL(target)
    expect(`${url.origin}${url.pathname}`).toBe('https://otds.example.com/otdsws/logout')
    expect(url.searchParams.get('post_logout_redirect_uri'))
      .toBe(`${window.location.origin}/?password=1&logged_out=1`)
  })

  it('asks the provider to send the browser back to this app\'s sign-in screen, password form ready', async () => {
    // Without this, an enforced deployment lands back here signed out, sees nobody signed in, and
    // leaves for the provider all over again — not a loop, since the provider's own session is
    // already gone, but "sign out" would bounce through the provider a second time instead of
    // ever showing this app's own screen.
    mockGetSsoConfig.mockResolvedValue({
      enabled: true, providerId: 'otds', providerName: 'OpenText',
      authorizationUrl: '/api/oauth2/authorization/otds',
      logoutUrl: 'https://otds.example.com/otdsws/logout', enforced: true,
    })
    localStorage.setItem(TOKEN_KEY, token)
    localStorage.setItem(REFRESH_KEY, 'refresh-token')
    localStorage.setItem(SSO_SESSION_KEY, 'otds')
    mockGetMe.mockResolvedValue(session())
    mockRevokeSession.mockReturnValue(new Promise(() => {}))
    const assign = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, assign } as Location)

    render(<AuthProvider><LogoutProbe /></AuthProvider>)
    await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('jane@corp.example'))

    await userEvent.click(screen.getByRole('button', { name: /sign out/i }))

    const target = new URL(assign.mock.calls[0][0])
    const returnUrl = new URL(target.searchParams.get('post_logout_redirect_uri')!)
    expect(returnUrl.searchParams.get('password')).toBe('1')
  })

  it('leaves an already-configured return address alone', async () => {
    mockGetSsoConfig.mockResolvedValue({
      enabled: true, providerId: 'otds', providerName: 'OpenText',
      authorizationUrl: '/api/oauth2/authorization/otds',
      logoutUrl: 'https://otds.example.com/otdsws/logout?post_logout_redirect_uri=https%3A%2F%2Fother.example.com%2Fbye',
      enforced: true,
    })
    localStorage.setItem(TOKEN_KEY, token)
    localStorage.setItem(REFRESH_KEY, 'refresh-token')
    localStorage.setItem(SSO_SESSION_KEY, 'otds')
    mockGetMe.mockResolvedValue(session())
    mockRevokeSession.mockReturnValue(new Promise(() => {}))
    const assign = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, assign } as Location)

    render(<AuthProvider><LogoutProbe /></AuthProvider>)
    await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('jane@corp.example'))

    await userEvent.click(screen.getByRole('button', { name: /sign out/i }))

    const target = new URL(assign.mock.calls[0][0])
    expect(target.searchParams.get('post_logout_redirect_uri')).toBe('https://other.example.com/bye')
  })

  it('does not redirect a password session, even with a directory configured', async () => {
    mockGetSsoConfig.mockResolvedValue({
      enabled: true, providerId: 'otds', providerName: 'OpenText',
      authorizationUrl: '/api/oauth2/authorization/otds',
      logoutUrl: 'https://otds.example.com/otdsws/logout', enforced: false,
    })
    localStorage.setItem(TOKEN_KEY, token)
    mockGetMe.mockResolvedValue(session())
    mockRevokeSession.mockResolvedValue(undefined)
    const assign = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, assign } as Location)

    render(<AuthProvider><LogoutProbe /></AuthProvider>)
    await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('jane@corp.example'))

    await userEvent.click(screen.getByRole('button', { name: /sign out/i }))

    expect(assign).not.toHaveBeenCalled()
  })
})

/**
 * The flag LogoutPage renders on, both for a session that ends without leaving this tab and for
 * one that ends by way of a round trip to the provider's own logout endpoint.
 */
describe('AuthProvider — justLoggedOut', () => {
  const token = `header.${btoa(JSON.stringify({ mustChangePassword: false }))}.signature`

  function session(): AuthResponse {
    return {
      userId: 7, email: 'jane@corp.example', name: 'Jane', roles: ['USER'],
      token, refreshToken: 'refresh-token', mustChangePassword: false,
    }
  }

  function Probe() {
    const { user, isLoading, justLoggedOut, logout } = useAuth()
    if (isLoading) return <p>loading</p>
    return (
      <>
        <p data-testid="user">{user?.email ?? 'signed out'}</p>
        <p data-testid="just-logged-out">{String(justLoggedOut)}</p>
        <button onClick={logout}>Sign out</button>
      </>
    )
  }

  beforeEach(() => {
    localStorage.clear()
    mockGetMe.mockReset()
    mockRevokeSession.mockReset()
    mockGetSsoConfig.mockResolvedValue({
      enabled: false, providerId: null, providerName: null,
      authorizationUrl: null, logoutUrl: null, enforced: false,
    })
  })

  afterEach(() => {
    window.history.replaceState(null, '', '/')
    vi.restoreAllMocks()
  })

  it('is set once a password session signs out with nowhere else to redirect', async () => {
    localStorage.setItem(TOKEN_KEY, token)
    mockGetMe.mockResolvedValue(session())
    mockRevokeSession.mockResolvedValue(undefined)

    render(<AuthProvider><Probe /></AuthProvider>)
    await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('jane@corp.example'))

    await userEvent.click(screen.getByRole('button', { name: /sign out/i }))

    await waitFor(() => expect(screen.getByTestId('just-logged-out')).toHaveTextContent('true'))
  })

  it('is set from the logout marker when the browser lands back from the provider', async () => {
    // What a real return trip looks like: the session was already cleared before the browser left
    // for the provider, so there is nothing in storage and nothing to fetch — just the marker
    // `withPostLogoutRedirect` put in the URL on the way out.
    window.history.replaceState(null, '', '/?password=1&logged_out=1')

    render(<AuthProvider><Probe /></AuthProvider>)

    await waitFor(() => expect(screen.getByTestId('just-logged-out')).toHaveTextContent('true'))
    expect(screen.getByTestId('user')).toHaveTextContent('signed out')
    // Read once and removed, so a later reload of this same URL shows the sign-in screen rather
    // than "you've been signed out" a second time — but the password escape hatch stays, since an
    // enforced deployment must not auto-redirect once LogoutPage hands off to LoginPage either.
    expect(window.location.search).not.toContain('logged_out')
    expect(window.location.search).toContain('password=1')
  })
})
