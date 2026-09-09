import { createContext, useContext, useState, useEffect, useCallback, useRef, type ReactNode } from 'react'
import type { AuthUser, AuthResponse, SsoConfig, UserRole } from '../types'
import {
  login as apiLogin, getMe, changePassword as apiChangePassword,
  refreshSession, revokeSession, updateName as apiUpdateName,
  getSsoConfig, exchangeSsoCode,
} from '../services/authService'
import {
  clearSession, getRefreshToken, getSsoSessionProvider, getToken, markSsoSession, onSessionChange,
  storeSession,
} from '../lib/token'
import {
  clearJustLoggedOutMarker, clearSsoHandoff, readSsoHandoff, wasJustLoggedOut,
  withPostLogoutRedirect,
} from '../lib/sso'

interface AuthContextValue {
  user: AuthUser | null
  token: string | null
  isLoading: boolean
  isAuthenticated: boolean
  isAdmin: boolean
  /** ADMIN or ADMIN_READ_ONLY — enough to see and onboard users. */
  canManageUsers: boolean
  /** ADMIN or INGESTOR — enough to trigger and retrigger ingestion jobs. */
  canIngest: boolean
  /** Any role with a reason to open the admin screen at all. */
  canAdminister: boolean
  /** Null until the deployment has answered whether it has a directory to sign in through. */
  sso: SsoConfig | null
  /** Why the last trip through the identity provider did not end in a session. */
  ssoError: string | null
  dismissSsoError: () => void
  /** Set by `logout()` for a signed-out visitor who just clicked "Sign out", so the app can show
   * `LogoutPage` once instead of going straight back to `LoginPage`. */
  justLoggedOut: boolean
  dismissJustLoggedOut: () => void
  login: (email: string, password: string) => Promise<void>
  applySession: (data: AuthResponse) => void
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>
  updateName: (name: string) => Promise<void>
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
    name: data.name ?? null,
    roles: (data.roles ?? []) as UserRole[],
    mustChangePassword,
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [token, setToken] = useState<string | null>(() => getToken() || null)
  const [isLoading, setIsLoading] = useState(true)
  const [sso, setSso] = useState<SsoConfig | null>(null)
  const [ssoError, setSsoError] = useState<string | null>(null)
  const [justLoggedOut, setJustLoggedOut] = useState(false)
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
          if (handoff.providerId) markSsoSession(handoff.providerId)
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

    // Landing back from a provider-initiated logout (see `withPostLogoutRedirect`): the session
    // was already cleared before the browser left for the provider, so there is nothing to load —
    // just show LogoutPage instead of the sign-in screen, and don't let SSO-enforced auto-redirect
    // straight back into the provider (App.tsx checks `justLoggedOut` before it ever renders
    // LoginPage).
    if (wasJustLoggedOut()) {
      clearJustLoggedOutMarker()
      setJustLoggedOut(true)
      setIsLoading(false)
      return
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
    setJustLoggedOut(false)
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    applySession(await apiLogin(email, password))
  }, [applySession])

  const changePassword = useCallback(async (currentPassword: string, newPassword: string) => {
    applySession(await apiChangePassword(currentPassword, newPassword))
  }, [applySession])

  // Unlike changePassword, this never touches tokens: a name is not security-sensitive and other
  // sessions have no reason to be revoked over it.
  const updateName = useCallback(async (name: string) => {
    const data = await apiUpdateName(name)
    setUser(current => (current ? { ...current, name: data.name } : current))
  }, [])

  const dismissSsoError = useCallback(() => setSsoError(null), [])
  const dismissJustLoggedOut = useCallback(() => setJustLoggedOut(false), [])

  const logout = useCallback(() => {
    const refreshToken = getRefreshToken()
    // Read before clearing: clearing the session is what forgets where it came from.
    const sessionProvider = getSsoSessionProvider()
    if (refreshToken) void revokeSession(refreshToken)
    clearSession()

    // Ending the session here is not ending the one at the provider. Without this, signing out and
    // signing back in returns the same person with nothing asked of them, which does not look like
    // signing out at all. Only for a session that came from the provider now configured, though:
    // somebody who signed in with a password has no provider session to end, and one left over
    // from a provider this deployment no longer points at is not ours to end either.
    //
    // This has to happen synchronously, in the same tick as clearSession() above, and not after the
    // revoke request settles. clearSession() is what flips the app into its signed-out state, and
    // with SSO enforced that state redirects straight back to the provider on its own — so a
    // redirect here that waits on a network round trip loses the race: the enforced redirect fires
    // first, finds the provider's own session still alive, and signs back in before the browser
    // ever leaves for the provider's logout endpoint.
    const logoutUrl = sso?.logoutUrl
    if (logoutUrl && sessionProvider && sessionProvider === sso?.providerId) {
      // Told where to send the browser back, so it lands on this app's own sign-in screen
      // instead of whatever the provider shows by default — and with the password form already
      // requested, so an enforced deployment does not immediately leave for the provider again.
      window.location.assign(withPostLogoutRedirect(logoutUrl))
      return
    }

    // No provider round trip to make: stay on this page and show LogoutPage instead of jumping
    // straight back to LoginPage, so signing out reads as something that happened.
    setJustLoggedOut(true)
  }, [sso])

  const isAdmin = user?.roles.includes('ADMIN') ?? false
  const canManageUsers = isAdmin || (user?.roles.includes('ADMIN_READ_ONLY') ?? false)
  const canIngest = isAdmin || (user?.roles.includes('INGESTOR') ?? false)

  return (
    <AuthContext.Provider value={{
      user, token, isLoading,
      isAuthenticated: !!user,
      isAdmin,
      canManageUsers,
      canIngest,
      canAdminister: canManageUsers || canIngest,
      sso, ssoError, dismissSsoError,
      justLoggedOut, dismissJustLoggedOut,
      login, applySession, changePassword, updateName, logout,
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
