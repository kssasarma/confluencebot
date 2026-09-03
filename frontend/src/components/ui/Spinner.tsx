import { Loader2 } from 'lucide-react'
import { cn } from '../../lib/cn'

interface SpinnerProps {
  size?: 'sm' | 'md' | 'lg'
  className?: string
  /**
   * Announced to assistive technology. Omit when a nearby live region already says what is
   * loading — two announcements for one wait is worse than none.
   */
  label?: string
}

const SIZES = { sm: 'h-4 w-4', md: 'h-6 w-6', lg: 'h-9 w-9' } as const

export default function Spinner({ size = 'md', className, label }: SpinnerProps) {
  return (
    <>
      <Loader2
        aria-hidden="true"
        className={cn('animate-spin text-primary-emphasis', SIZES[size], className)}
      />
      {label && <span className="sr-only">{label}</span>}
    </>
  )
}
