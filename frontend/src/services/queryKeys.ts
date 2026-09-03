/**
 * Every react-query key in one place.
 *
 * Keys are the contract between a fetch and the invalidation that follows it. Spelling them
 * inline is how a mutation ends up invalidating `['sessions']` while the query registered
 * `['sessions', '']` — nothing refetches, nothing errors, and the list is simply stale.
 */
export const queryKeys = {
  sessions: (search: string) => ['sessions', search] as const,
  sessionsRoot: ['sessions'] as const,
  transcript: (chatId: string) => ['transcript', chatId] as const,
  userPreferences: ['preferences', 'user'] as const,
  chatPreferences: (chatId: string) => ['preferences', 'chat', chatId] as const,
  adminUsers: ['admin', 'users'] as const,
  ingestionJobs: ['admin', 'jobs'] as const,
} as const
