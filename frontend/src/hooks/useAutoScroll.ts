import { useCallback, useEffect, useRef, useState } from 'react'

/** How close to the bottom still counts as "following along". */
const AT_BOTTOM_THRESHOLD_PX = 96

export interface AutoScroll<T extends HTMLElement> {
  containerRef: React.RefObject<T>
  /** False once the reader has scrolled up; that is when the pill appears. */
  isPinnedToBottom: boolean
  scrollToBottom: (behavior?: ScrollBehavior) => void
}

/**
 * Keeps a transcript pinned to the newest content — but only while the reader wants it there.
 *
 * The behaviour this replaces called `scrollIntoView({ behavior: 'smooth' })` on every change to
 * the message array, which during a stream means once per token. Scrolling up to re-read an
 * earlier answer then yanked the reader back to the bottom, smoothly, several times a second.
 *
 * So the scroll position is treated as the reader's, not the app's: it follows while they are
 * already at the bottom, stops the moment they scroll away, and offers a way back instead of
 * taking one.
 *
 * @param dependency changes whenever new content arrives — the streamed text, typically
 */
export function useAutoScroll<T extends HTMLElement>(dependency: unknown): AutoScroll<T> {
  // `null!` rather than a nullable ref: the element is attached before any effect
  // reads it, and the alternative is a cast at every call site.
  const containerRef = useRef<T>(null!)
  const [isPinnedToBottom, setPinned] = useState(true)

  const measure = useCallback(() => {
    const element = containerRef.current
    if (!element) return
    const distance = element.scrollHeight - element.scrollTop - element.clientHeight
    setPinned(distance <= AT_BOTTOM_THRESHOLD_PX)
  }, [])

  useEffect(() => {
    const element = containerRef.current
    if (!element) return

    element.addEventListener('scroll', measure, { passive: true })
    measure()
    return () => element.removeEventListener('scroll', measure)
  }, [measure])

  const scrollToBottom = useCallback((behavior: ScrollBehavior = 'smooth') => {
    const element = containerRef.current
    if (!element) return
    element.scrollTo({ top: element.scrollHeight, behavior })
    setPinned(true)
  }, [])

  useEffect(() => {
    if (!isPinnedToBottom) return
    const element = containerRef.current
    if (!element) return

    // 'auto' while following a stream: a smooth scroll queued once per frame never finishes one
    // animation before the next begins, and the result is a transcript that crawls.
    element.scrollTop = element.scrollHeight
  }, [dependency, isPinnedToBottom])

  return { containerRef, isPinnedToBottom, scrollToBottom }
}
