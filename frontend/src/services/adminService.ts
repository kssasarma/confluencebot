import { apiJson, jsonBody } from './http'

/**
 * Administration endpoints.
 *
 * These go through `apiJson` like everything else. The previous version hand-rolled its own
 * `fetch` with the token threaded through every call site, which meant an expired token failed
 * the request outright instead of being refreshed and replayed — the one thing the shared HTTP
 * layer exists to do.
 */

export type AdminRole = 'ADMIN' | 'ADMIN_READ_ONLY' | 'INGESTOR' | 'USER'

export interface AdminUser {
  id: number
  email: string
  roles: AdminRole[]
  enabled: boolean
  mustChangePassword: boolean
  createdAt: string
}

export interface CreateUserResult {
  user: AdminUser
  tempPassword: string
  /** Whether the welcome email carrying the temp password reached the user. */
  emailSent: boolean
}

export interface IngestionJob {
  jobId: string
  jobType: string
  spaceKey: string | null
  pageId: string | null
  force: boolean
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | string
  createdAt: string
  startedAt: string | null
  completedAt: string | null
  pagesProcessed: number | null
  chunksStored: number | null
  pagesSkipped: number | null
  errorMessage: string | null
}

export const listUsers = (): Promise<AdminUser[]> => apiJson<AdminUser[]>('/admin/users')

export const createUser = (
  email: string,
  roles: AdminRole[],
  tempPassword?: string,
): Promise<CreateUserResult> =>
  apiJson<CreateUserResult>('/admin/users', {
    method: 'POST',
    ...jsonBody({ email, roles, tempPassword }),
  })

export const setUserEnabled = (id: number, enabled: boolean): Promise<AdminUser> =>
  apiJson<AdminUser>(`/admin/users/${id}/enabled`, { method: 'PATCH', ...jsonBody({ enabled }) })

export const setUserRoles = (id: number, roles: AdminRole[]): Promise<AdminUser> =>
  apiJson<AdminUser>(`/admin/users/${id}/roles`, { method: 'PATCH', ...jsonBody({ roles }) })

export const ingestSpace = (spaceKey: string, force = false): Promise<IngestionJob> =>
  apiJson<IngestionJob>('/ingest/space', { method: 'POST', ...jsonBody({ spaceKey, force }) })

export const ingestPage = (pageId: string): Promise<IngestionJob> =>
  apiJson<IngestionJob>(`/ingest/page/${pageId}`, { method: 'POST' })

export const listJobs = (): Promise<IngestionJob[]> => apiJson<IngestionJob[]>('/ingest/jobs')

export const getJob = (jobId: string): Promise<IngestionJob> =>
  apiJson<IngestionJob>(`/ingest/jobs/${jobId}`)

/** Resubmits a failed job. The failure stays in the history; this returns the new job. */
export const retriggerJob = (jobId: string): Promise<IngestionJob> =>
  apiJson<IngestionJob>(`/ingest/jobs/${jobId}/retrigger`, { method: 'POST' })
