/**
 * The single owner of the tokens in local storage.
 *
 * Both the auth context and the HTTP layer read and rotate the session, so keeping the keys and
 * the notification in one module is what stops the two from drifting apart.
 */
import type { AuthResponse } from '../types'

export const TOKEN_KEY = 'cb_token'
export const REFRESH_KEY = 'cb_refresh'

type SessionListener = (token: string | null) => void

const listeners = new Set<SessionListener>()

export const getToken = (): string => localStorage.getItem(TOKEN_KEY) ?? ''
export const getRefreshToken = (): string | null => localStorage.getItem(REFRESH_KEY)

export const authHeader = (): Record<string, string> => {
  const token = getToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

export function storeSession(data: AuthResponse): void {
  if (!data.token) return
  localStorage.setItem(TOKEN_KEY, data.token)
  if (data.refreshToken) localStorage.setItem(REFRESH_KEY, data.refreshToken)
  notify(data.token)
}

export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(REFRESH_KEY)
  notify(null)
}

/** Notifies when the session is replaced or dropped — including by a background token refresh. */
export function onSessionChange(listener: SessionListener): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function notify(token: string | null): void {
  listeners.forEach(listener => listener(token))
}
