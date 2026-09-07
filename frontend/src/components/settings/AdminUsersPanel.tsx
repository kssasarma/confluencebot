import { useRef, useState } from 'react'
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
import { useDismiss } from '../../hooks/useDismiss'
import { useConfirm } from '../ui/ConfirmDialog'
import { useToast } from '../ui/Toast'
import { cn } from '../../lib/cn'
import Badge from '../ui/Badge'
import Button from '../ui/Button'
import EmptyState from '../ui/EmptyState'
import IconButton from '../ui/IconButton'
import Input from '../ui/Input'
import Tooltip from '../ui/Tooltip'
import { SkeletonText } from '../ui/Skeleton'

const ROLE_LABELS: Record<AdminRole, string> = {
  ADMIN: 'Admin',
  ADMIN_READ_ONLY: 'Admin (read-only)',
  INGESTOR: 'Ingestor',
  USER: 'User',
}

const ROLES = Object.keys(ROLE_LABELS) as AdminRole[]

/** An icon button that stays hidden until its row (or the button itself) is hovered or focused. */
const REVEAL_ON_HOVER = 'opacity-0 group-hover:opacity-100 group-focus-within:opacity-100'

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

/** Compact role display: one badge, with the full list on hover/focus once there's more than one. */
function RoleSummary({ roles }: { roles: AdminRole[] }) {
  const labels = roles.map(role => ROLE_LABELS[role] ?? role)
  const badge = (
    <Badge tone={roles[0] === 'USER' ? 'info' : 'accent'}>
      {labels[0]}{labels.length > 1 && ` +${labels.length - 1}`}
    </Badge>
  )
  return labels.length > 1 ? <Tooltip content={labels.join(', ')}>{badge}</Tooltip> : badge
}

interface WelcomeEmailResult {
  action: 'created' | 'resent'
  email: string
  tempPassword: string
  emailSent: boolean
}

function statusBadge(user: AdminUser) {
  if (!user.enabled) return <Badge tone="danger">Disabled</Badge>
  if (user.mustChangePassword) return <Badge tone="warning">Pending setup</Badge>
  return <Badge tone="success">Active</Badge>
}

interface UserRowProps {
  user: AdminUser
  onWelcomeEmail: (result: WelcomeEmailResult) => void
}

