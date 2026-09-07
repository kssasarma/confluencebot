import { useQuery } from '@tanstack/react-query'
import { RefreshCw } from 'lucide-react'
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
 * Onboarding and usage analytics — its own top-level Settings tab, visible only to a full admin
 * (unlike the "Admin"/User Management tab, which a read-only admin can also see). Selecting the
 * tab is itself the signal to fetch: nobody opens this tab to see a stale report.
 *
 * Question counts only, never the questions themselves — this reports on usage, not on what
 * anyone asked.
 */
export default function AdminAnalyticsPanel() {
  const analytics = useQuery({ queryKey: queryKeys.adminAnalytics, queryFn: getAnalytics })

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-end">
        <Button size="sm" variant="ghost" onClick={() => analytics.refetch()}>
          <RefreshCw size={13} aria-hidden="true" />
          Refresh
        </Button>
      </div>

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
  )
}
