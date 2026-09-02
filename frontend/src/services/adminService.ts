import { API_BASE } from '../config/env'

export interface AdminUser {
  id: number
  email: string
  role: string
  enabled: boolean
  mustChangePassword: boolean
  createdAt: string
}

export interface CreateUserResult {
  user: AdminUser
  tempPassword: string
}

export interface IngestionJob {
  jobId: string
  jobType: string
  spaceKey: string | null
  pageId: string | null
  force: boolean
  status: string
  createdAt: string
  startedAt: string | null
  completedAt: string | null
  pagesProcessed: number | null
  chunksStored: number | null
  pagesSkipped: number | null
  errorMessage: string | null
}

async function authFetch<T>(path: string, token: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      ...(options?.headers ?? {}),
    },
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.detail ?? err.message ?? `HTTP ${res.status}`)
  }
  return res.json() as Promise<T>
}

export async function listUsers(token: string): Promise<AdminUser[]> {
  return authFetch('/admin/users', token)
}

export async function createUser(
  token: string,
  email: string,
  role: string,
  tempPassword?: string,
): Promise<CreateUserResult> {
  return authFetch('/admin/users', token, {
    method: 'POST',
    body: JSON.stringify({ email, role, tempPassword }),
  })
}

export async function setUserEnabled(token: string, id: number, enabled: boolean): Promise<AdminUser> {
  return authFetch(`/admin/users/${id}/enabled`, token, {
    method: 'PATCH',
    body: JSON.stringify({ enabled }),
  })
}

export async function ingestSpace(token: string, spaceKey: string, force = false): Promise<IngestionJob> {
  return authFetch('/ingest/space', token, {
    method: 'POST',
    body: JSON.stringify({ spaceKey, force }),
  })
}

export async function ingestPage(token: string, pageId: string): Promise<IngestionJob> {
  return authFetch(`/ingest/page/${pageId}`, token, { method: 'POST' })
}

export async function listJobs(token: string): Promise<IngestionJob[]> {
  return authFetch('/ingest/jobs', token)
}

export async function getJob(token: string, jobId: string): Promise<IngestionJob> {
  return authFetch(`/ingest/jobs/${jobId}`, token)
}
