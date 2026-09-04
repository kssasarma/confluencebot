import userEvent from '@testing-library/user-event'
import { screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/render'
import { TOKEN_KEY } from '../lib/token'
import AdminRoute from './AdminRoute'
import { toggleRole } from '../lib/roles'
import type { AdminRole } from '../services/adminService'

describe('toggleRole', () => {
  it('adds a role that is not yet selected', () => {
    expect(toggleRole(['USER'], 'INGESTOR')).toEqual(['USER', 'INGESTOR'])
  })

  it('removes a role that is already selected', () => {
    expect(toggleRole(['USER', 'INGESTOR'], 'INGESTOR')).toEqual(['USER'])
  })

  it('refuses to remove the only remaining role', () => {
    const solo: AdminRole[] = ['ADMIN']
    expect(toggleRole(solo, 'ADMIN')).toBe(solo)
  })
})

/**
 * A user can now hold several roles, and each role unlocks its own tab of the admin screen: a
 * read-only admin only ever sees Users, an ingestor only ever sees Ingestion, and a full admin
 * sees both and can re-assign anyone else's roles from the table.
 */

interface StubUser {
  id: number
  email: string
  roles: string[]
  enabled: boolean
  mustChangePassword: boolean
  createdAt: string
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function makeFetchMock(options: { meRoles: string[]; users?: StubUser[]; jobs?: unknown[]; emailSent?: boolean }) {
  const { meRoles, users = [], jobs = [], emailSent = true } = options
  return vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(
    async (input, init) => {
      const url = String(input)
      const method = (init?.method ?? 'GET').toUpperCase()

      if (url.includes('/auth/me')) {
        return json({ userId: 1, email: 'signed-in@example.com', roles: meRoles, mustChangePassword: false })
      }
      if (url.endsWith('/admin/users') && method === 'GET') return json(users)
      if (url.endsWith('/admin/users') && method === 'POST') {
        const body = JSON.parse(String(init?.body ?? '{}')) as { email: string; roles?: string[] }
        return json({
          user: {
            id: 99, email: body.email, roles: body.roles?.length ? body.roles : ['USER'],
            enabled: true, mustChangePassword: true, createdAt: '2026-09-04T00:00:00Z',
          },
          tempPassword: 'temp-pass-123',
          emailSent,
        }, 201)
      }
      const rolesMatch = url.match(/\/admin\/users\/(\d+)\/roles$/)
      if (rolesMatch && method === 'PATCH') {
        const id = Number(rolesMatch[1])
        const existing = users.find(u => u.id === id)
        const body = JSON.parse(String(init?.body ?? '{}')) as { roles: string[] }
        return json({ ...existing, roles: body.roles })
      }
      if (url.includes('/ingest/jobs')) return json(jobs)

      return json({}, 404)
    },
  )
}

function seedToken() {
  localStorage.setItem(TOKEN_KEY, 'header.payload.signature')
}

beforeEach(() => {
  localStorage.clear()
})

describe('AdminRoute tab visibility per role', () => {
  it('a full admin sees both tabs, defaulting to Users', async () => {
    seedToken()
    vi.stubGlobal('fetch', makeFetchMock({ meRoles: ['ADMIN'], users: [] }))

    renderWithProviders(<AdminRoute />, { route: '/admin' })

    expect(await screen.findByRole('tab', { name: 'users', selected: true })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'ingestion' })).toBeInTheDocument()
  })

  it('an admin_read_only sees only the Users tab', async () => {
    seedToken()
    vi.stubGlobal('fetch', makeFetchMock({ meRoles: ['ADMIN_READ_ONLY'], users: [] }))

    renderWithProviders(<AdminRoute />, { route: '/admin' })

    expect(await screen.findByRole('tab', { name: 'users' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'ingestion' })).not.toBeInTheDocument()
  })

  it('an ingestor sees only the Ingestion tab, and it is already open', async () => {
    seedToken()
    vi.stubGlobal('fetch', makeFetchMock({ meRoles: ['INGESTOR'], jobs: [] }))

    renderWithProviders(<AdminRoute />, { route: '/admin' })

    expect(await screen.findByRole('tab', { name: 'ingestion' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'users' })).not.toBeInTheDocument()
    expect(await screen.findByText(/ingest a space/i)).toBeInTheDocument()
  })
})

