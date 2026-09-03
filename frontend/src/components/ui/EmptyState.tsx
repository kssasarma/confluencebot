import type { ReactNode } from 'react'
import { cn } from '../../lib/cn'

/**
 * The state a list is in when it has nothing to show.
 *
 * Distinct from an error on purpose: "no conversations yet" and "we could not reach the server"
 * look identical when both are rendered as an empty list, and the difference is the whole
 * question the reader has. `tone="error"` says which one this is and offers the retry.
 */
interface EmptyStateProps {
  icon?: ReactNode
  title: string
  description?: string
  action?: ReactNode
  tone?: 'neutral' | 'error'
  className?: string
}

export default function EmptyState({
  icon, title, description, action, tone = 'neutral', className,
}: EmptyStateProps) {
  return (
    <div
      role={tone === 'error' ? 'alert' : undefined}
      className={cn('flex flex-col items-center px-6 py-10 text-center', className)}
    >
      {icon && (
        <div
          aria-hidden="true"
          className={cn(
            'mb-3 flex h-10 w-10 items-center justify-center rounded-xl',
            tone === 'error' ? 'bg-danger-soft text-danger-emphasis' : 'bg-muted text-muted-foreground',
          )}
        >
          {icon}
        </div>
      )}
      <p className="text-sm font-medium text-foreground">{title}</p>
      {description && (
        <p className="mt-1 max-w-xs text-sm text-muted-foreground">{description}</p>
      )}
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}
