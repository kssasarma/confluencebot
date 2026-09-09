import { API_BASE } from '../config/env'
import { apiFetch, apiJson, jsonBody, toApiError } from './http'
import type { AuthResponse, SsoConfig, UserInfoResponse } from '../types'

/**
 * Sign-in and token rotation.
 *
 * These endpoints are the only ones that must work without a valid access token, so they bypass
 * the automatic refresh-and-retry in {@link apiFetch} rather than recursing through it.
 */
async function postUnauthenticatedJson<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
  })
  if (!response.ok) throw await toApiError(response)
  return response.json() as Promise<T>
}

const postUnauthenticated = (path: string, body: unknown): Promise<AuthResponse> =>
  postUnauthenticatedJson<AuthResponse>(path, body)

async function getUnauthenticated<T>(path: string): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, { headers: { Accept: 'application/json' } })
  if (!response.ok) throw await toApiError(response)
  return response.json() as Promise<T>
}

/** Whether this deployment has a directory to sign in through, and where its button goes. */
export const getSsoConfig = (): Promise<SsoConfig> => getUnauthenticated<SsoConfig>('/auth/sso')

/**
 * Redeems the one-time code the provider handed the browser.
 *
 * Single-use and short-lived, so this is called exactly once per sign-in and a failure is final —
 * there is nothing to retry, only another trip through the provider.
 */
export const exchangeSsoCode = (code: string): Promise<AuthResponse> =>
  postUnauthenticated('/auth/sso/exchange', { code })

export const login = (email: string, password: string): Promise<AuthResponse> =>
  postUnauthenticated('/auth/login', { email, password })

export const refreshSession = (refreshToken: string): Promise<AuthResponse> =>
  postUnauthenticated('/auth/refresh', { refreshToken })

/**
 * Requests a one-time password-reset code by email. Always resolves — the backend deliberately
 * reports the same outcome whether or not the address is registered, so this never reveals which
 * emails have accounts. `emailSent: false` means mail is down or misconfigured, not that the
 * address is unknown; the reader's recourse then is asking an admin to re-share a temporary
 * password, which works independently of the mail relay.
 */
export const requestPasswordReset = (email: string): Promise<{ emailSent: boolean }> =>
  postUnauthenticatedJson('/auth/forgot-password/request', { email })

/** Redeems a one-time code for a new password and signs the reader in with it. */
export const resetPassword = (email: string, otp: string, newPassword: string): Promise<AuthResponse> =>
  postUnauthenticated('/auth/forgot-password/reset', { email, otp, newPassword })

export async function revokeSession(refreshToken: string): Promise<void> {
  try {
    await apiFetch('/auth/logout', {
      method: 'POST',
      ...jsonBody({ refreshToken }),
      skipAuthRetry: true,
    })
  } catch {
    /* signing out locally matters more than reaching the server */
  }
}

export const getMe = (): Promise<AuthResponse> =>
  apiJson<AuthResponse>('/auth/me', { skipAuthRetry: true })

export const changePassword = (currentPassword: string, newPassword: string): Promise<AuthResponse> =>
  apiJson<AuthResponse>('/auth/change-password', {
    method: 'POST',
    ...jsonBody({ currentPassword, newPassword }),
  })

/** Self-service only — there is no endpoint to change email. */
export const updateName = (name: string): Promise<UserInfoResponse> =>
  apiJson<UserInfoResponse>('/auth/name', { method: 'PATCH', ...jsonBody({ name }) })
