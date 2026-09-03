import { useId, useRef, useState, type ReactNode } from 'react'
import { cn } from '../../lib/cn'

/**
 * A hover and focus hint.
 *
 * Deliberately not a `title` attribute: those never appear for keyboard users, never appear on
 * touch, and are announced inconsistently. This opens on focus as well as hover and is wired
 * through `aria-describedby`, so it is supplementary rather than the only way to read something.
 *
 * Anything a user cannot complete the task without belongs in visible text, not in here.
 */

interface TooltipProps {
  content: ReactNode
  children: ReactNode
  placement?: 'top' | 'bottom'
  className?: string
}

const OPEN_DELAY_MS = 250

export default function Tooltip({ content, children, placement = 'top', className }: TooltipProps) {
  const [open, setOpen] = useState(false)
  const id = useId()
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  // A delay on hover so that sweeping the pointer across a toolbar does not flash six tooltips;
  // no delay on focus, where the intent is unambiguous.
  const openLater = () => {
    timer.current = setTimeout(() => setOpen(true), OPEN_DELAY_MS)
  }

  const close = () => {
    if (timer.current) clearTimeout(timer.current)
    timer.current = null
    setOpen(false)
  }

  return (
    <span
      className={cn('relative inline-flex', className)}
      onPointerEnter={openLater}
      onPointerLeave={close}
      onFocusCapture={() => setOpen(true)}
      onBlurCapture={close}
      // Escape closes a tooltip without moving focus — WCAG 1.4.13.
      onKeyDown={event => { if (event.key === 'Escape') close() }}
    >
      <span aria-describedby={open ? id : undefined} className="inline-flex">{children}</span>

      {open && (
        <span
          id={id}
          role="tooltip"
          className={cn(
            'pointer-events-none absolute left-1/2 z-popover w-max max-w-xs -translate-x-1/2',
            'animate-fade-in rounded-lg border border-border bg-surface px-2.5 py-1.5',
            'text-2xs leading-relaxed text-foreground shadow-raised',
            placement === 'top' ? 'bottom-full mb-2' : 'top-full mt-2',
          )}
        >
          {content}
        </span>
      )}
    </span>
  )
}
