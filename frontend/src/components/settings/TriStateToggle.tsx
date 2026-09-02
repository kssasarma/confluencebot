import { cn } from '../../lib/cn'

interface TriStateToggleProps {
  label: string
  /** null means "inherit the account default". */
  value: boolean | null
  onChange: (value: boolean | null) => void
}

const OPTIONS: Array<{ value: boolean | null; label: string }> = [
  { value: null, label: 'Default' },
  { value: true, label: 'On' },
  { value: false, label: 'Off' },
]

/**
 * Default / On / Off, as a radio group.
 *
 * A real `radiogroup` rather than three buttons: arrow keys move between the options and the
 * selected one is announced as such, which three `aria-pressed` buttons do not achieve.
 */
export default function TriStateToggle({ label, value, onChange }: TriStateToggleProps) {
  return (
    <div className="flex items-center justify-between gap-4">
      <span id={`${label}-label`} className="text-sm text-foreground">{label}</span>

      <div role="radiogroup" aria-labelledby={`${label}-label`} className="flex gap-1">
        {OPTIONS.map(option => {
          const selected = value === option.value
          return (
            <button
              key={String(option.value)}
              role="radio"
              aria-checked={selected}
              // Only the selected option is in the tab order; arrow keys move within the group.
              tabIndex={selected ? 0 : -1}
              onClick={() => onChange(option.value)}
              onKeyDown={event => {
                if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return
                event.preventDefault()
                const index = OPTIONS.findIndex(candidate => candidate.value === value)
                const delta = event.key === 'ArrowRight' ? 1 : -1
                const next = (index + delta + OPTIONS.length) % OPTIONS.length
                onChange(OPTIONS[next].value)
              }}
              className={cn(
                'rounded-md border px-2.5 py-1 text-2xs transition-colors',
                selected
                  ? 'border-primary bg-primary-soft font-medium text-primary-emphasis'
                  : 'border-border text-muted-foreground hover:bg-surface-hover',
              )}
            >
              {option.label}
            </button>
          )
        })}
      </div>
    </div>
  )
}
