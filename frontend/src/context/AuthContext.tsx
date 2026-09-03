import { createContext, useContext, useState, useEffect, useCallback, useRef, type ReactNode } from 'react'
import type { AuthUser, AuthResponse, SsoConfig } from '../types'
import {
  login as apiLogin, getMe, changePassword as apiChangePassword,
  refreshSession, revokeSession, getSsoConfig, exchangeSsoCode,
} from '../services/authService'
import {
  clearSession, getRefreshToken, getToken, isSsoSession, markSsoSession, onSessionChange, storeSession,
} from '../lib/token'
import { clearSsoHandoff, readSsoHandoff } from '../lib/sso'

interface AuthContextValue {
  user: AuthUser | null
  token: string | null
  isLoading: boolean
  isAuthenticated: boolean
  isAdmin: boolean
  /** Either admin role — enough to reach the admin screen, not enough to act everywhere on it. */
  canAdminister: boolean
  /** Null until the deployment has answered whether it has a directory to sign in through. */
  sso: SsoConfig | null
  /** Why the last trip through the identity provider did not end in a session. */
  ssoError: string | null
  dismissSsoError: () => void
  login: (email: string, password: string) => Promise<void>
  applySession: (data: AuthResponse) => void
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

const RENEW_BEFORE_MS = 60_000
const MIN_DELAY_MS = 5_000

function decodeJwt(token: string): { mustChangePassword: boolean; exp: number | null } {
  try {
    const payload = token.split('.')[1]
    const json = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')))
    return { mustChangePassword: json.mustChangePassword ?? false, exp: json.exp ?? null }
  } catch {
    return { mustChangePassword: false, exp: null }
  }
}

function toAuthUser(data: AuthResponse, token: string): AuthUser {
  const { mustChangePassword } = decodeJwt(token)
  return {
    userId: data.userId!,
    email: data.email!,
    role: data.role as AuthUser['role'],
    mustChangePassword,
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [token, setToken] = useState<string | null>(() => getToken() || null)
  const [isLoading, setIsLoading] = useState(true)
  const [sso, setSso] = useState<SsoConfig | null>(null)
  const [ssoError, setSsoError] = useState<string | null>(null)
  const renewTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const cancelRenewal = useCallback(() => {
    if (renewTimerRef.current) {
      clearTimeout(renewTimerRef.current)
      renewTimerRef.current = null
    }
  }, [])

  /** Renews shortly before expiry so an idle tab does not need a failed request to notice. */
  const scheduleRenewal = useCallback((accessToken: string) => {
    cancelRenewal()
    const { exp } = decodeJwt(accessToken)
    if (!exp) return
    const delay = Math.max(exp * 1000 - Date.now() - RENEW_BEFORE_MS, MIN_DELAY_MS)
    renewTimerRef.current = setTimeout(() => {
      const refreshToken = getRefreshToken()
      if (!refreshToken) return
      refreshSession(refreshToken)
        .then(data => { if (data.token) storeSession(data) })
        .catch(() => clearSession())
    }, delay)
  }, [cancelRenewal])

  // The HTTP layer rotates tokens on its own when a request meets a 401; mirror whatever it stored.
  useEffect(() => onSessionChange(nextToken => {
    setToken(nextToken)
    if (!nextToken) {
      cancelRenewal()
      setUser(null)
      return
    }
    scheduleRenewal(nextToken)
    const { mustChangePassword } = decodeJwt(nextToken)
    setUser(current => (current ? { ...current, mustChangePassword } : current))
  }), [cancelRenewal, scheduleRenewal])

  // Asked once, and never gated on: the sign-in screen renders a password form either way, and
  // gains a second button if the answer arrives saying there is a directory behind it.
  useEffect(() => {
    let cancelled = false
    getSsoConfig()
      .then(config => { if (!cancelled) setSso(config) })
      .catch(() => { /* a deployment without SSO answers this too; the password form still works */ })
    return () => { cancelled = true }
  }, [])

  useEffect(() => {
    // Whatever the identity provider redirected back with decides this page load, so it is read —
    // and erased from the address bar — before anything else looks at where the browser is.
    const handoff = readSsoHandoff()
    if (handoff) clearSsoHandoff()

    if (handoff?.code) {
      exchangeSsoCode(handoff.code)
        .then(session => {
          markSsoSession()
          applySession(session)
        })
        .catch(error => setSsoError(
          error instanceof Error ? error.message : 'Signing in through your identity provider failed.'))
        .finally(() => setIsLoading(false))
      return
    }
    if (handoff?.error) {
      setSsoError(handoff.error)
    }

    const stored = getToken()
    if (!stored) {
      setIsLoading(false)
      return
    }
    getMe()
      .then(data => {
        setUser(toAuthUser(data, getToken()))
        setToken(getToken())
        scheduleRenewal(getToken())
      })
      .catch(async () => {
        const refreshToken = getRefreshToken()
        if (!refreshToken) return clearSession()
        try {
          const data = await refreshSession(refreshToken)
          storeSession(data)
          const me = await getMe()
          setUser(toAuthUser(me, getToken()))
        } catch {
          clearSession()
        }
      })
      .finally(() => setIsLoading(false))
    // Runs once on mount: the stored token is read directly rather than tracked as a dependency.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => cancelRenewal, [cancelRenewal])

  const applySession = useCallback((data: AuthResponse) => {
    if (!data.token) return
    storeSession(data)
    setUser(toAuthUser(data, data.token))
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    applySession(await apiLogin(email, password))
  }, [applySession])

  const changePassword = useCallback(async (currentPassword: string, newPassword: string) => {
    applySession(await apiChangePassword(currentPassword, newPassword))
  }, [applySession])

  const dismissSsoError = useCallback(() => setSsoError(null), [])

  const logout = useCallback(() => {
    const refreshToken = getRefreshToken()
    // Read before clearing: clearing the session is what forgets where it came from.
    const cameFromProvider = isSsoSession()
    const revoked = refreshToken ? revokeSession(refreshToken) : Promise.resolve()
    clearSession()

    // Ending the session here is not ending the one at the provider. Without this, signing out and
    // signing back in returns the same person with nothing asked of them, which does not look like
    // signing out at all. Only for a session that actually came from the provider, though: someone
    // who signed in with a password has no provider session to end. The revoke is given a chance
    // to land before the browser leaves.
    const logoutUrl = sso?.logoutUrl
    if (logoutUrl && cameFromProvider) void revoked.finally(() => window.location.assign(logoutUrl))
  }, [sso])

  return (
    <AuthContext.Provider value={{
      user, token, isLoading,
      isAuthenticated: !!user,
      isAdmin: user?.role === 'ADMIN',
      canAdminister: user?.role === 'ADMIN' || user?.role === 'ADMIN_READ_ONLY',
      sso, ssoError, dismissSsoError,
      login, applySession, changePassword, logout,
    }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
