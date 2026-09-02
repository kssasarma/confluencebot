import { forwardRef, useId, type InputHTMLAttributes } from 'react'
import { cn } from '../../lib/cn'

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: string
  /** Shown under the field and, when set, announced as the field's error. */
  error?: string
  hint?: string
}

/**
 * A labelled text field.
 *
 * The id is generated rather than derived from the label text, which is what the previous version
 * did: two fields labelled "Name" on one page produced two elements with the same id, and every
 * click on the second label focused the first.
 */
const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { label, error, hint, className, id, ...props }, ref,
) {
  const generatedId = useId()
  const inputId = id ?? generatedId
  const messageId = `${inputId}-message`

  return (
    <div className="flex flex-col gap-1.5">
      {label && (
        <label htmlFor={inputId} className="text-sm font-medium text-foreground">
          {label}
        </label>
      )}
      <input
        ref={ref}
        id={inputId}
        aria-invalid={error ? true : undefined}
        aria-describedby={error || hint ? messageId : undefined}
        className={cn(
          'h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-foreground',
          'placeholder:text-muted-foreground transition-colors duration-fast',
          error && 'border-danger',
          className,
        )}
        {...props}
      />
      {(error || hint) && (
        <p
          id={messageId}
          className={cn('text-2xs', error ? 'text-danger-emphasis' : 'text-muted-foreground')}
        >
          {error ?? hint}
        </p>
      )}
    </div>
  )
})

export default Input