/** One row of the user table, including its own floating editors for roles and business unit. */
function UserRow({ user, onWelcomeEmail }: UserRowProps) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const confirm = useConfirm()
  const { isAdmin, user: signedIn } = useAuth()
  const isSelf = signedIn?.email === user.email

  const [rolesOpen, setRolesOpen] = useState(false)
  const [buOpen, setBuOpen] = useState(false)
  const [buDraft, setBuDraft] = useState(user.businessUnit ?? '')

  const rolesRef = useRef<HTMLDivElement>(null)
  const buRef = useRef<HTMLDivElement>(null)
  useDismiss(rolesRef, () => setRolesOpen(false), rolesOpen)
  useDismiss(buRef, () => setBuOpen(false), buOpen)

  const applyUser = (updated: AdminUser) => {
    queryClient.setQueryData<AdminUser[]>(queryKeys.adminUsers, current =>
      current?.map(u => (u.id === updated.id ? updated : u)))
  }

  const toggle = useMutation({
    mutationFn: () => setUserEnabled(user.id, !user.enabled),
    onSuccess: applyUser,
    onError: error => toast.error('Could not update the user', toMessage(error, 'Please try again.')),
  })

  const changeRoles = useMutation({
    mutationFn: (next: AdminRole[]) => setUserRoles(user.id, next),
    onSuccess: updated => {
      applyUser(updated)
      toast.success('Roles updated', `${updated.email} is now ${updated.roles.map(r => ROLE_LABELS[r]).join(', ')}.`)
    },
    onError: error => toast.error('Could not change the roles', toMessage(error, 'Please try again.')),
  })

  const changeBusinessUnit = useMutation({
    mutationFn: (next: string) => setUserBusinessUnit(user.id, next),
    onSuccess: updated => {
      applyUser(updated)
      setBuOpen(false)
    },
    onError: error => toast.error('Could not update the business unit', toMessage(error, 'Please try again.')),
  })

  const resend = useMutation({
    mutationFn: () => resendWelcome(user.id),
    onSuccess: result => {
      applyUser(result.user)
      onWelcomeEmail({
        action: 'resent', email: user.email,
        tempPassword: result.tempPassword, emailSent: result.emailSent,
      })
    },
    onError: error => toast.error('Could not resend the welcome email', toMessage(error, 'Please try again.')),
  })

  const remove = useMutation({
    mutationFn: () => deleteUser(user.id),
    onSuccess: () => {
      queryClient.setQueryData<AdminUser[]>(queryKeys.adminUsers, current =>
        current?.filter(u => u.id !== user.id))
      toast.success('User deleted', `${user.email} and everything scoped to their account is gone.`)
    },
    onError: error => toast.error('Could not delete the user', toMessage(error, 'Please try again.')),
  })

  function openBuEditor() {
    setBuDraft(user.businessUnit ?? '')
    setBuOpen(open => !open)
  }

  async function handleResend() {
    const confirmed = await confirm({
      title: `Resend welcome email to ${user.email}?`,
      description: 'This issues a brand new temporary password — the old one, if any, stops working.',
      confirmLabel: 'Resend',
    })
    if (confirmed) resend.mutate()
  }

  async function handleDelete() {
    const confirmed = await confirm({
      title: `Delete ${user.email}?`,
      description: 'This permanently deletes the account and every chat, session and preference tied to it. This cannot be undone.',
      confirmLabel: 'Delete',
      tone: 'danger',
    })
    if (confirmed) remove.mutate()
  }

  return (
    <tr className="group text-foreground">
      <td className="py-2 pr-4 font-mono text-2xs">{user.email}</td>
      <td className="py-2 pr-4 text-2xs text-muted-foreground">{user.name ?? '—'}</td>
      <td className="py-2 pr-4">
        <div ref={buRef} className="relative inline-block">
          <div className="flex items-center gap-1">
            <span className="text-2xs text-muted-foreground">{user.businessUnit ?? '—'}</span>
            {isAdmin && (
              <IconButton
                size="sm"
                active={buOpen}
                label={buOpen ? `Close business unit editor for ${user.email}` : `Edit business unit for ${user.email}`}
                icon={<Pencil size={12} />}
                onClick={openBuEditor}
                disabled={changeBusinessUnit.isPending}
                className={buOpen ? undefined : REVEAL_ON_HOVER}
              />
            )}
          </div>
          {buOpen && (
            <form
              className="absolute left-0 top-full z-dropdown mt-1.5 flex w-56 items-center gap-1.5 rounded-xl border border-border bg-surface p-2 shadow-overlay"
              onSubmit={event => {
                event.preventDefault()
                changeBusinessUnit.mutate(buDraft.trim())
              }}
            >
              <Input
                aria-label={`Business unit for ${user.email}`}
                autoFocus
                value={buDraft}
                onChange={event => setBuDraft(event.target.value)}
                placeholder="e.g. Engineering"
              />
              <Button type="submit" size="sm" loading={changeBusinessUnit.isPending}>
                Save
              </Button>
            </form>
          )}
        </div>
      </td>
      <td className="py-2 pr-4">
        <div ref={rolesRef} className="relative inline-block">
          <div className="flex items-center gap-1">
            <RoleSummary roles={user.roles} />
            {/* Your own row has no editor: the request that strips your own admin is the last
                one you are allowed to make, so the API refuses it and so does this. */}
            {isAdmin && !isSelf && (
              <IconButton
                size="sm"
                active={rolesOpen}
                label={rolesOpen ? `Close role editor for ${user.email}` : `Edit roles for ${user.email}`}
                icon={<Pencil size={12} />}
                onClick={() => setRolesOpen(open => !open)}
                disabled={changeRoles.isPending}
                className={rolesOpen ? undefined : REVEAL_ON_HOVER}
              />
            )}
          </div>
          {rolesOpen && (
            <div className="absolute left-0 top-full z-dropdown mt-1.5 w-max rounded-xl border border-border bg-surface p-2 shadow-overlay">
              <RoleToggleGroup
                selected={user.roles}
                disabled={changeRoles.isPending}
                onToggle={role => {
                  const next = toggleRole(user.roles, role)
                  if (next !== user.roles) changeRoles.mutate(next)
                }}
              />
            </div>
          )}
        </div>
      </td>
      <td className="py-2 pr-4">{statusBadge(user)}</td>
      <td className="py-2 text-right">
        <div className="flex justify-end gap-1">
          {isAdmin && user.mustChangePassword && (
            <IconButton
              size="sm"
              label={`Resend welcome email to ${user.email}`}
              icon={<Mail size={14} />}
              onClick={handleResend}
              disabled={resend.isPending}
              className={REVEAL_ON_HOVER}
            />
          )}
          {isAdmin && !isSelf && (
            <>
              <IconButton
                size="sm"
                label={user.enabled ? `Disable ${user.email}` : `Enable ${user.email}`}
                icon={user.enabled ? <Ban size={14} /> : <Check size={14} />}
                onClick={() => toggle.mutate()}
                disabled={toggle.isPending}
                className={REVEAL_ON_HOVER}
              />
              <IconButton
                size="sm"
                variant="danger"
                label={`Delete ${user.email}`}
                icon={<Trash2 size={14} />}
                onClick={handleDelete}
                disabled={remove.isPending}
                className={REVEAL_ON_HOVER}
              />
            </>
          )}
        </div>
      </td>
    </tr>
  )
}

/** User management: the "Admin" section of the unified Settings dialog. */
export default function AdminUsersPanel() {
  const queryClient = useQueryClient()
  const toast = useToast()

  const [email, setEmail] = useState('')
  const [roles, setRoles] = useState<AdminRole[]>(['USER'])
  const [businessUnit, setBusinessUnit] = useState('')
  const [welcomeResult, setWelcomeResult] = useState<WelcomeEmailResult | null>(null)

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

  return (
    <div className="space-y-6">
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
                <th scope="col" className="pb-2"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {users.data?.map(user => (
                <UserRow key={user.id} user={user} onWelcomeEmail={setWelcomeResult} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
