import { useCallback, useEffect, useRef, useState } from 'react'
import { usePersistentState } from './usePersistentState'

export interface ResizableOptions {
  storageKey: string
  defaultWidth: number
  minWidth: number
  maxWidth: number
  /** Pixels moved per arrow-key press. */
  step?: number
}

export interface Resizable {
  width: number
  isDragging: boolean
  /** Spread onto the drag handle. Includes the ARIA slider semantics keyboard users need. */
  handleProps: {
    role: 'separator'
    tabIndex: 0
    'aria-orientation': 'vertical'
    'aria-label': string
    'aria-valuenow': number
    'aria-valuemin': number
    'aria-valuemax': number
    onPointerDown: (event: React.PointerEvent<HTMLElement>) => void
    onKeyDown: (event: React.KeyboardEvent<HTMLElement>) => void
    onDoubleClick: () => void
  }
  reset: () => void
}

const DEFAULT_STEP = 16

/**
 * A draggable, keyboard-operable panel width, remembered across sessions.
 *
 * Two details are what make this usable rather than merely present:
 *
 * **Pointer capture.** Without `setPointerCapture`, dragging fast enough to leave the 4px handle
 * drops the pointer events on whatever is underneath, and the panel sticks mid-drag. Capture
 * routes every subsequent move to the handle regardless of what the cursor is over.
 *
 * **Keyboard resizing.** This is the part that gets skipped. A drag handle that only responds to
 * a pointer is a control a keyboard user cannot operate at all, so the handle is a real
 * `separator` with arrow keys, Home and End, and an `aria-valuenow` that is announced as it
 * moves — WCAG 2.1.1.
 */
export function useResizable({
  storageKey, defaultWidth, minWidth, maxWidth, step = DEFAULT_STEP,
}: ResizableOptions): Resizable {
  const isValidWidth = useCallback(
    (value: unknown): value is number =>
      typeof value === 'number' && Number.isFinite(value) && value >= minWidth && value <= maxWidth,
    [minWidth, maxWidth],
  )

  const [width, setWidth] = usePersistentState(storageKey, defaultWidth, isValidWidth)
  const [isDragging, setIsDragging] = useState(false)
  const dragState = useRef<{ startX: number; startWidth: number } | null>(null)

  const clamp = useCallback(
    (value: number) => Math.min(Math.max(Math.round(value), minWidth), maxWidth),
    [minWidth, maxWidth],
  )

  const onPointerDown = useCallback((event: React.PointerEvent<HTMLElement>) => {
    // Only the primary button drags; a right-click should open a context menu, not resize.
    if (event.button !== 0) return

    event.preventDefault()
    event.currentTarget.setPointerCapture?.(event.pointerId)
    dragState.current = { startX: event.clientX, startWidth: width }
    setIsDragging(true)
  }, [width])

  useEffect(() => {
    if (!isDragging) return

    const onMove = (event: PointerEvent) => {
      const state = dragState.current
      if (!state) return
      setWidth(clamp(state.startWidth + (event.clientX - state.startX)))
    }

    const onUp = () => {
      dragState.current = null
      setIsDragging(false)
    }

    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    window.addEventListener('pointercancel', onUp)

    // While dragging, the pointer sweeps across text and the browser starts selecting it. Both
    // rules are removed on cleanup, including if the component unmounts mid-drag.
    const previousUserSelect = document.body.style.userSelect
    const previousCursor = document.body.style.cursor
    document.body.style.userSelect = 'none'
    document.body.style.cursor = 'col-resize'

    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      window.removeEventListener('pointercancel', onUp)
      document.body.style.userSelect = previousUserSelect
      document.body.style.cursor = previousCursor
    }
  }, [clamp, isDragging, setWidth])

  const reset = useCallback(() => setWidth(defaultWidth), [defaultWidth, setWidth])

  const onKeyDown = useCallback((event: React.KeyboardEvent<HTMLElement>) => {
    const moves: Record<string, number> = {
      ArrowLeft: -step,
      ArrowRight: step,
      // A larger jump for a long drag, matching the convention of every other slider.
      PageDown: -step * 4,
      PageUp: step * 4,
    }

    if (event.key in moves) {
      event.preventDefault()
      setWidth(current => clamp(current + moves[event.key]))
      return
    }
    if (event.key === 'Home') {
      event.preventDefault()
      setWidth(minWidth)
      return
    }
    if (event.key === 'End') {
      event.preventDefault()
      setWidth(maxWidth)
      return
    }
    if (event.key === 'Enter') {
      event.preventDefault()
      reset()
    }
  }, [clamp, maxWidth, minWidth, reset, setWidth, step])

  return {
    width,
    isDragging,
    reset,
    handleProps: {
      role: 'separator',
      tabIndex: 0,
      'aria-orientation': 'vertical',
      'aria-label': 'Resize sidebar',
      'aria-valuenow': width,
      'aria-valuemin': minWidth,
      'aria-valuemax': maxWidth,
      onPointerDown,
      onKeyDown,
      onDoubleClick: reset,
    },
  }
}
