import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '../../lib/cn'

const iconButton = cva(
  'inline-flex items-center justify-center rounded-md transition-colors duration-fast ' +
  'ease-out-expo disabled:opacity-40 disabled:cursor-not-allowed disabled:pointer-events-none',
  {
    variants: {
      variant: {
        ghost: 'text-muted-foreground hover:bg-surface-hover hover:text-foreground',
        subtle: 'bg-muted text-foreground hover:bg-surface-hover',
        primary: 'bg-primary text-primary-foreground hover:bg-primary-hover',
        danger: 'text-danger-emphasis hover:bg-danger-soft',
      },
      size: {
        sm: 'h-7 w-7',
        md: 'h-8 w-8',
        lg: 'h-10 w-10',
      },
      active: {
        true: 'bg-muted text-foreground',
      },
    },
    defaultVariants: { variant: 'ghost', size: 'md' },
  },
)

export interface IconButtonProps
  extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children' | 'aria-label'>,
          VariantProps<typeof iconButton> {
  /**
   * The accessible name. Required, and required at the type level rather than by convention.
   *
   * An icon-only button with no name is a button a screen reader announces as "button" — the
   * single most common accessibility defect in an app like this one, and one that no amount of
   * review reliably catches. Making it a compile error is the only mechanism that scales.
   */
  label: string

  /** The icon. Marked `aria-hidden` for you — the name comes from `label`. */
  icon: ReactNode

  /** Set false when the label is already visible next to the button, to avoid a doubled tooltip. */
  showTooltip?: boolean
}

const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { label, icon, showTooltip = true, variant, size, active, className, type = 'button', ...props },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      aria-label={label}
      title={showTooltip ? label : undefined}
      className={cn(iconButton({ variant, size, active }), className)}
      {...props}
    >
      <span aria-hidden="true" className="flex items-center justify-center">{icon}</span>
    </button>
  )
})

export default IconButton
