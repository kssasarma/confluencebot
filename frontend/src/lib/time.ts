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
