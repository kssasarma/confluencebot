import { useCallback, useEffect, useState } from 'react'

/**
 * State that survives a reload, stored in `localStorage`.
 *
 * Every read and write is guarded: storage throws outright in a private window, when the browser
 * is set to block site data, and when the quota is full. A layout preference is not worth
 * white-screening the application over, so a failure degrades to in-memory state.
 */
export function usePersistentState<T>(
  key: string,
  initial: T,
  /** Rejects stored values that are no longer valid — a width from an older, wider range. */
  validate?: (value: unknown) => value is T,
): [T, (value: T | ((current: T) => T)) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      const stored = localStorage.getItem(key)
      if (stored === null) return initial
      const parsed = JSON.parse(stored) as unknown
      if (validate && !validate(parsed)) return initial
      return parsed as T
    } catch {
      return initial
    }
  })

  useEffect(() => {
    try {
      localStorage.setItem(key, JSON.stringify(value))
    } catch {
      /* Storage is unavailable or full; the value still works for this session. */
    }
  }, [key, value])

  const update = useCallback((next: T | ((current: T) => T)) => setValue(next), [])

  return [value, update]
}

/**
 * Draft text, kept in `sessionStorage` and keyed per conversation.
 *
 * `sessionStorage` rather than `localStorage` deliberately: an unsent question is scoped to the
 * tab it was typed in, and should not reappear in a different window a week later.
 */
export function readDraft(chatId: string): string {
  try {
    return sessionStorage.getItem(`cb_draft_${chatId}`) ?? ''
  } catch {
    return ''
  }
}

export function writeDraft(chatId: string, text: string): void {
  try {
    if (text) sessionStorage.setItem(`cb_draft_${chatId}`, text)
    else sessionStorage.removeItem(`cb_draft_${chatId}`)
  } catch {
    /* An unsaved draft is a smaller loss than a thrown render. */
  }
}

/**
 * The Confluence space a conversation's questions are scoped to, kept per conversation and per
 * tab like the draft above: reopening the same chat in a different window starts unscoped rather
 * than silently inheriting a filter chosen somewhere else.
 */
export function readSpaceFilter(chatId: string): string | null {
  try {
    return sessionStorage.getItem(`cb_space_${chatId}`)
  } catch {
    return null
  }
}

export function writeSpaceFilter(chatId: string, spaceKey: string | null): void {
  try {
    if (spaceKey) sessionStorage.setItem(`cb_space_${chatId}`, spaceKey)
    else sessionStorage.removeItem(`cb_space_${chatId}`)
  } catch {
    /* A filter that resets on reload is a smaller loss than a thrown render. */
  }
}

/**
 * Chat preference overrides picked before a conversation exists on the server.
 *
 * The backend only accepts a preferences write once the conversation's row exists, which happens
 * on the first recorded turn — so a choice made while the composer is still empty has nowhere to
 * be saved yet. It is kept here, per conversation id, until the id's first turn lands and the
 * pending value can be replayed as a real save.
 */
export function readPendingChatPreferences<T>(chatId: string): T | null {
  try {
    const stored = sessionStorage.getItem(`cb_pending_prefs_${chatId}`)
    return stored ? (JSON.parse(stored) as T) : null
  } catch {
    return null
  }
}

export function writePendingChatPreferences<T>(chatId: string, preferences: T): void {
  try {
    sessionStorage.setItem(`cb_pending_prefs_${chatId}`, JSON.stringify(preferences))
  } catch {
    /* Dropping the pending choice is a smaller loss than a thrown render. */
  }
}

export function clearPendingChatPreferences(chatId: string): void {
  try {
    sessionStorage.removeItem(`cb_pending_prefs_${chatId}`)
  } catch {
    /* Nothing to clean up if storage is unavailable. */
  }
}
