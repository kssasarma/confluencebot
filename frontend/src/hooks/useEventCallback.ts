import { useCallback, useInsertionEffect, useRef } from 'react'

/**
 * A callback whose identity never changes but which always calls the latest closure.
 *
 * Used where a memoised child must not re-render because its parent recreated a handler. The
 * transcript is the case that matters: `MessageBubble` is memoised so that a streamed token
 * re-renders one message rather than all of them, and that memoisation is defeated entirely by an
 * inline `onClick={() => ...}` in the parent — the props compare unequal every frame and every
 * message re-renders anyway.
 *
 * `useInsertionEffect` rather than `useEffect` to publish the latest closure: it runs before any
 * layout effect, so a handler invoked from one cannot observe a stale version.
 *
 * Not for values read during render — only for event handlers.
 */
export function useEventCallback<Args extends unknown[], Result>(
  callback: (...args: Args) => Result,
): (...args: Args) => Result {
  const latest = useRef(callback)

  useInsertionEffect(() => {
    latest.current = callback
  }, [callback])

  return useCallback((...args: Args) => latest.current(...args), [])
}
