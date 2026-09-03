/** Short, human-readable age of a timestamp — "just now", "3h ago", "12 Mar". */
export function relativeTime(isoTimestamp?: string | null): string {
  if (!isoTimestamp) return ''

  const then = new Date(isoTimestamp).getTime()
  if (Number.isNaN(then)) return ''

  const minutes = Math.floor((Date.now() - then) / 60_000)
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${minutes}m ago`

  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`

  const days = Math.floor(hours / 24)
  if (days < 7) return `${days}d ago`

  return new Date(then).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}

/** A full timestamp for a tooltip or a `title`, in the reader's own locale and time zone. */
export function absoluteTime(isoTimestamp?: string | null): string {
  if (!isoTimestamp) return ''
  const date = new Date(isoTimestamp)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}

export type RecencyBucket =
  | 'Today' | 'Yesterday' | 'Previous 7 days' | 'Previous 30 days' | 'Older'

const BUCKET_ORDER: RecencyBucket[] =
  ['Today', 'Yesterday', 'Previous 7 days', 'Previous 30 days', 'Older']

/**
 * Which heading a timestamp belongs under in the conversation list.
 *
 * Calendar days, not elapsed hours: something from 11pm last night is "Yesterday" at 1am, not
 * "2h ago" grouped under Today. Readers reason about their history in days.
 */
export function recencyBucket(isoTimestamp: string | undefined, now = new Date()): RecencyBucket {
  if (!isoTimestamp) return 'Older'

  const then = new Date(isoTimestamp)
  if (Number.isNaN(then.getTime())) return 'Older'

  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const dayMs = 86_400_000
  const daysAgo = Math.floor((startOfToday - startOfDay(then)) / dayMs)

  // A timestamp slightly in the future — a clock skew between browser and server — is today's.
  if (daysAgo <= 0) return 'Today'
  if (daysAgo === 1) return 'Yesterday'
  if (daysAgo <= 7) return 'Previous 7 days'
  if (daysAgo <= 30) return 'Previous 30 days'
  return 'Older'
}

function startOfDay(date: Date): number {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

/**
 * Splits an already-sorted list into recency groups, dropping the empty ones.
 *
 * Order is preserved rather than re-sorted: the server decides the ordering (pinned first, then
 * most recently used) and re-sorting here would fight its pagination.
 */
export function groupByRecency<T>(
  items: T[],
  timestampOf: (item: T) => string | undefined,
  now = new Date(),
): Array<{ label: RecencyBucket; items: T[] }> {
  const buckets = new Map<RecencyBucket, T[]>()

  for (const item of items) {
    const label = recencyBucket(timestampOf(item), now)
    const bucket = buckets.get(label)
    if (bucket) bucket.push(item)
    else buckets.set(label, [item])
  }

  return BUCKET_ORDER
    .filter(label => buckets.has(label))
    .map(label => ({ label, items: buckets.get(label)! }))
}
