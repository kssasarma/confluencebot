import { useRef } from 'react'
import { Monitor, Moon, Sun } from 'lucide-react'
import type { Theme } from '../../context/ThemeContext'
import { cn } from '../../lib/cn'

const OPTIONS: Array<{ value: Theme; label: string; icon: typeof Sun }> = [
  { value: 'dark', label: 'Dark', icon: Moon },
  { value: 'system', label: 'System', icon: Monitor },
  { value: 'light', label: 'Light', icon: Sun },
]

/**
 * Dark / Match system / Light, as one three-position slider.
 *
 * `role="radiogroup"` over its three `role="radio"` buttons rather than a native `<select>` or a
 * row of plain buttons: exactly one of the three is ever true, which is what a radio group means,
 * and it comes with roving `Tab` behaviour and arrow-key movement between options for free once
 * `aria-checked` and `tabIndex` are wired correctly below.
 *
 * The thumb is one absolutely-positioned element that slides under the icons rather than three
 * independently-styled buttons, so switching reads as one choice moving rather than three buttons
 * each flipping their own state.
 */
export default function ThemeSwitch({
  value, onChange,
}: {
  value: Theme
  onChange: (theme: Theme) => void
}) {
  const groupRef = useRef<HTMLDivElement>(null)
  const activeIndex = OPTIONS.findIndex(option => option.value === value)

  function focusOption(index: number) {
    const buttons = groupRef.current?.querySelectorAll<HTMLButtonElement>('[role="radio"]')
    buttons?.[index]?.focus()
  }

  function handleKeyDown(event: React.KeyboardEvent) {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
    event.preventDefault()
    const delta = event.key === 'ArrowRight' ? 1 : -1
    const next = (activeIndex + delta + OPTIONS.length) % OPTIONS.length
    onChange(OPTIONS[next].value)
    focusOption(next)
  }

  return (
    <div
      ref={groupRef}
      role="radiogroup"
      aria-label="Theme"
      onKeyDown={handleKeyDown}
      className="relative inline-flex items-center rounded-full border border-border bg-surface p-1"
    >
      <span
        aria-hidden="true"
        className="absolute inset-y-1 w-9 rounded-full bg-primary shadow-soft transition-transform duration-base ease-out-expo"
        style={{ transform: `translateX(${activeIndex * 2.25}rem)` }}
      />
      {OPTIONS.map(({ value: option, label, icon: Icon }) => {
        const checked = option === value
        return (
          <button
            key={option}
            type="button"
            role="radio"
            aria-checked={checked}
            aria-label={label === 'System' ? 'Match system theme' : `${label} theme`}
            tabIndex={checked ? 0 : -1}
            onClick={() => onChange(option)}
            className={cn(
              'relative z-10 flex h-7 w-9 items-center justify-center rounded-full transition-colors',
              'duration-base ease-out-expo',
              checked ? 'text-primary-foreground' : 'text-muted-foreground hover:text-foreground',
            )}
          >
            <Icon size={14} aria-hidden="true" />
          </button>
        )
      })}
    </div>
  )
}
