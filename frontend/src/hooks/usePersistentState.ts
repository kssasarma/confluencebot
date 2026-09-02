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
