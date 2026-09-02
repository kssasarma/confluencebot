import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { Loader2 } from 'lucide-react'
import { cn } from '../../lib/cn'

/**
 * Variants live in `cva` rather than in a chain of template literals so that the variant names
 * are part of the component's type: a typo in `variant="danger"` is a compile error, and adding a
 * size means adding it in exactly one place.
 */
const button = cva(
  'inline-flex items-center justify-center gap-2 rounded-lg font-medium ' +
  'transition-colors duration-fast ease-out-expo ' +
  'disabled:opacity-50 disabled:cursor-not-allowed disabled:pointer-events-none',
  {
    variants: {
      variant: {
        primary: 'bg-primary text-primary-foreground hover:bg-primary-hover',
        secondary: 'bg-surface border border-border text-foreground hover:bg-surface-hover',
        ghost: 'text-foreground hover:bg-surface-hover',
        danger: 'bg-danger text-danger-foreground hover:bg-danger-hover',
        subtle: 'bg-muted text-foreground hover:bg-surface-hover',
      },
      size: {
        sm: 'h-8 px-3 text-sm',
        md: 'h-10 px-4 text-sm',
        lg: 'h-11 px-6 text-base',
      },
      block: {
        true: 'w-full',
      },
    },
    defaultVariants: { variant: 'primary', size: 'md' },
  },
)

export interface ButtonProps
  extends ButtonHTMLAttributes<HTMLButtonElement>, VariantProps<typeof button> {
  /** Shows a spinner and blocks further presses. The label stays, so the button keeps its width. */
  loading?: boolean
  children?: ReactNode
}

const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant, size, block, loading, className, children, disabled, type = 'button', ...props },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={cn(button({ variant, size, block }), className)}
      {...props}
    >
      {loading && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
      {children}
    </button>
  )
})

export default Button
