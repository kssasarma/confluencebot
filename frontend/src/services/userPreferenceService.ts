import { apiJson, jsonBody } from './http'
import type { UserPreferences, ChatPreferences } from '../types'

export const fetchUserPreferences = (): Promise<UserPreferences> =>
  apiJson<UserPreferences>('/user/preferences')

/** Partial update: every account-wide preference has a value, so an omitted field means "keep it". */
export const updateUserPreferences = (patch: Partial<UserPreferences>): Promise<UserPreferences> =>
  apiJson<UserPreferences>('/user/preferences', { method: 'PATCH', ...jsonBody(patch) })

export const fetchChatPreferences = (chatId: string): Promise<ChatPreferences> =>
  apiJson<ChatPreferences>(`/user/chats/${chatId}/preferences`)

/**
 * Replaces the per-conversation overrides.
 *
 * These are sent whole rather than merged: null is a meaningful value here — it is how the user
 * takes an override off again and goes back to inheriting their account default.
 */
export const saveChatPreferences = (
  chatId: string,
  preferences: ChatPreferences,
): Promise<ChatPreferences> =>
  apiJson<ChatPreferences>(`/user/chats/${chatId}/preferences`, {
    method: 'PUT',
    ...jsonBody({
      responseStyle: preferences.responseStyle ?? null,
      showSources: preferences.showSources ?? null,
      showConfidence: preferences.showConfidence ?? null,
      customPrompt: preferences.customPrompt?.trim() || null,
    }),
  })
