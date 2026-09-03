/**
 * The single owner of the tokens in local storage.
 *
 * Both the auth context and the HTTP layer read and rotate the session, so keeping the keys and
 * the notification in one module is what stops the two from drifting apart.
 */
import type { AuthResponse } from '../types'

export const TOKEN_KEY = 'cb_token'
export const REFRESH_KEY = 'cb_refresh'

/**
 * Whether *this* session was started through the identity provider.
 *
 * Not the same question as whether the account could have been. An administrator whose account is
 * linked to the directory but who just signed in with a password has no provider session to end,
 * and bouncing them through the provider's sign-out screen on their way out would be baffling.
 */
export const SSO_SESSION_KEY = 'cb_sso'

type SessionListener = (token: string | null) => void

const listeners = new Set<SessionListener>()

export const getToken = (): string => localStorage.getItem(TOKEN_KEY) ?? ''
export const getRefreshToken = (): string | null => localStorage.getItem(REFRESH_KEY)
export const isSsoSession = (): boolean => localStorage.getItem(SSO_SESSION_KEY) === 'true'

/** Called once, where a directory sign-in is redeemed. Survives token rotation; dies with the session. */
export const markSsoSession = (): void => localStorage.setItem(SSO_SESSION_KEY, 'true')

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
  localStorage.removeItem(SSO_SESSION_KEY)
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
