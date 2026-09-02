import { createContext, useContext, useState, useEffect, useCallback, useRef, type ReactNode } from 'react'
import type { AuthUser, AuthResponse } from '../types'
import {
  login as apiLogin, getMe, changePassword as apiChangePassword,
  refreshSession, revokeSession,
} from '../services/authService'
import { clearSession, getRefreshToken, getToken, onSessionChange, storeSession } from '../lib/token'

interface AuthContextValue {
  user: AuthUser | null
  token: string | null
  isLoading: boolean
  isAuthenticated: boolean
  isAdmin: boolean
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

  useEffect(() => {
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

  const logout = useCallback(() => {
    const refreshToken = getRefreshToken()
    if (refreshToken) revokeSession(refreshToken)
    clearSession()
  }, [])

  return (
    <AuthContext.Provider value={{
      user, token, isLoading,
      isAuthenticated: !!user,
      isAdmin: user?.role === 'ADMIN',
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
