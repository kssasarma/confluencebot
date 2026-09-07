import { ChevronDown } from 'lucide-react'
import { useAuth } from '../../context/AuthContext'
import { useConfirm } from '../ui/ConfirmDialog'
import Badge from '../ui/Badge'
import Menu from '../ui/Menu'
import { buildProfileActions } from './profileActions'

const ROLE_LABEL: Record<string, string> = {
  ADMIN: 'Admin',
  ADMIN_READ_ONLY: 'Admin (read-only)',
  INGESTOR: 'Ingestor',
  USER: 'User',
}

/**
 * Everything about the signed-in person, gathered under one control at the top right.
 *
 * Identity, role and every account-level action used to be split between a menu pinned to the
 * bottom of the sidebar and nothing at all elsewhere — so a reader on the admin screen or in
 * settings had no way to tell who they were signed in as, or to sign out, without navigating back
 * to the chat list first. One menu, one place, reachable from every screen this shell renders.
 *
 * The trigger shows the reader's name, not their email — the sign-in address is an identifier, not
 * how anyone (including the reader themselves) thinks of themselves. The app forces a name to be
 * set before this component can render at all (see `App.tsx`'s `CompleteProfilePage` gate), but
 * the email fallback stays here in case that guarantee is ever relaxed.
 */
export default function ProfileMenu({ onOpenSettings }: { onOpenSettings: () => void }) {
  const { user, logout } = useAuth()
  const confirm = useConfirm()

  if (!user) return null

  const displayName = user.name ?? user.email

  async function signOut() {
    const confirmed = await confirm({
      title: 'Sign out?',
      description: 'You will need your password to sign back in.',
      confirmLabel: 'Sign out',
    })
    if (confirmed) logout()
  }

  return (
    <Menu
      placement="bottom end"
      trigger={
        <button
          className="flex items-center gap-2 rounded-lg px-2 py-1.5 text-left transition-colors hover:bg-surface-hover"
          aria-label={`Account menu for ${user.email}`}
        >
          <span
            aria-hidden="true"
            className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary-soft text-2xs font-semibold uppercase text-primary-emphasis"
          >
            {displayName.slice(0, 2)}
          </span>
          <span className="hidden max-w-[12rem] truncate text-sm text-foreground sm:inline">
            {displayName}
          </span>
          <ChevronDown size={14} aria-hidden="true" className="shrink-0 text-muted-foreground" />
        </button>
      }
      header={
        <div className="space-y-1.5">
          {/* Name first, email second: the name is the identity, the email is only how they
              signed in. */}
          <p className="truncate text-sm font-medium text-foreground">{displayName}</p>
          {user.name && <p className="truncate text-2xs text-muted-foreground">{user.email}</p>}
          <div className="flex flex-wrap gap-1">
            {user.roles.map(role => (
              <Badge key={role} tone={role === 'USER' ? 'info' : 'accent'}>
                {ROLE_LABEL[role] ?? role}
              </Badge>
            ))}
          </div>
        </div>
      }
      actions={buildProfileActions({ onOpenSettings, onSignOut: signOut })}
    />
  )
}
