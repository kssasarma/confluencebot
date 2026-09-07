import userEvent from '@testing-library/user-event'
import { screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/render'
import { TOKEN_KEY } from '../../lib/token'
import SettingsDialog from './SettingsDialog'

/**
 * Settings, Admin and Ingestor Settings used to be three separate nav entries and three separate
 * full-page routes. They are now sections of one dialog, and a role still only ever sees the
 * sections it has permission for: a read-only admin sees Admin but not Ingestion, an ingestor sees
 * Ingestion but not Admin, and a full admin sees both — General is unconditional for everyone.
 */

interface StubUser {
  id: number
  email: string
  name?: string | null
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
        return json({ userId: 1, email: 'signed-in@example.com', name: 'Signed In', roles: meRoles, mustChangePassword: false })
      }
      if (url.includes('/user/preferences')) {
        return json({
          theme: 'system', language: 'en', responseStyle: 'balanced',
          showSources: true, showConfidence: true,
        })
      }
      if (url.endsWith('/admin/users') && method === 'GET') return json(users)
      if (url.endsWith('/admin/users') && method === 'POST') {
        const body = JSON.parse(String(init?.body ?? '{}')) as { email: string; roles?: string[] }
        return json({
          user: {
            id: 99, email: body.email, name: null, roles: body.roles?.length ? body.roles : ['USER'],
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
      const resendMatch = url.match(/\/admin\/users\/(\d+)\/resend-welcome$/)
      if (resendMatch && method === 'POST') {
        const id = Number(resendMatch[1])
        const existing = users.find(u => u.id === id)
        return json({
          user: { ...existing, mustChangePassword: true },
          tempPassword: 'resent-pass-456',
          emailSent,
        })
      }
      const deleteMatch = url.match(/\/admin\/users\/(\d+)$/)
      if (deleteMatch && method === 'DELETE') {
        return new Response(null, { status: 204 })
      }
      if (url.includes('/ingest/jobs')) return json(jobs)
      // Asked once on mount by the auth provider; this deployment has no directory configured.
      if (url.includes('/auth/sso')) {
        return json({ enabled: false, providerId: null, providerName: null, authorizationUrl: null, logoutUrl: null })
      }

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

describe('SettingsDialog section visibility per role', () => {
  it('opens on General, and a full admin can also reach Admin and Ingestion', async () => {
    seedToken()
    vi.stubGlobal('fetch', makeFetchMock({ meRoles: ['ADMIN'], users: [] }))

    renderWithProviders(<SettingsDialog open onClose={vi.fn()} />)

    expect(await screen.findByRole('tab', { name: 'General', selected: true })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Admin' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Ingestion' })).toBeInTheDocument()
  })

  it('an admin_read_only sees General and Admin, but not Ingestion', async () => {
    seedToken()
    vi.stubGlobal('fetch', makeFetchMock({ meRoles: ['ADMIN_READ_ONLY'], users: [] }))

    renderWithProviders(<SettingsDialog open onClose={vi.fn()} />)

    expect(await screen.findByRole('tab', { name: 'Admin' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Ingestion' })).not.toBeInTheDocument()
  })

  it('an ingestor sees General and Ingestion, but not Admin', async () => {
    seedToken()
    vi.stubGlobal('fetch', makeFetchMock({ meRoles: ['INGESTOR'], jobs: [] }))

    renderWithProviders(<SettingsDialog open onClose={vi.fn()} />)

    expect(await screen.findByRole('tab', { name: 'Ingestion' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Admin' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('tab', { name: 'Ingestion' }))
    expect(await screen.findByText(/ingest a space/i)).toBeInTheDocument()
  })

  it('a plain user sees only General', async () => {
    seedToken()
    vi.stubGlobal('fetch', makeFetchMock({ meRoles: ['USER'] }))

    renderWithProviders(<SettingsDialog open onClose={vi.fn()} />)

    await screen.findByRole('tab', { name: 'General' })
    expect(screen.queryByRole('tab', { name: 'Admin' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Ingestion' })).not.toBeInTheDocument()
  })
})

describe('SettingsDialog Admin section', () => {
  async function openAdminTab() {
    await userEvent.click(await screen.findByRole('tab', { name: 'Admin' }))
  }

  it('lets a full admin grant an additional role to another user', async () => {
    seedToken()
    const users: StubUser[] = [
      { id: 1, email: 'signed-in@example.com', roles: ['ADMIN'], enabled: true, mustChangePassword: false, createdAt: '2026-01-01T00:00:00Z' },
      { id: 2, email: 'other@example.com', roles: ['USER'], enabled: true, mustChangePassword: false, createdAt: '2026-01-01T00:00:00Z' },
    ]
    const fetchMock = makeFetchMock({ meRoles: ['ADMIN'], users })
    vi.stubGlobal('fetch', fetchMock)

    renderWithProviders(<SettingsDialog open onClose={vi.fn()} />)
    await openAdminTab()

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

    renderWithProviders(<SettingsDialog open onClose={vi.fn()} />)
    await openAdminTab()

    const ownRow = (await screen.findByText('signed-in@example.com')).closest('tr')
    if (!ownRow) throw new Error('expected a table row for the signed-in admin')
    expect(within(ownRow).queryByRole('checkbox')).not.toBeInTheDocument()
    expect(within(ownRow).getByText('Admin')).toBeInTheDocument()
  })

  it('submits every role selected in the toggle group when creating a user', async () => {
    seedToken()
    const fetchMock = makeFetchMock({ meRoles: ['ADMIN'], users: [] })
    vi.stubGlobal('fetch', fetchMock)

    renderWithProviders(<SettingsDialog open onClose={vi.fn()} />)
    await openAdminTab()

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

  it('asks for confirmation, then deletes and removes the row', async () => {
    seedToken()
    const users: StubUser[] = [
      { id: 1, email: 'signed-in@example.com', roles: ['ADMIN'], enabled: true, mustChangePassword: false, createdAt: '2026-01-01T00:00:00Z' },
      { id: 2, email: 'other@example.com', roles: ['USER'], enabled: true, mustChangePassword: false, createdAt: '2026-01-01T00:00:00Z' },
    ]
    const fetchMock = makeFetchMock({ meRoles: ['ADMIN'], users })
    vi.stubGlobal('fetch', fetchMock)

    renderWithProviders(<SettingsDialog open onClose={vi.fn()} />)
    await openAdminTab()

    await userEvent.click(await screen.findByRole('button', { name: 'Delete other@example.com' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/admin/users/2'),
      expect.objectContaining({ method: 'DELETE' }),
    ))
    await waitFor(() => expect(screen.queryByText('other@example.com')).not.toBeInTheDocument())
  })

  it('confirms, then reports the welcome email was resent', async () => {
    seedToken()
    const users: StubUser[] = [
      { id: 2, email: 'pending@example.com', roles: ['USER'], enabled: true, mustChangePassword: true, createdAt: '2026-01-01T00:00:00Z' },
    ]
    const fetchMock = makeFetchMock({ meRoles: ['ADMIN'], users, emailSent: true })
    vi.stubGlobal('fetch', fetchMock)

    renderWithProviders(<SettingsDialog open onClose={vi.fn()} />)
    await openAdminTab()

    await userEvent.click(await screen.findByRole('button', { name: 'Resend welcome email to pending@example.com' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Resend' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/admin/users/2/resend-welcome'),
      expect.objectContaining({ method: 'POST' }),
    ))
    const banner = await screen.findByRole('status')
    expect(within(banner).getByText('Welcome email resent')).toBeInTheDocument()
  })
})
