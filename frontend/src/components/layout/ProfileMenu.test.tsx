import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/render'
import { TOKEN_KEY } from '../../lib/token'
import ProfileMenu from './ProfileMenu'
import { buildProfileActions } from './profileActions'

/**
 * Every account-level action used to be split between a menu at the bottom of the sidebar and
 * nothing anywhere else. The trigger tests below pin the single top-right replacement's identity
 * display; `buildProfileActions` is exercised directly (rather than through the rendered,
 * floating-ui-anchored dropdown, which this environment's stubbed ResizeObserver cannot settle)
 * because it is the one rule that actually matters here: Admin is offered only to a reader who can
 * reach it, and every other action is unconditional.
 */

describe('buildProfileActions', () => {
  const base = {
    nextTheme: 'dark' as const,
    onThemeChange: vi.fn(),
    onGoToAdmin: vi.fn(),
    onGoToSettings: vi.fn(),
    onSignOut: vi.fn(),
  }

  it('includes Admin when the reader can administer', () => {
    const labels = buildProfileActions({ ...base, canAdminister: true }).map(a => a.label)

    expect(labels).toEqual(['Dark theme', 'Admin', 'Settings', 'Sign out'])
  })

  it('omits Admin when the reader cannot administer', () => {
    const labels = buildProfileActions({ ...base, canAdminister: false }).map(a => a.label)

    expect(labels).toEqual(['Dark theme', 'Settings', 'Sign out'])
  })

  it('wires the Admin action to onGoToAdmin', () => {
    const onGoToAdmin = vi.fn()
    const actions = buildProfileActions({ ...base, canAdminister: true, onGoToAdmin })

    actions.find(a => a.label === 'Admin')?.onSelect()

    expect(onGoToAdmin).toHaveBeenCalledOnce()
  })

  it('marks Sign out as a separated, dangerous action', () => {
    const actions = buildProfileActions({ ...base, canAdminister: false })
    const signOut = actions.find(a => a.label === 'Sign out')

    expect(signOut?.tone).toBe('danger')
    expect(signOut?.separated).toBe(true)
  })
})

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()

beforeEach(() => {
  localStorage.clear()
  vi.stubGlobal('fetch', fetchMock)
})

function signIn(roles: string[]) {
  localStorage.setItem(TOKEN_KEY, 'header.payload.signature')
  fetchMock.mockResolvedValue(json({
    userId: 1, email: 'reader@example.com', roles, mustChangePassword: false,
  }))
}

describe('ProfileMenu trigger', () => {
  it('renders nothing while signed out', () => {
    fetchMock.mockResolvedValue(json({}, 401))

    renderWithProviders(<ProfileMenu />)

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('shows the signed-in email once a session resolves', async () => {
    signIn(['USER'])

    renderWithProviders(<ProfileMenu />)

    expect(await screen.findByRole('button', { name: /account menu for reader@example.com/i }))
      .toBeInTheDocument()
  })
})
