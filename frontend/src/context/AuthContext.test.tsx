import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider, useAuth } from './AuthContext'
import { TOKEN_KEY } from '../lib/token'

/**
 * A user can now hold several roles at once, so "can this reader reach the admin area" is no
 * longer one flag — it is the union of what each of their roles unlocks. These pin that union for
 * every role combination the product cares about: a plain user, each elevated role alone, and a
 * user who holds more than one at the same time.
 */

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

function Probe() {
  const { user, isLoading, isAdmin, canManageUsers, canIngest, canAdminister } = useAuth()
  if (isLoading || !user) return <div>loading</div>
  return (
    <ul>
      <li>roles:{user.roles.join(',')}</li>
      <li>isAdmin:{String(isAdmin)}</li>
      <li>canManageUsers:{String(canManageUsers)}</li>
      <li>canIngest:{String(canIngest)}</li>
      <li>canAdminister:{String(canAdminister)}</li>
    </ul>
  )
}

function renderAsSignedIn(roles: string[]) {
  localStorage.setItem(TOKEN_KEY, 'header.payload.signature')
  fetchMock.mockResolvedValue(json({
    userId: 1, email: 'reader@example.com', roles, mustChangePassword: false,
  }))
  return render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  )
}

describe('role-derived permissions', () => {
  it('a plain USER has no elevated permission', async () => {
    renderAsSignedIn(['USER'])

    expect(await screen.findByText('roles:USER')).toBeInTheDocument()
    expect(screen.getByText('isAdmin:false')).toBeInTheDocument()
    expect(screen.getByText('canManageUsers:false')).toBeInTheDocument()
    expect(screen.getByText('canIngest:false')).toBeInTheDocument()
    expect(screen.getByText('canAdminister:false')).toBeInTheDocument()
  })

  it('an INGESTOR can ingest and reach the admin area, but cannot manage users', async () => {
    renderAsSignedIn(['INGESTOR'])

    expect(await screen.findByText('roles:INGESTOR')).toBeInTheDocument()
    expect(screen.getByText('isAdmin:false')).toBeInTheDocument()
    expect(screen.getByText('canManageUsers:false')).toBeInTheDocument()
    expect(screen.getByText('canIngest:true')).toBeInTheDocument()
    expect(screen.getByText('canAdminister:true')).toBeInTheDocument()
  })

  it('an ADMIN_READ_ONLY can manage users and reach the admin area, but cannot ingest', async () => {
    renderAsSignedIn(['ADMIN_READ_ONLY'])

    expect(await screen.findByText('roles:ADMIN_READ_ONLY')).toBeInTheDocument()
    expect(screen.getByText('isAdmin:false')).toBeInTheDocument()
    expect(screen.getByText('canManageUsers:true')).toBeInTheDocument()
    expect(screen.getByText('canIngest:false')).toBeInTheDocument()
    expect(screen.getByText('canAdminister:true')).toBeInTheDocument()
  })

  it('an ADMIN has every elevated permission', async () => {
    renderAsSignedIn(['ADMIN'])

    expect(await screen.findByText('roles:ADMIN')).toBeInTheDocument()
    expect(screen.getByText('isAdmin:true')).toBeInTheDocument()
    expect(screen.getByText('canManageUsers:true')).toBeInTheDocument()
    expect(screen.getByText('canIngest:true')).toBeInTheDocument()
    expect(screen.getByText('canAdminister:true')).toBeInTheDocument()
  })

  it('a user holding two roles at once gets the union of both', async () => {
    renderAsSignedIn(['INGESTOR', 'ADMIN_READ_ONLY'])

    expect(await screen.findByText('roles:INGESTOR,ADMIN_READ_ONLY')).toBeInTheDocument()
    expect(screen.getByText('isAdmin:false')).toBeInTheDocument()
    expect(screen.getByText('canManageUsers:true')).toBeInTheDocument()
    expect(screen.getByText('canIngest:true')).toBeInTheDocument()
    expect(screen.getByText('canAdminister:true')).toBeInTheDocument()
  })
})
