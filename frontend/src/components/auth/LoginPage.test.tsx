import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/render'
import LoginPage from './LoginPage'
import { getSsoConfig } from '../../services/authService'
import type { SsoConfig } from '../../types'

vi.mock('../../services/authService', async importOriginal => ({
  ...(await importOriginal<typeof import('../../services/authService')>()),
  getSsoConfig: vi.fn(),
}))

const mockGetSsoConfig = vi.mocked(getSsoConfig)

/**
 * What the sign-in screen offers, and on whose say-so.
 *
 * Whether there is a directory to sign in through is a property of the deployment, answered by the
 * API at runtime — so the same build serves a customer using OTDS and one using passwords only.
 * The password form is unconditional in both: it is the way back in when the directory is down,
 * and the only way in for the bootstrap administrator, who does not exist in any directory.
 */
describe('LoginPage', () => {
  beforeEach(() => {
    localStorage.clear()
    mockGetSsoConfig.mockReset()
  })

  afterEach(() => window.history.replaceState(null, '', '/'))

  function ssoConfig(overrides: Partial<SsoConfig> = {}): SsoConfig {
    return {
      enabled: true,
      providerId: 'otds',
      providerName: 'OpenText',
      authorizationUrl: '/api/oauth2/authorization/otds',
      logoutUrl: null,
      enforced: false,
      ...overrides,
    }
  }

  it('offers the provider by name once the deployment says it has one', async () => {
    mockGetSsoConfig.mockResolvedValue(ssoConfig())

    renderWithProviders(<LoginPage onForgotPassword={() => {}} />)

    expect(await screen.findByRole('button', { name: /continue with opentext/i })).toBeInTheDocument()
  })

  it('names whichever provider the deployment configured, not a hardcoded one', async () => {
    // The same build serves a customer on OpenText and one on Entra ID; only the API answer
    // differs. A vendor name compiled into the bundle would be the coupling this guards against.
    mockGetSsoConfig.mockResolvedValue(ssoConfig({
      providerId: 'entra',
      providerName: 'Microsoft Entra ID',
      authorizationUrl: '/api/oauth2/authorization/entra',
    }))

    renderWithProviders(<LoginPage onForgotPassword={() => {}} />)

    expect(await screen.findByRole('button', { name: /continue with microsoft entra id/i }))
      .toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /opentext/i })).not.toBeInTheDocument()
  })

  it('shows only the password form when there is no directory behind this deployment', async () => {
    mockGetSsoConfig.mockResolvedValue(
      ssoConfig({ enabled: false, providerId: null, providerName: null, authorizationUrl: null }))

    renderWithProviders(<LoginPage onForgotPassword={() => {}} />)

    await waitFor(() => expect(mockGetSsoConfig).toHaveBeenCalled())
    expect(screen.queryByRole('button', { name: /continue with/i })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^sign in$/i })).toBeInTheDocument()
  })

  it('still signs people in with a password when the SSO question cannot be answered', async () => {
    // The endpoint being unreachable must not take the password form down with it.
    mockGetSsoConfig.mockRejectedValue(new Error('network'))

    renderWithProviders(<LoginPage onForgotPassword={() => {}} />)

    await waitFor(() => expect(mockGetSsoConfig).toHaveBeenCalled())
    expect(screen.getByRole('button', { name: /^sign in$/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /continue with/i })).not.toBeInTheDocument()
  })

  it('keeps the password form even when the directory is offered', async () => {
    mockGetSsoConfig.mockResolvedValue(ssoConfig())

    renderWithProviders(<LoginPage onForgotPassword={() => {}} />)

    await screen.findByRole('button', { name: /continue with opentext/i })
    expect(screen.getByLabelText(/email/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument()
  })

  it('hands the browser to the provider rather than fetching it', async () => {
    // A full navigation, not an XHR: the provider replies with redirects and its own screens, and
    // consults a session cookie on its own origin that this one cannot read.
    const assign = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, assign } as Location)
    mockGetSsoConfig.mockResolvedValue(ssoConfig())

    renderWithProviders(<LoginPage onForgotPassword={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: /continue with opentext/i }))

    expect(assign).toHaveBeenCalledWith('/api/oauth2/authorization/otds')
    vi.restoreAllMocks()
  })

  it('falls back to a neutral label when the provider has no name', async () => {
    mockGetSsoConfig.mockResolvedValue(ssoConfig({ providerName: null }))

    renderWithProviders(<LoginPage onForgotPassword={() => {}} />)

    expect(await screen.findByRole('button', { name: /continue with single sign-on/i })).toBeInTheDocument()
  })

  /**
   * A deployment can decide for everyone, rather than leaving it to each visitor's click.
   *
   * What must never disappear alongside the button is the way back: a directory outage cannot
   * also be the day the bootstrap administrator — who exists in no directory — is locked out.
   */
  describe('when the deployment enforces it', () => {
    it('leaves for the provider without anyone clicking anything', async () => {
      const assign = vi.fn()
      vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, assign } as Location)
      mockGetSsoConfig.mockResolvedValue(ssoConfig({ enforced: true }))

      renderWithProviders(<LoginPage onForgotPassword={() => {}} />)

      await waitFor(() => expect(assign).toHaveBeenCalledWith('/api/oauth2/authorization/otds'))
      vi.restoreAllMocks()
    })

    it('offers a quiet way to sign in with a password instead, and honours it', async () => {
      const assign = vi.fn()
      vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, assign } as Location)
      mockGetSsoConfig.mockResolvedValue(ssoConfig({ enforced: true }))

      renderWithProviders(<LoginPage onForgotPassword={() => {}} />)
      await waitFor(() => expect(assign).toHaveBeenCalledTimes(1))

      await userEvent.click(await screen.findByRole('button', { name: /sign in with a password instead/i }))

      expect(screen.getByLabelText(/email/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/password/i)).toBeInTheDocument()
      // Never redirected a second time. That the choice also survives a reload is covered by
      // requestPasswordSignIn's own tests in sso.test.ts — window.location is stubbed here to
      // observe `assign`, which freezes the rest of the object and makes `search` unreliable to
      // read back through this same mock.
      expect(assign).toHaveBeenCalledTimes(1)
      vi.restoreAllMocks()
    })

    it('respects that choice from the very first render, before ever redirecting', async () => {
      window.history.replaceState(null, '', '/?password=1')
      const assign = vi.fn()
      vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, assign } as Location)
      mockGetSsoConfig.mockResolvedValue(ssoConfig({ enforced: true }))

      renderWithProviders(<LoginPage onForgotPassword={() => {}} />)

      await screen.findByRole('button', { name: /continue with opentext/i })
      expect(screen.getByLabelText(/email/i)).toBeInTheDocument()
      expect(assign).not.toHaveBeenCalled()
      vi.restoreAllMocks()
    })

    it('does not bounce a rejected sign-in straight back without showing why', async () => {
      // A failed round trip already landed here with something to say; leaving again immediately
      // would erase it before anyone read it.
      window.history.replaceState(null, '', '/sso/callback#sso_error=This+account+has+been+disabled.')
      const assign = vi.fn()
      vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, assign } as Location)
      mockGetSsoConfig.mockResolvedValue(ssoConfig({ enforced: true }))

      renderWithProviders(<LoginPage onForgotPassword={() => {}} />)

      expect(await screen.findByText('This account has been disabled.')).toBeInTheDocument()
      expect(screen.getByLabelText(/email/i)).toBeInTheDocument()
      expect(assign).not.toHaveBeenCalled()
      vi.restoreAllMocks()
    })
  })
})
