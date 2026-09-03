import { useMemo } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { ChatPreferences, UserPreferences } from '../types'
import {
  fetchChatPreferences, fetchUserPreferences, saveChatPreferences, updateUserPreferences,
} from '../services/userPreferenceService'
import { queryKeys } from '../services/queryKeys'
import { useToast } from '../components/ui/Toast'
import { toMessage } from '../lib/errors'

/**
 * What the account defaults to when the server has not answered yet.
 *
 * Sources on and confidence on: an assistant that shows its working by default is the safer
 * starting point for a tool people act on.
 */
const FALLBACK: UserPreferences = {
  theme: 'system',
  language: 'en',
  responseStyle: 'balanced',
  showSources: true,
  showConfidence: true,
}

export function useUserPreferences() {
  const queryClient = useQueryClient()
  const toast = useToast()

  const query = useQuery({
    queryKey: queryKeys.userPreferences,
    queryFn: fetchUserPreferences,
    staleTime: 5 * 60_000,
  })

  const mutation = useMutation({
    mutationFn: (patch: Partial<UserPreferences>) => updateUserPreferences(patch),
    onSuccess: updated => queryClient.setQueryData(queryKeys.userPreferences, updated),
    onError: error =>
      toast.error('Could not save your preferences', toMessage(error, 'Please try again.')),
  })

  return {
    preferences: query.data ?? FALLBACK,
    isLoading: query.isLoading,
    error: query.error ? toMessage(query.error, 'Could not load your preferences.') : null,
    save: mutation.mutate,
    isSaving: mutation.isPending,
  }
}

export function useChatPreferences(chatId: string | null) {
  const queryClient = useQueryClient()
  const toast = useToast()

  const query = useQuery({
    queryKey: queryKeys.chatPreferences(chatId ?? ''),
    queryFn: () => fetchChatPreferences(chatId!),
    enabled: !!chatId,
    staleTime: 5 * 60_000,
  })

  const mutation = useMutation({
    mutationFn: (preferences: ChatPreferences) => saveChatPreferences(chatId!, preferences),
    onSuccess: saved =>
      queryClient.setQueryData(queryKeys.chatPreferences(chatId ?? ''), saved),
    onError: error =>
      toast.error('Could not save the chat settings', toMessage(error, 'Please try again.')),
  })

  return {
    overrides: query.data ?? null,
    isLoading: query.isLoading,
    save: mutation.mutate,
    isSaving: mutation.isPending,
  }
}

export interface EffectiveDisplayPreferences {
  showSources: boolean
  showConfidence: boolean
}

/**
 * The preferences the transcript should actually be rendered with.
 *
 * This is the piece that was missing entirely: `showSources` and `showConfidence` were written by
 * two settings screens and read by nothing, so both toggles did exactly nothing while looking
 * like they worked. Nothing erodes trust in a settings page faster.
 *
 * A per-conversation override of `null` means "inherit the account default" — a tri-state the
 * backend models explicitly, and which a plain boolean would flatten into "off".
 */
export function useEffectiveDisplayPreferences(chatId: string | null): EffectiveDisplayPreferences {
  const { preferences } = useUserPreferences()
  const { overrides } = useChatPreferences(chatId)

  return useMemo(() => ({
    showSources: overrides?.showSources ?? preferences.showSources,
    showConfidence: overrides?.showConfidence ?? preferences.showConfidence,
  }), [overrides, preferences])
}