describe('AdminRoute role re-assignment', () => {
  it('lets a full admin grant an additional role to another user', async () => {
    seedToken()
    const users: StubUser[] = [
      { id: 1, email: 'signed-in@example.com', roles: ['ADMIN'], enabled: true, mustChangePassword: false, createdAt: '2026-01-01T00:00:00Z' },
      { id: 2, email: 'other@example.com', roles: ['USER'], enabled: true, mustChangePassword: false, createdAt: '2026-01-01T00:00:00Z' },
    ]
    const fetchMock = makeFetchMock({ meRoles: ['ADMIN'], users })
    vi.stubGlobal('fetch', fetchMock)

    renderWithProviders(<AdminRoute />, { route: '/admin' })

    const otherRow = (await screen.findByText('other@example.com')).closest('tr')
    if (!otherRow) throw new Error('expected a table row for other@example.com')
    await userEvent.click(within(otherRow).getByRole('checkbox', { name: 'Ingestor' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/admin/users/2/roles'),
      expect.objectContaining({ method: 'PATCH' }),
    ))
    const [, patchInit] = fetchMock.mock.calls.find(([reqUrl]) =>
      String(reqUrl).includes('/admin/users/2/roles'))!
    expect(JSON.parse(String(patchInit?.body))).toEqual({ roles: ['USER', 'INGESTOR'] })
  })

  it('shows the signed-in admin their own roles as labels, not toggles', async () => {
    seedToken()
    const users: StubUser[] = [
      { id: 1, email: 'signed-in@example.com', roles: ['ADMIN'], enabled: true, mustChangePassword: false, createdAt: '2026-01-01T00:00:00Z' },
    ]
    vi.stubGlobal('fetch', makeFetchMock({ meRoles: ['ADMIN'], users }))

    renderWithProviders(<AdminRoute />, { route: '/admin' })

    const ownRow = (await screen.findByText('signed-in@example.com')).closest('tr')
    if (!ownRow) throw new Error('expected a table row for the signed-in admin')
    expect(within(ownRow).queryByRole('checkbox')).not.toBeInTheDocument()
    expect(within(ownRow).getByText('Admin')).toBeInTheDocument()
  })

  it('an admin_read_only sees roles as labels for every user, never toggles', async () => {
    seedToken()
    const users: StubUser[] = [
      { id: 2, email: 'other@example.com', roles: ['USER'], enabled: true, mustChangePassword: false, createdAt: '2026-01-01T00:00:00Z' },
    ]
    vi.stubGlobal('fetch', makeFetchMock({ meRoles: ['ADMIN_READ_ONLY'], users }))

    renderWithProviders(<AdminRoute />, { route: '/admin' })

    const row = (await screen.findByText('other@example.com')).closest('tr')
    if (!row) throw new Error('expected a table row for other@example.com')
    expect(within(row).queryByRole('checkbox')).not.toBeInTheDocument()
    expect(within(row).getByText('User')).toBeInTheDocument()
  })
})

describe('AdminRoute user creation', () => {
  it('submits every role selected in the toggle group', async () => {
    seedToken()
    const fetchMock = makeFetchMock({ meRoles: ['ADMIN'], users: [] })
    vi.stubGlobal('fetch', fetchMock)

    renderWithProviders(<AdminRoute />, { route: '/admin' })

    await userEvent.type(await screen.findByLabelText(/email/i), 'new.hire@example.com')
    const createForm = (await screen.findByLabelText(/email/i)).closest('form')
    if (!createForm) throw new Error('expected the create-user form')
    await userEvent.click(within(createForm).getByRole('checkbox', { name: 'Ingestor' }))
    await userEvent.click(within(createForm).getByRole('button', { name: /add user/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      expect.stringMatching(/\/admin\/users$/),
      expect.objectContaining({ method: 'POST' }),
    ))
    const [, postInit] = fetchMock.mock.calls.find(([reqUrl], index) =>
      String(reqUrl).match(/\/admin\/users$/) && fetchMock.mock.calls[index][1]?.method === 'POST')!
    expect(JSON.parse(String(postInit?.body))).toMatchObject({
      email: 'new.hire@example.com',
      roles: ['USER', 'INGESTOR'],
    })
  })

  it('tells the admin the welcome email was sent instead of showing the password', async () => {
    seedToken()
    vi.stubGlobal('fetch', makeFetchMock({ meRoles: ['ADMIN'], users: [], emailSent: true }))

    renderWithProviders(<AdminRoute />, { route: '/admin' })

    await userEvent.type(await screen.findByLabelText(/email/i), 'new.hire@example.com')
    await userEvent.click(await screen.findByRole('button', { name: /add user/i }))

    const banner = await screen.findByRole('status')
    expect(within(banner).getByText(/sign-in instructions were emailed/i)).toBeInTheDocument()
    expect(within(banner).queryByText('temp-pass-123')).not.toBeInTheDocument()
  })

  it('falls back to showing the password when the welcome email could not be sent', async () => {
    seedToken()
    vi.stubGlobal('fetch', makeFetchMock({ meRoles: ['ADMIN'], users: [], emailSent: false }))

    renderWithProviders(<AdminRoute />, { route: '/admin' })

    await userEvent.type(await screen.findByLabelText(/email/i), 'new.hire@example.com')
    await userEvent.click(await screen.findByRole('button', { name: /add user/i }))

    const banner = await screen.findByRole('status')
    expect(within(banner).getByText('temp-pass-123')).toBeInTheDocument()
  })
})
