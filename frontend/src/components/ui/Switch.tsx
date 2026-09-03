import { useId } from 'react'
import { cn } from '../../lib/cn'

interface SwitchProps {
  label: string
  description?: string
  checked: boolean
  onChange: (checked: boolean) => void
  disabled?: boolean
}

/**
 * A labelled on/off control.
 *
 * `role="switch"` with `aria-checked` rather than a styled checkbox: the two are announced
 * differently, and "switch, on" is what a settings toggle should say. The description is wired
 * through `aria-describedby` so it is read as part of the control rather than as stray text
 * floating near it.
 */
export default function Switch({ label, description, checked, onChange, disabled }: SwitchProps) {
  const id = useId()
  const descriptionId = description ? `${id}-description` : undefined

  return (
    <div className="flex items-center justify-between gap-4">
      <div className="min-w-0">
        <label htmlFor={id} className="text-sm font-medium text-foreground">{label}</label>
        {description && (
          <p id={descriptionId} className="text-2xs text-muted-foreground">{description}</p>
        )}
      </div>

      <button
        id={id}
        type="button"
        role="switch"
        aria-checked={checked}
        aria-describedby={descriptionId}
        disabled={disabled}
        onClick={() => onChange(!checked)}
        className={cn(
          'relative inline-flex h-5 w-9 shrink-0 rounded-full transition-colors duration-fast',
          'ease-out-expo disabled:cursor-not-allowed disabled:opacity-50',
          checked ? 'bg-primary' : 'bg-muted ring-1 ring-inset ring-border',
        )}
      >
        <span
          aria-hidden="true"
          className={cn(
            'pointer-events-none absolute top-[3px] h-3.5 w-3.5 rounded-full bg-surface shadow-soft',
            'transition-transform duration-fast ease-out-expo',
            checked ? 'translate-x-[18px]' : 'translate-x-[3px]',
          )}
        />
      </button>
    </div>
  )
}
