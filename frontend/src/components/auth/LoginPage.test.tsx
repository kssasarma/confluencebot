import { beforeEach, describe, expect, it, vi } from 'vitest'
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

  function ssoConfig(overrides: Partial<SsoConfig> = {}): SsoConfig {
    return {
      enabled: true,
      providerId: 'otds',
      providerName: 'OpenText',
      authorizationUrl: '/api/oauth2/authorization/otds',
      logoutUrl: null,
      ...overrides,
    }
  }

  it('offers the provider by name once the deployment says it has one', async () => {
    mockGetSsoConfig.mockResolvedValue(ssoConfig())

    renderWithProviders(<LoginPage />)

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

    renderWithProviders(<LoginPage />)

    expect(await screen.findByRole('button', { name: /continue with microsoft entra id/i }))
      .toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /opentext/i })).not.toBeInTheDocument()
  })

  it('shows only the password form when there is no directory behind this deployment', async () => {
    mockGetSsoConfig.mockResolvedValue(
      ssoConfig({ enabled: false, providerId: null, providerName: null, authorizationUrl: null }))

    renderWithProviders(<LoginPage />)

    await waitFor(() => expect(mockGetSsoConfig).toHaveBeenCalled())
    expect(screen.queryByRole('button', { name: /continue with/i })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^sign in$/i })).toBeInTheDocument()
  })

  it('still signs people in with a password when the SSO question cannot be answered', async () => {
    // The endpoint being unreachable must not take the password form down with it.
    mockGetSsoConfig.mockRejectedValue(new Error('network'))

    renderWithProviders(<LoginPage />)

    await waitFor(() => expect(mockGetSsoConfig).toHaveBeenCalled())
    expect(screen.getByRole('button', { name: /^sign in$/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /continue with/i })).not.toBeInTheDocument()
  })

  it('keeps the password form even when the directory is offered', async () => {
    mockGetSsoConfig.mockResolvedValue(ssoConfig())

    renderWithProviders(<LoginPage />)

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

    renderWithProviders(<LoginPage />)
    await userEvent.click(await screen.findByRole('button', { name: /continue with opentext/i }))

    expect(assign).toHaveBeenCalledWith('/api/oauth2/authorization/otds')
    vi.restoreAllMocks()
  })

  it('falls back to a neutral label when the provider has no name', async () => {
    mockGetSsoConfig.mockResolvedValue(ssoConfig({ providerName: null }))

    renderWithProviders(<LoginPage />)

    expect(await screen.findByRole('button', { name: /continue with single sign-on/i })).toBeInTheDocument()
  })
})
