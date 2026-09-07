import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Ban, Check, Mail, Pencil, Trash2, UserPlus } from 'lucide-react'
import {
  createUser, deleteUser, listUsers, resendWelcome, setUserBusinessUnit, setUserEnabled, setUserRoles,
  type AdminRole, type AdminUser,
} from '../../services/adminService'
import { useAuth } from '../../context/AuthContext'
import { queryKeys } from '../../services/queryKeys'
import { toMessage } from '../../lib/errors'
import { toggleRole } from '../../lib/roles'
import { useConfirm } from '../ui/ConfirmDialog'
import { useToast } from '../ui/Toast'
import { cn } from '../../lib/cn'
import AdminAnalyticsPanel from './AdminAnalyticsPanel'
import Badge from '../ui/Badge'
import Button from '../ui/Button'
import EmptyState from '../ui/EmptyState'
import IconButton from '../ui/IconButton'
import Input from '../ui/Input'
import { SkeletonText } from '../ui/Skeleton'

const ROLE_LABELS: Record<AdminRole, string> = {
  ADMIN: 'Admin',
  ADMIN_READ_ONLY: 'Admin (read-only)',
  INGESTOR: 'Ingestor',
  USER: 'User',
}

const ROLES = Object.keys(ROLE_LABELS) as AdminRole[]

/** A row of pills toggling membership in `selected`. Used for both creating and re-assigning. */
function RoleToggleGroup({
  selected, onToggle, disabled,
}: {
  selected: AdminRole[]
  onToggle: (role: AdminRole) => void
  disabled?: boolean
}) {
  return (
    <div className="flex flex-wrap gap-1.5" role="group" aria-label="Roles">
      {ROLES.map(role => {
        const active = selected.includes(role)
        return (
          <button
            key={role}
            type="button"
            role="checkbox"
            aria-checked={active}
            aria-label={ROLE_LABELS[role]}
            disabled={disabled}
            onClick={() => onToggle(role)}
            className={cn(
              'rounded-full border px-2.5 py-1 text-2xs font-medium transition-colors',
              'disabled:cursor-not-allowed disabled:opacity-50',
              active
                ? 'border-primary bg-primary-soft text-primary-emphasis'
                : 'border-border text-muted-foreground hover:bg-surface-hover',
            )}
          >
            {ROLE_LABELS[role]}
          </button>
        )
      })}
    </div>
  )
}

interface WelcomeEmailResult {
  action: 'created' | 'resent'
  email: string
  tempPassword: string
  emailSent: boolean
}

