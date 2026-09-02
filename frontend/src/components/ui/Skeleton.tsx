import { cn } from '../../lib/cn'

/**
 * A placeholder with the shape of the thing that is loading.
 *
 * A spinner tells the reader that something is happening; a skeleton tells them what is about to
 * appear and reserves its space, so arriving content does not shove the page around. Marked
 * `aria-hidden` — the loading state is announced once by the region that owns it, not once per
 * grey rectangle.
 */
export default function Skeleton({ className }: { className?: string }) {
  return (
    <div
      aria-hidden="true"
      className={cn('relative overflow-hidden rounded-md bg-muted', className)}
    >
      <div
        className="absolute inset-0 -translate-x-full animate-shimmer bg-gradient-to-r from-transparent via-surface/60 to-transparent"
      />
    </div>
  )
}

export function SkeletonText({ lines = 3, className }: { lines?: number; className?: string }) {
  return (
    <div className={cn('space-y-2', className)}>
      {Array.from({ length: lines }, (_, index) => (
        <Skeleton
          key={index}
          // The last line runs short, the way a paragraph does. Uniform bars read as a loading
          // bar rather than as text.
          className={cn('h-3.5', index === lines - 1 ? 'w-2/3' : 'w-full')}
        />
      ))}
    </div>
  )
}

export function SkeletonRow() {
  return (
    <div className="flex items-center gap-2 px-2 py-2">
      <Skeleton className="h-4 w-4 shrink-0 rounded" />
      <div className="min-w-0 flex-1 space-y-1.5">
        <Skeleton className="h-3.5 w-3/4" />
        <Skeleton className="h-2.5 w-1/3" />
      </div>
    </div>
  )
}
