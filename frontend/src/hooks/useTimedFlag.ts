import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * A boolean that turns itself off after `durationMs` — the "Saved" checkmark pattern. Cleans up
 * its timer on unmount and on every re-trigger, so a save right before navigating away, or two
 * saves in quick succession, can't set state on an unmounted component or clear a newer flag early.
 */
export function useTimedFlag(durationMs = 2000): [boolean, () => void] {
  const [active, setActive] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const trigger = useCallback(() => {
    if (timerRef.current) clearTimeout(timerRef.current)
    setActive(true)
    timerRef.current = setTimeout(() => setActive(false), durationMs)
  }, [durationMs])

  useEffect(() => () => { if (timerRef.current) clearTimeout(timerRef.current) }, [])

  return [active, trigger]
}
