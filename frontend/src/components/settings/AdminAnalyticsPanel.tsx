import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronDown, ChevronRight, RefreshCw } from 'lucide-react'
import { getAnalytics } from '../../services/adminService'
import { queryKeys } from '../../services/queryKeys'
import { toMessage } from '../../lib/errors'
import Button from '../ui/Button'
import EmptyState from '../ui/EmptyState'
import { SkeletonText } from '../ui/Skeleton'

/** One number with a label underneath — the building block of both stat grids below. */
function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-lg border border-border p-3">
      <p className="text-xl font-semibold text-foreground">{value.toLocaleString()}</p>
      <p className="text-2xs text-muted-foreground">{label}</p>
    </div>
  )
}

/**
 * Onboarding and usage analytics — visible only to a full admin, unlike the rest of this dialog's
 * "Admin"/User Management section which a read-only admin can also see. Collapsed by default and
 * unfetched until opened, the same as ingestion's job history: nobody opens Settings to read a
 * report, so this should not be the thing that makes the tab slow to open.
 *
 * Question counts only, never the questions themselves — this reports on usage, not on what
 * anyone asked.
 */
export default function AdminAnalyticsPanel() {
  const [open, setOpen] = useState(false)

  const analytics = useQuery({
    queryKey: queryKeys.adminAnalytics,
    queryFn: getAnalytics,
    enabled: open,
  })

  return (
    <div className="rounded-lg border border-border">
      <div className="flex items-center justify-between px-2 py-1">
        <button
          type="button"
          onClick={() => setOpen(current => !current)}
          aria-expanded={open}
          className="flex flex-1 items-center gap-2 rounded-lg px-2 py-2 text-left text-sm font-semibold text-foreground hover:bg-surface-hover"
        >
          {open ? <ChevronDown size={16} aria-hidden="true" /> : <ChevronRight size={16} aria-hidden="true" />}
          Analytics
        </button>
        {open && (
          <Button size="sm" variant="ghost" onClick={() => analytics.refetch()}>
            <RefreshCw size={13} aria-hidden="true" />
            Refresh
          </Button>
        )}
      </div>

      {open && (
        <div className="space-y-6 border-t border-border p-4">
          {analytics.isLoading ? (
            <SkeletonText lines={6} />
          ) : analytics.error ? (
            <EmptyState
              tone="error"
              title="Could not load analytics"
              description={toMessage(analytics.error, 'Please try again.')}
              action={<Button variant="secondary" onClick={() => analytics.refetch()}>Try again</Button>}
            />
          ) : analytics.data && (
            <>
              <section className="space-y-2">
                <h3 className="text-2xs font-semibold uppercase tracking-wider text-muted-foreground">
                  Onboarding
                </h3>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                  <Stat label="Total users" value={analytics.data.onboarding.totalUsers} />
                  <Stat label="Onboarded (30d)" value={analytics.data.onboarding.createdLast30Days} />
                  <Stat label="Temp passwords re-shared (30d)" value={analytics.data.onboarding.resentLast30Days} />
                  <Stat label="Deleted (30d)" value={analytics.data.onboarding.deletedLast30Days} />
                  <Stat
                    label="Onboarding emails that failed to send (30d)"
                    value={analytics.data.onboarding.emailDeliveryFailuresLast30Days}
                  />
                </div>
                {analytics.data.onboarding.usersByRole.length > 0 && (
                  <p className="text-2xs text-muted-foreground">
                    {analytics.data.onboarding.usersByRole
                      .map(({ role, count }) => `${count} ${role.toLowerCase()}`)
                      .join(' · ')}
                  </p>
                )}
              </section>

              <section className="space-y-2">
                <h3 className="text-2xs font-semibold uppercase tracking-wider text-muted-foreground">
                  Usage
                </h3>
                <Stat label="Questions asked" value={analytics.data.usage.totalQuestions} />

                {analytics.data.usage.topUsers.length > 0 && (
                  <div>
                    <p className="mb-1 text-2xs font-medium text-muted-foreground">Most active users</p>
                    <ul className="space-y-1 text-sm">
                      {analytics.data.usage.topUsers.map(user => (
                        <li key={user.email} className="flex items-center justify-between gap-2">
                          <span className="truncate text-foreground">{user.name ?? user.email}</span>
                          <span className="shrink-0 text-2xs text-muted-foreground">
                            {user.questionCount.toLocaleString()} question{user.questionCount === 1 ? '' : 's'}
                          </span>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}

                {analytics.data.usage.byBusinessUnit.length > 0 && (
                  <div>
                    <p className="mb-1 text-2xs font-medium text-muted-foreground">By business unit</p>
                    <ul className="space-y-1 text-sm">
                      {analytics.data.usage.byBusinessUnit.map(row => (
                        <li key={row.businessUnit} className="flex items-center justify-between gap-2">
                          <span className="truncate text-foreground">{row.businessUnit}</span>
                          <span className="shrink-0 text-2xs text-muted-foreground">
                            {row.questionCount.toLocaleString()} question{row.questionCount === 1 ? '' : 's'}
                          </span>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </section>
            </>
          )}
        </div>
      )}
    </div>
  )
}
