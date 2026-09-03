import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Ban, Check, Play, RefreshCw, RotateCcw, UserPlus } from 'lucide-react'
import {
  createUser, ingestPage, ingestSpace, listJobs, listUsers, retriggerJob, setUserEnabled,
  setUserRole, type AdminRole, type AdminUser, type IngestionJob,
} from '../services/adminService'
import { useAuth } from '../context/AuthContext'
import { queryKeys } from '../services/queryKeys'
import { toMessage } from '../lib/errors'
import { useToast } from '../components/ui/Toast'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { absoluteTime } from '../lib/time'
import { cn } from '../lib/cn'
import Badge from '../components/ui/Badge'
import Button from '../components/ui/Button'
import EmptyState from '../components/ui/EmptyState'
import IconButton from '../components/ui/IconButton'
import Input from '../components/ui/Input'
import { SkeletonText } from '../components/ui/Skeleton'

type Tab = 'users' | 'ingestion'

const ROLE_LABELS: Record<AdminRole, string> = {
  ADMIN: 'Admin',
  ADMIN_READ_ONLY: 'Admin (read-only)',
  USER: 'User',
}

const ROLES = Object.keys(ROLE_LABELS) as AdminRole[]

/**
 * User management and ingestion control.
 *
 * A page, and lazily loaded: it is 400 lines that only administrators can act on, and shipping it
 * in the initial bundle taxes every other user for a screen they cannot open.
 */
export default function AdminRoute() {
  const [tab, setTab] = useState<Tab>('users')
  const { isAdmin } = useAuth()
  useDocumentTitle('Admin')

  // Ingestion is a full-admin power, so a read-only admin is not shown a tab whose every control
  // the API would refuse. Onboarding is the whole screen for them.
  const tabs: Tab[] = isAdmin ? ['users', 'ingestion'] : ['users']

  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto max-w-4xl px-4 py-8">
        <Link
          to="/"
          className="mb-6 inline-flex items-center gap-1.5 rounded text-2xs text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft size={13} aria-hidden="true" />
          Back to chat
        </Link>

        <h1 className="mb-6 text-xl font-semibold text-foreground">Admin</h1>

        <div role="tablist" aria-label="Admin sections" className="mb-6 flex gap-1">
          {tabs.map(name => (
            <button
              key={name}
              role="tab"
              aria-selected={tab === name}
              onClick={() => setTab(name)}
              className={cn(
                'rounded-lg px-4 py-2 text-sm font-medium capitalize transition-colors',
                tab === name
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:bg-surface-hover',
              )}
            >
              {name}
            </button>
          ))}
        </div>

        <div role="tabpanel">
          {tab === 'users' || !isAdmin ? <UsersTab /> : <IngestionTab />}
        </div>
      </div>
    </div>
  )
}

