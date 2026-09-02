import { API_BASE } from '../config/env'
import type { AuthResponse } from '../types'

async function post(path: string, body: unknown, token?: string): Promise<AuthResponse> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  })
  if (res.status === 204) return {}
  const data = await res.json()
  if (!res.ok) return { error: data.message ?? data.error ?? 'Request failed' }
  return data
}

export async function login(email: string, password: string): Promise<AuthResponse> {
  return post('/auth/login', { email, password })
}

export async function refreshSession(refreshToken: string): Promise<AuthResponse> {
  return post('/auth/refresh', { refreshToken })
}

export async function revokeSession(refreshToken: string): Promise<void> {
  await post('/auth/logout', { refreshToken }).catch(() => {})
}

export async function getMe(token: string): Promise<AuthResponse> {
  const res = await fetch(`${API_BASE}/auth/me`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  if (!res.ok) return { error: 'Unauthorized' }
  return res.json()
}

export async function changePassword(
  token: string, currentPassword: string, newPassword: string
): Promise<AuthResponse> {
  return post('/auth/change-password', { currentPassword, newPassword }, token)
}
