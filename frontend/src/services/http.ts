import { API_BASE } from '../config/env'
import { authHeader, clearSession, getRefreshToken, storeSession } from '../lib/token'
import type { AuthResponse } from '../types'

/** An error carrying the status and the RFC 9457 `detail` the backend sent. */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

interface RequestOptions extends RequestInit {
  /** Set for the refresh call itself, so a failing refresh cannot recurse. */
  skipAuthRetry?: boolean
}

let refreshInFlight: Promise<boolean> | null = null

/**
 * Fetches an API endpoint with the access token attached.
 *
 * An access token lives for minutes, so any request can outlive it — a laptop waking from sleep
 * hits this constantly. On a 401 the refresh token is redeemed once (single-flight across all
 * concurrent callers) and the request is replayed, which is invisible to the caller.
 */
export async function apiFetch(path: string, options: RequestOptions = {}): Promise<Response> {
  const { skipAuthRetry, ...init } = options
  const response = await fetch(`${API_BASE}${path}`, withAuth(init))

  if (response.status !== 401 || skipAuthRetry) return response
  if (!(await refreshAccessToken())) return response

  return fetch(`${API_BASE}${path}`, withAuth(init))
}

export async function apiJson<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await apiFetch(path, options)
  if (!response.ok) throw await toApiError(response)
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export async function apiVoid(path: string, options: RequestOptions = {}): Promise<void> {
  const response = await apiFetch(path, options)
  if (!response.ok) throw await toApiError(response)
}

export const jsonBody = (body: unknown): RequestInit => ({
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})

/** Reads the message out of a ProblemDetail response, falling back to something printable. */
export async function toApiError(response: Response): Promise<ApiError> {
  let message = ''
  try {
    const problem = await response.json() as { detail?: string; message?: string; error?: string }
    message = problem.detail ?? problem.message ?? problem.error ?? ''
  } catch {
    /* the body was not JSON — the status alone will have to do */
  }
  return new ApiError(response.status, message || defaultMessage(response.status))
}

function withAuth(init: RequestInit): RequestInit {
  return { ...init, headers: { ...(init.headers as Record<string, string>), ...authHeader() } }
}

function refreshAccessToken(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight

  refreshInFlight = (async () => {
    const refreshToken = getRefreshToken()
    if (!refreshToken) {
      clearSession()
      return false
    }
    try {
      const response = await fetch(`${API_BASE}/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      })
      if (!response.ok) {
        clearSession()
        return false
      }
      const data = await response.json() as AuthResponse
      if (!data.token) {
        clearSession()
        return false
      }
      storeSession(data)
      return true
    } catch {
      return false
    } finally {
      refreshInFlight = null
    }
  })()

  return refreshInFlight
}

function defaultMessage(status: number): string {
  if (status === 401) return 'Your session has expired. Please sign in again.'
  if (status === 403) return 'You do not have access to this.'
  if (status === 404) return 'Not found.'
  if (status === 503) return 'The assistant is unavailable right now. Please try again in a moment.'
  return 'Something went wrong. Please try again.'
}