function UsersTab() {
  const queryClient = useQueryClient()
  const toast = useToast()
  const { isAdmin, user: signedIn } = useAuth()

  const [email, setEmail] = useState('')
  const [role, setRole] = useState<AdminRole>('USER')
  const [created, setCreated] = useState<{ email: string; tempPassword: string } | null>(null)

  const users = useQuery({ queryKey: queryKeys.adminUsers, queryFn: listUsers })

  const create = useMutation({
    mutationFn: () => createUser(email.trim(), role),
    onSuccess: result => {
      setCreated({ email: result.user.email, tempPassword: result.tempPassword })
      setEmail('')
      setRole('USER')
      void queryClient.invalidateQueries({ queryKey: queryKeys.adminUsers })
    },
    onError: error => toast.error('Could not create the user', toMessage(error, 'Please try again.')),
  })

  const applyUser = (updated: AdminUser) => {
    queryClient.setQueryData<AdminUser[]>(queryKeys.adminUsers, current =>
      current?.map(user => (user.id === updated.id ? updated : user)))
  }

  const toggle = useMutation({
    mutationFn: (user: AdminUser) => setUserEnabled(user.id, !user.enabled),
    onSuccess: applyUser,
    onError: error => toast.error('Could not update the user', toMessage(error, 'Please try again.')),
  })

  const changeRole = useMutation({
    mutationFn: ({ user, next }: { user: AdminUser; next: AdminRole }) => setUserRole(user.id, next),
    onSuccess: updated => {
      applyUser(updated)
      toast.success('Role updated', `${updated.email} is now ${ROLE_LABELS[updated.role]}.`)
    },
    onError: error => toast.error('Could not change the role', toMessage(error, 'Please try again.')),
  })

  return (
    <div className="space-y-6">
      {created && (
        <div role="status" className="rounded-lg border border-success/40 bg-success-soft p-4 text-sm">
          <p className="mb-1 font-medium text-success-emphasis">User created</p>
          <p className="text-muted-foreground">
            Email: <span className="font-mono text-foreground">{created.email}</span>
          </p>
          <p className="text-muted-foreground">
            Temporary password:{' '}
            <span className="font-mono text-foreground">{created.tempPassword}</span>
          </p>
          <p className="mt-1 text-2xs text-muted-foreground">
            Share this over a secure channel. It is shown once, and the user must change it at
            first sign-in.
          </p>
          <Button size="sm" variant="ghost" className="mt-2" onClick={() => setCreated(null)}>
            Dismiss
          </Button>
        </div>
      )}

      <form
        onSubmit={event => { event.preventDefault(); create.mutate() }}
        className="flex flex-wrap items-end gap-2"
      >
        <div className="min-w-[16rem] flex-1">
          <Input
            label="Email"
            type="email"
            required
            value={email}
            onChange={event => setEmail(event.target.value)}
            placeholder="user@example.com"
          />
        </div>

        <div className="flex flex-col gap-1.5">
          <label htmlFor="new-user-role" className="text-sm font-medium text-foreground">Role</label>
          <select
            id="new-user-role"
            value={role}
            onChange={event => setRole(event.target.value as AdminRole)}
            className="h-10 rounded-lg border border-border bg-surface px-3 text-sm text-foreground"
          >
            {ROLES.map(name => (
              <option key={name} value={name}>{ROLE_LABELS[name]}</option>
            ))}
          </select>
        </div>

        <Button type="submit" loading={create.isPending} disabled={!email.trim()}>
          <UserPlus size={15} aria-hidden="true" />
          Add user
        </Button>
      </form>

      {users.isLoading ? (
        <SkeletonText lines={5} />
      ) : users.error ? (
        <EmptyState
          tone="error"
          title="Could not load users"
          description={toMessage(users.error, 'Please try again.')}
          action={<Button variant="secondary" onClick={() => users.refetch()}>Try again</Button>}
        />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <caption className="sr-only">Users of this deployment</caption>
            <thead>
              <tr className="border-b border-border text-left">
                <th scope="col" className="pb-2 font-medium text-muted-foreground">Email</th>
                <th scope="col" className="pb-2 font-medium text-muted-foreground">Role</th>
                <th scope="col" className="pb-2 font-medium text-muted-foreground">Status</th>
                <th scope="col" className="pb-2 font-medium text-muted-foreground">Must change password</th>
                <th scope="col" className="pb-2"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {users.data?.map(user => (
                <tr key={user.id} className="text-foreground">
                  <td className="py-2.5 pr-4 font-mono text-2xs">{user.email}</td>
                  <td className="py-2.5 pr-4">
                    {isAdmin && signedIn?.email !== user.email ? (
                      <>
                        <label htmlFor={`role-${user.id}`} className="sr-only">
                          Role for {user.email}
                        </label>
                        <select
                          id={`role-${user.id}`}
                          value={user.role}
                          onChange={event =>
                            changeRole.mutate({ user, next: event.target.value as AdminRole })}
                          disabled={changeRole.isPending}
                          className="h-8 rounded-lg border border-border bg-surface px-2 text-2xs text-foreground disabled:opacity-50"
                        >
                          {ROLES.map(name => (
                            <option key={name} value={name}>{ROLE_LABELS[name]}</option>
                          ))}
                        </select>
                      </>
                    ) : (
                      // Your own row stays a label: the request that strips your own admin is the
                      // last one you are allowed to make, so the API refuses it and so does this.
                      <Badge tone={user.role === 'USER' ? 'info' : 'accent'}>
                        {ROLE_LABELS[user.role] ?? user.role}
                      </Badge>
                    )}
                  </td>
                  <td className="py-2.5 pr-4">
                    <Badge tone={user.enabled ? 'success' : 'danger'}>
                      {user.enabled ? 'Active' : 'Disabled'}
                    </Badge>
                  </td>
                  <td className="py-2.5 pr-4 text-2xs text-muted-foreground">
                    {user.mustChangePassword ? 'Yes' : 'No'}
                  </td>
                  <td className="py-2.5 text-right">
                    {isAdmin && signedIn?.email !== user.email && (
                      <IconButton
                        size="sm"
                        label={user.enabled ? `Disable ${user.email}` : `Enable ${user.email}`}
                        icon={user.enabled ? <Ban size={14} /> : <Check size={14} />}
                        onClick={() => toggle.mutate(user)}
                        disabled={toggle.isPending}
                      />
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

const JOB_TONE: Record<string, 'warning' | 'info' | 'success' | 'danger' | 'neutral'> = {
  PENDING: 'warning',
  RUNNING: 'info',
  COMPLETED: 'success',
  FAILED: 'danger',
}

function IngestionTab() {
  const queryClient = useQueryClient()
  const toast = useToast()

  const [spaceKey, setSpaceKey] = useState('')
  const [force, setForce] = useState(false)
  const [pageId, setPageId] = useState('')

  const jobs = useQuery({
    queryKey: queryKeys.ingestionJobs,
    queryFn: listJobs,
    // Ingestion runs in the background, so the list is polled while anything is still in flight
    // and left alone once everything has settled.
    refetchInterval: query => {
      const data = query.state.data as IngestionJob[] | undefined
      const active = data?.some(job => job.status === 'PENDING' || job.status === 'RUNNING')
      return active ? 4000 : false
    },
  })

  const refresh = () => queryClient.invalidateQueries({ queryKey: queryKeys.ingestionJobs })

  const startSpace = useMutation({
    mutationFn: () => ingestSpace(spaceKey.trim(), force),
    onSuccess: () => { setSpaceKey(''); setForce(false); void refresh() },
    onError: error => toast.error('Could not start ingestion', toMessage(error, 'Please try again.')),
  })

  const startPage = useMutation({
    mutationFn: () => ingestPage(pageId.trim()),
    onSuccess: () => { setPageId(''); void refresh() },
    onError: error => toast.error('Could not ingest the page', toMessage(error, 'Please try again.')),
  })

  const retrigger = useMutation({
    mutationFn: (job: IngestionJob) => retriggerJob(job.jobId),
    onSuccess: () => {
      toast.success('Job resubmitted', 'The failed run stays in the history below.')
      void refresh()
    },
    onError: error => toast.error('Could not retrigger the job', toMessage(error, 'Please try again.')),
  })

  return (
    <div className="space-y-6">
      <div className="grid gap-4 sm:grid-cols-2">
        <form
          onSubmit={event => { event.preventDefault(); startSpace.mutate() }}
          className="space-y-3 rounded-lg border border-border p-4"
        >
          <h2 className="text-sm font-semibold text-foreground">Ingest a space</h2>
          <Input
            label="Space key"
            value={spaceKey}
            onChange={event => setSpaceKey(event.target.value)}
            placeholder="ENG"
            hint="Leave blank to use the configured default space."
          />
          <label className="flex cursor-pointer items-center gap-2 text-sm text-muted-foreground">
            <input
              type="checkbox"
              checked={force}
              onChange={event => setForce(event.target.checked)}
              className="rounded accent-primary"
            />
            Re-ingest pages that have not changed
          </label>
          <Button type="submit" block loading={startSpace.isPending}>
            <Play size={14} aria-hidden="true" />
            Start ingestion
          </Button>
        </form>

        <form
          onSubmit={event => { event.preventDefault(); startPage.mutate() }}
          className="space-y-3 rounded-lg border border-border p-4"
        >
          <h2 className="text-sm font-semibold text-foreground">Ingest a single page</h2>
          <Input
            label="Page ID"
            required
            value={pageId}
            onChange={event => setPageId(event.target.value)}
            placeholder="131073"
            hint="The numeric id in the Confluence page URL."
          />
          <Button type="submit" block loading={startPage.isPending} disabled={!pageId.trim()}>
            <Play size={14} aria-hidden="true" />
            Ingest page
          </Button>
        </form>
      </div>

      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-foreground">Job history</h2>
        <Button size="sm" variant="ghost" onClick={refresh}>
          <RefreshCw size={13} aria-hidden="true" />
          Refresh
        </Button>
      </div>

      {jobs.isLoading ? (
        <SkeletonText lines={4} />
      ) : jobs.data?.length === 0 ? (
        <EmptyState title="No ingestion jobs yet" description="Start one above to index a space." />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <caption className="sr-only">Recent ingestion jobs</caption>
            <thead>
              <tr className="border-b border-border text-left">
                <th scope="col" className="pb-2 font-medium text-muted-foreground">Type</th>
                <th scope="col" className="pb-2 font-medium text-muted-foreground">Target</th>
                <th scope="col" className="pb-2 font-medium text-muted-foreground">Status</th>
                <th scope="col" className="pb-2 font-medium text-muted-foreground">Pages</th>
                <th scope="col" className="pb-2 font-medium text-muted-foreground">Chunks</th>
                <th scope="col" className="pb-2 font-medium text-muted-foreground">Started</th>
                <th scope="col" className="pb-2"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {jobs.data?.map(job => (
                <tr key={job.jobId}>
                  <td className="py-2 pr-3 text-2xs text-muted-foreground">{job.jobType}</td>
                  <td className="py-2 pr-3 font-mono text-2xs">{job.spaceKey ?? job.pageId ?? '—'}</td>
                  <td className="py-2 pr-3">
                    <Badge tone={JOB_TONE[job.status] ?? 'neutral'}>{job.status}</Badge>
                    {job.errorMessage && (
                      <p className="mt-1 max-w-xs text-2xs text-danger-emphasis">{job.errorMessage}</p>
                    )}
                  </td>
                  <td className="py-2 pr-3 text-2xs text-muted-foreground">{job.pagesProcessed ?? '—'}</td>
                  <td className="py-2 pr-3 text-2xs text-muted-foreground">{job.chunksStored ?? '—'}</td>
                  <td className="py-2 pr-3 text-2xs text-muted-foreground">
                    {job.startedAt ? absoluteTime(job.startedAt) : '—'}
                  </td>
                  <td className="py-2 text-right">
                    {job.status === 'FAILED' && (
                      <IconButton
                        size="sm"
                        label={`Retrigger ${job.jobType.toLowerCase()} job for ${job.spaceKey ?? job.pageId ?? 'this target'}`}
                        icon={<RotateCcw size={14} />}
                        onClick={() => retrigger.mutate(job)}
                        disabled={retrigger.isPending}
                      />
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
