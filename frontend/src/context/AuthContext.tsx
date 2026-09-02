import { createContext, useContext, useState, useEffect, useCallback, useRef, type ReactNode } from 'react'
import type { AuthUser, AuthResponse } from '../types'
import { login as apiLogin, getMe, changePassword as apiChangePassword, refreshSession, revokeSession } from '../services/authService'

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
const TOKEN_KEY = 'cb_token'
const REFRESH_KEY = 'cb_refresh'
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
  const [token, setToken] = useState<string | null>(() => localStorage.getItem(TOKEN_KEY))
  const [isLoading, setIsLoading] = useState(true)
  const renewTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const inFlightRef = useRef<Promise<boolean> | null>(null)

  function clearSession() {
    if (renewTimerRef.current) { clearTimeout(renewTimerRef.current); renewTimerRef.current = null }
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(REFRESH_KEY)
    setToken(null)
    setUser(null)
  }

  function scheduleRenewal(accessToken: string) {
    if (renewTimerRef.current) clearTimeout(renewTimerRef.current)
    const { exp } = decodeJwt(accessToken)
    if (!exp) return
    const delay = Math.max(exp * 1000 - Date.now() - RENEW_BEFORE_MS, MIN_DELAY_MS)
    renewTimerRef.current = setTimeout(() => { performRefresh() }, delay)
  }

  function applySessionInternal(data: AuthResponse) {
    if (!data.token) return
    localStorage.setItem(TOKEN_KEY, data.token)
    if (data.refreshToken) localStorage.setItem(REFRESH_KEY, data.refreshToken)
    setToken(data.token)
    setUser(toAuthUser(data, data.token))
    scheduleRenewal(data.token)
  }

  function performRefresh(): Promise<boolean> {
    if (inFlightRef.current) return inFlightRef.current
    const attempt = (async () => {
      const rt = localStorage.getItem(REFRESH_KEY)
      if (!rt) return false
      try {
        const data = await refreshSession(rt)
        if (data.error || !data.token) return false
        applySessionInternal(data)
        return true
      } catch { return false }
      finally { inFlightRef.current = null }
    })()
    inFlightRef.current = attempt
    return attempt
  }

  useEffect(() => {
    const stored = localStorage.getItem(TOKEN_KEY)
    if (!stored) { setIsLoading(false); return }
    getMe(stored).then(data => {
      if (data.userId && data.role) {
        setUser(toAuthUser(data, stored))
        setToken(stored)
        scheduleRenewal(stored)
      } else clearSession()
    }).catch(async () => {
      const renewed = await performRefresh()
      if (!renewed) clearSession()
    }).finally(() => setIsLoading(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const data = await apiLogin(email, password)
    if (data.error || !data.token) throw new Error(data.error ?? 'Login failed')
    applySessionInternal(data)
  }, [])

  const applySession = useCallback((data: AuthResponse) => { applySessionInternal(data) }, [])

  const changePassword = useCallback(async (currentPassword: string, newPassword: string) => {
    if (!token) throw new Error('Not authenticated')
    const data = await apiChangePassword(token, currentPassword, newPassword)
    if (data.error || !data.token) throw new Error(data.error ?? 'Password change failed')
    applySessionInternal(data)
  }, [token])

  const logout = useCallback(() => {
    const rt = localStorage.getItem(REFRESH_KEY)
    if (rt) revokeSession(rt)
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
