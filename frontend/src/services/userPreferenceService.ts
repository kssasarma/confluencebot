import { API_BASE } from '../config/env'
import { authHeader } from '../lib/token'
import type { UserPreferences, ChatPreferences } from '../types'

export async function fetchUserPreferences(): Promise<UserPreferences> {
  const res = await fetch(`${API_BASE}/user/preferences`, { headers: authHeader() })
  if (!res.ok) throw new Error('Failed to fetch preferences')
  return res.json()
}

export async function updateUserPreferences(patch: Partial<UserPreferences>): Promise<UserPreferences> {
  const res = await fetch(`${API_BASE}/user/preferences`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', ...authHeader() },
    body: JSON.stringify(patch),
  })
  if (!res.ok) throw new Error('Failed to update preferences')
  return res.json()
}

export async function fetchChatPreferences(chatId: string): Promise<ChatPreferences> {
  const res = await fetch(`${API_BASE}/user/chats/${chatId}/preferences`, { headers: authHeader() })
  if (!res.ok) throw new Error('Failed to fetch chat preferences')
  return res.json()
}

export async function updateChatPreferences(chatId: string, patch: Partial<ChatPreferences>): Promise<ChatPreferences> {
  const res = await fetch(`${API_BASE}/user/chats/${chatId}/preferences`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', ...authHeader() },
    body: JSON.stringify(patch),
  })
  if (!res.ok) throw new Error('Failed to update chat preferences')
  return res.json()
}
