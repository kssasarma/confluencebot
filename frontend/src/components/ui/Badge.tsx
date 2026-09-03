import type { ReactNode } from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '../../lib/cn'

/**
 * A small status label.
 *
 * Each tone pairs a `-soft` background with the matching `-emphasis` text, which is the pairing
 * the contrast test guarantees. That is the entire reason the palette carries both: a badge that
 * picked its own text colour on a tinted background is where contrast drift starts.
 */
const badge = cva(
  'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-2xs font-medium whitespace-nowrap',
  {
    variants: {
      tone: {
        neutral: 'bg-muted text-muted-foreground',
        primary: 'bg-primary-soft text-primary-emphasis',
        accent: 'bg-accent-soft text-accent-emphasis',
        success: 'bg-success-soft text-success-emphasis',
        warning: 'bg-warning-soft text-warning-emphasis',
        danger: 'bg-danger-soft text-danger-emphasis',
        info: 'bg-info-soft text-info-emphasis',
      },
    },
    defaultVariants: { tone: 'neutral' },
  },
)

interface BadgeProps extends VariantProps<typeof badge> {
  children: ReactNode
  icon?: ReactNode
  className?: string
}

export default function Badge({ tone, icon, children, className }: BadgeProps) {
  return (
    <span className={cn(badge({ tone }), className)}>
      {icon && <span aria-hidden="true" className="flex shrink-0">{icon}</span>}
      {children}
    </span>
  )
}
