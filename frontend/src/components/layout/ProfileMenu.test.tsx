import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/render'
import { TOKEN_KEY } from '../../lib/token'
import ProfileMenu from './ProfileMenu'
import { buildProfileActions } from './profileActions'

/**
 * Every account-level action used to be split between a menu at the bottom of the sidebar and
 * nothing anywhere else. Settings, Admin and Ingestor Settings were later three separate entries
 * here; they are now the one "Settings" entry that opens the unified dialog, which does its own
 * permission-gating per section. The trigger tests below pin the single top-right replacement's
 * identity display; `buildProfileActions` is exercised directly (rather than through the rendered,
 * floating-ui-anchored dropdown, which this environment's stubbed ResizeObserver cannot settle).
 */

describe('buildProfileActions', () => {
  const base = {
    onOpenSettings: vi.fn(),
    onSignOut: vi.fn(),
  }

  it('offers Settings and Sign out', () => {
    const labels = buildProfileActions(base).map(a => a.label)

    expect(labels).toEqual(['Settings', 'Sign out'])
  })

  it('wires the Settings action to onOpenSettings', () => {
    const onOpenSettings = vi.fn()
    const actions = buildProfileActions({ ...base, onOpenSettings })

    actions.find(a => a.label === 'Settings')?.onSelect()

    expect(onOpenSettings).toHaveBeenCalledOnce()
  })

  it('marks Sign out as a separated, dangerous action', () => {
    const actions = buildProfileActions(base)
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

/**
 * A fresh Response per call, not one shared instance — a Response body can only be read once, and
 * the auth provider makes more than one request on mount (it also asks whether this deployment
 * offers single sign-on).
 */
function alwaysRespond(body: unknown, status = 200): void {
  fetchMock.mockImplementation(() => Promise.resolve(json(body, status)))
}

function signIn(roles: string[]) {
  localStorage.setItem(TOKEN_KEY, 'header.payload.signature')
  alwaysRespond({
    userId: 1, email: 'reader@example.com', name: 'Reader Person', roles, mustChangePassword: false,
  })
}

describe('ProfileMenu trigger', () => {
  it('renders nothing while signed out', () => {
    alwaysRespond({}, 401)

    renderWithProviders(<ProfileMenu onOpenSettings={vi.fn()} />)

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('shows the signed-in name, not the email, once a session resolves', async () => {
    signIn(['USER'])

    renderWithProviders(<ProfileMenu onOpenSettings={vi.fn()} />)

    expect(await screen.findByRole('button', { name: /account menu for reader@example.com/i }))
      .toBeInTheDocument()
    expect(screen.getByText('Reader Person')).toBeInTheDocument()
    expect(screen.queryByText('reader@example.com')).not.toBeInTheDocument()
  })

  it('falls back to the email when no name is set', async () => {
    localStorage.setItem(TOKEN_KEY, 'header.payload.signature')
    alwaysRespond({
      userId: 1, email: 'reader@example.com', name: null, roles: ['USER'], mustChangePassword: false,
    })

    renderWithProviders(<ProfileMenu onOpenSettings={vi.fn()} />)

    expect(await screen.findByText('reader@example.com')).toBeInTheDocument()
  })
})
