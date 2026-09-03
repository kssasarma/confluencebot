import { useEffect } from 'react'

export interface Hotkey {
  /** Lower-case `event.key`, e.g. `k`, `escape`, `arrowup`. */
  key: string
  /** Command on macOS, Control elsewhere. */
  meta?: boolean
  shift?: boolean
  handler: (event: KeyboardEvent) => void
  /**
   * Fire even when the reader is typing. Off by default — a bare `/` shortcut that steals the
   * slash out of a URL someone is typing into the composer is a genuinely infuriating bug.
   */
  allowInInput?: boolean
  enabled?: boolean
}

/** True when the event came from somewhere the reader is composing text. */
export function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  return ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)
}

/**
 * Binds keyboard shortcuts at the document level.
 *
 * `metaKey || ctrlKey` rather than a platform check: the two conventions are Command on macOS and
 * Control everywhere else, and accepting both is simpler and more forgiving than sniffing the
 * user agent — which is also unreliable on iPads with keyboards.
 */
export function useHotkeys(hotkeys: Hotkey[]): void {
  useEffect(() => {
    const active = hotkeys.filter(hotkey => hotkey.enabled !== false)
    if (active.length === 0) return

    const onKeyDown = (event: KeyboardEvent) => {
      const key = event.key.toLowerCase()
      const typing = isTypingTarget(event.target)

      for (const hotkey of active) {
        if (key !== hotkey.key) continue
        if (!!hotkey.meta !== (event.metaKey || event.ctrlKey)) continue
        if (!!hotkey.shift !== event.shiftKey) continue
        if (typing && !hotkey.allowInInput) continue

        hotkey.handler(event)
        return
      }
    }

    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [hotkeys])
}