/** User management: the "Admin" section of the unified Settings dialog. */
export default function AdminUsersPanel() {
  const queryClient = useQueryClient()
  const toast = useToast()
  const confirm = useConfirm()
  const { isAdmin, user: signedIn } = useAuth()

  const [email, setEmail] = useState('')
  const [roles, setRoles] = useState<AdminRole[]>(['USER'])
  const [businessUnit, setBusinessUnit] = useState('')
  const [welcomeResult, setWelcomeResult] = useState<WelcomeEmailResult | null>(null)
  const [editingRolesFor, setEditingRolesFor] = useState<number | null>(null)
  const [editingBuFor, setEditingBuFor] = useState<number | null>(null)
  const [buDraft, setBuDraft] = useState('')

  const users = useQuery({ queryKey: queryKeys.adminUsers, queryFn: listUsers })

  const create = useMutation({
    mutationFn: () => createUser(email.trim(), roles, undefined, businessUnit.trim() || undefined),
    onSuccess: result => {
      setWelcomeResult({
        action: 'created', email: result.user.email,
        tempPassword: result.tempPassword, emailSent: result.emailSent,
      })
      setEmail('')
      setRoles(['USER'])
      setBusinessUnit('')
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

  const changeRoles = useMutation({
    mutationFn: ({ user, next }: { user: AdminUser; next: AdminRole[] }) => setUserRoles(user.id, next),
    onSuccess: updated => {
      applyUser(updated)
      toast.success('Roles updated', `${updated.email} is now ${updated.roles.map(r => ROLE_LABELS[r]).join(', ')}.`)
    },
    onError: error => toast.error('Could not change the roles', toMessage(error, 'Please try again.')),
  })

  const changeBusinessUnit = useMutation({
    mutationFn: ({ user, next }: { user: AdminUser; next: string }) => setUserBusinessUnit(user.id, next),
    onSuccess: updated => {
      applyUser(updated)
      setEditingBuFor(null)
    },
    onError: error => toast.error('Could not update the business unit', toMessage(error, 'Please try again.')),
  })

  function startEditingBu(user: AdminUser) {
    setBuDraft(user.businessUnit ?? '')
    setEditingBuFor(current => (current === user.id ? null : user.id))
  }

  const resend = useMutation({
    mutationFn: (user: AdminUser) => resendWelcome(user.id),
    onSuccess: (result, user) => {
      applyUser(result.user)
      setWelcomeResult({
        action: 'resent', email: user.email,
        tempPassword: result.tempPassword, emailSent: result.emailSent,
      })
    },
    onError: error => toast.error('Could not resend the welcome email', toMessage(error, 'Please try again.')),
  })

  const remove = useMutation({
    mutationFn: (user: AdminUser) => deleteUser(user.id),
    onSuccess: (_result, user) => {
      queryClient.setQueryData<AdminUser[]>(queryKeys.adminUsers, current =>
        current?.filter(u => u.id !== user.id))
      toast.success('User deleted', `${user.email} and everything scoped to their account is gone.`)
    },
    onError: error => toast.error('Could not delete the user', toMessage(error, 'Please try again.')),
  })

  async function handleResend(user: AdminUser) {
    const confirmed = await confirm({
      title: `Resend welcome email to ${user.email}?`,
      description: 'This issues a brand new temporary password — the old one, if any, stops working.',
      confirmLabel: 'Resend',
    })
    if (confirmed) resend.mutate(user)
  }

  async function handleDelete(user: AdminUser) {
    const confirmed = await confirm({
      title: `Delete ${user.email}?`,
      description: 'This permanently deletes the account and every chat, session and preference tied to it. This cannot be undone.',
      confirmLabel: 'Delete',
      tone: 'danger',
    })
    if (confirmed) remove.mutate(user)
  }

  return (
    <div className="space-y-6">
      {/* Onboarding/usage analytics are a full-admin thing — a read-only admin can see and manage
          users here, but not how much the deployment is being used or by whom. */}
      {isAdmin && <AdminAnalyticsPanel />}

      {welcomeResult && (
        <div role="status" className="rounded-lg border border-success/40 bg-success-soft p-4 text-sm">
          <p className="mb-1 font-medium text-success-emphasis">
            {welcomeResult.action === 'created' ? 'User created' : 'Welcome email resent'}
          </p>
          {welcomeResult.emailSent ? (
            <p className="text-muted-foreground">
              Sign-in instructions were emailed to{' '}
              <span className="font-mono text-foreground">{welcomeResult.email}</span>. No need to
              share a password yourself.
            </p>
          ) : (
            <>
              <p className="text-muted-foreground">
                Email: <span className="font-mono text-foreground">{welcomeResult.email}</span>
              </p>
              <p className="text-muted-foreground">
                Temporary password:{' '}
                <span className="font-mono text-foreground">{welcomeResult.tempPassword}</span>
              </p>
              <p className="mt-1 text-2xs text-muted-foreground">
                We couldn't email this — mail may not be configured or reachable right now. Share
                it over a secure channel; it is shown once, and the user must change it at first
                sign-in. Once mail is working again, use "Resend welcome email" instead of sharing
                passwords by hand.
              </p>
            </>
          )}
          <Button size="sm" variant="ghost" className="mt-2" onClick={() => setWelcomeResult(null)}>
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
          <span className="text-sm font-medium text-foreground">Roles</span>
          <RoleToggleGroup selected={roles} onToggle={role => setRoles(current => toggleRole(current, role))} />
        </div>

        <div className="min-w-[10rem]">
          <Input
            label="Business unit"
            value={businessUnit}
            onChange={event => setBusinessUnit(event.target.value)}
            placeholder="Optional"
          />
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
                <th scope="col" className="pb-2 font-medium text-muted-foreground">Name</th>
                <th scope="col" className="pb-2 font-medium text-muted-foreground">Business unit</th>
                <th scope="col" className="pb-2 font-medium text-muted-foreground">Roles</th>
                <th scope="col" className="pb-2 font-medium text-muted-foreground">Status</th>
                <th scope="col" className="pb-2 font-medium text-muted-foreground">Must change password</th>
                <th scope="col" className="pb-2"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {users.data?.map(user => (
                <tr key={user.id} className="text-foreground">
                  <td className="py-2.5 pr-4 font-mono text-2xs">{user.email}</td>
                  <td className="py-2.5 pr-4 text-2xs text-muted-foreground">{user.name ?? '—'}</td>
                  <td className="py-2.5 pr-4">
                    <div className="flex items-center gap-1">
                      <span className="text-2xs text-muted-foreground">{user.businessUnit ?? '—'}</span>
                      {isAdmin && (
                        <IconButton
                          size="sm"
                          active={editingBuFor === user.id}
                          label={
                            editingBuFor === user.id
                              ? `Close business unit editor for ${user.email}`
                              : `Edit business unit for ${user.email}`
                          }
                          icon={<Pencil size={12} />}
                          onClick={() => startEditingBu(user)}
                          disabled={changeBusinessUnit.isPending}
                        />
                      )}
                    </div>
                    {editingBuFor === user.id && (
                      <form
                        className="mt-1.5 flex items-center gap-1.5"
                        onSubmit={event => {
                          event.preventDefault()
                          changeBusinessUnit.mutate({ user, next: buDraft.trim() })
                        }}
                      >
                        <Input
                          aria-label={`Business unit for ${user.email}`}
                          value={buDraft}
                          onChange={event => setBuDraft(event.target.value)}
                          placeholder="e.g. Engineering"
                        />
                        <Button type="submit" size="sm" loading={changeBusinessUnit.isPending}>
                          Save
                        </Button>
                      </form>
                    )}
                  </td>
                  <td className="py-2.5 pr-4">
                    <div className="flex flex-wrap items-center gap-1">
                      {user.roles.map(userRole => (
                        <Badge key={userRole} tone={userRole === 'USER' ? 'info' : 'accent'}>
                          {ROLE_LABELS[userRole] ?? userRole}
                        </Badge>
                      ))}
                      {/* Your own row has no editor: the request that strips your own admin is
                          the last one you are allowed to make, so the API refuses it and so does
                          this. */}
                      {isAdmin && signedIn?.email !== user.email && (
                        <IconButton
                          size="sm"
                          active={editingRolesFor === user.id}
                          label={
                            editingRolesFor === user.id
                              ? `Close role editor for ${user.email}`
                              : `Edit roles for ${user.email}`
                          }
                          icon={<Pencil size={12} />}
                          onClick={() => setEditingRolesFor(current => (current === user.id ? null : user.id))}
                          disabled={changeRoles.isPending}
                        />
                      )}
                    </div>
                    {editingRolesFor === user.id && (
                      <div className="mt-1.5">
                        <RoleToggleGroup
                          selected={user.roles}
                          disabled={changeRoles.isPending}
                          onToggle={role => {
                            const next = toggleRole(user.roles, role)
                            if (next !== user.roles) changeRoles.mutate({ user, next })
                          }}
                        />
                      </div>
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
                    <div className="flex justify-end gap-1">
                      {isAdmin && user.mustChangePassword && (
                        <IconButton
                          size="sm"
                          label={`Resend welcome email to ${user.email}`}
                          icon={<Mail size={14} />}
                          onClick={() => handleResend(user)}
                          disabled={resend.isPending}
                        />
                      )}
                      {isAdmin && signedIn?.email !== user.email && (
                        <>
                          <IconButton
                            size="sm"
                            label={user.enabled ? `Disable ${user.email}` : `Enable ${user.email}`}
                            icon={user.enabled ? <Ban size={14} /> : <Check size={14} />}
                            onClick={() => toggle.mutate(user)}
                            disabled={toggle.isPending}
                          />
                          <IconButton
                            size="sm"
                            variant="danger"
                            label={`Delete ${user.email}`}
                            icon={<Trash2 size={14} />}
                            onClick={() => handleDelete(user)}
                            disabled={remove.isPending}
                          />
                        </>
                      )}
                    </div>
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
