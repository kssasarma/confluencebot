import { useEffect, type RefObject } from 'react'

/**
 * Closes a floating panel on an outside click or Escape.
 *
 * Deliberately not built on `@headlessui/react`'s `Popover`: its anchor positioning relies on
 * floating-ui's `ResizeObserver`-driven `autoUpdate`, which never settles against this project's
 * stubbed `ResizeObserver` in tests (see `Menu.tsx` and `Sidebar.test.tsx`, which mocks `Menu` out
 * entirely for that reason). A plain `absolute`-positioned panel with this hook needs no such
 * observer and stays exercisable by ordinary `userEvent` clicks in tests.
 */
export function useDismiss(ref: RefObject<HTMLElement | null>, onDismiss: () => void, active: boolean) {
  useEffect(() => {
    if (!active) return

    function handlePointerDown(event: PointerEvent) {
      if (ref.current && !ref.current.contains(event.target as Node)) onDismiss()
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') onDismiss()
    }

    document.addEventListener('pointerdown', handlePointerDown)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [ref, onDismiss, active])
}
