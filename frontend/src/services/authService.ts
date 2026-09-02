import { API_BASE } from '../config/env'
import { apiFetch, apiJson, jsonBody, toApiError } from './http'
import type { AuthResponse } from '../types'

/**
 * Sign-in and token rotation.
 *
 * These endpoints are the only ones that must work without a valid access token, so they bypass
 * the automatic refresh-and-retry in {@link apiFetch} rather than recursing through it.
 */
async function postUnauthenticated(path: string, body: unknown): Promise<AuthResponse> {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
  })
  if (!response.ok) throw await toApiError(response)
  return response.json() as Promise<AuthResponse>
}

export const login = (email: string, password: string): Promise<AuthResponse> =>
  postUnauthenticated('/auth/login', { email, password })

export const refreshSession = (refreshToken: string): Promise<AuthResponse> =>
  postUnauthenticated('/auth/refresh', { refreshToken })

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
