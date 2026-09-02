import { ChevronsUpDown, LogOut, Monitor, Moon, Sun } from 'lucide-react'
import { useAuth } from '../../context/AuthContext'
import { useTheme, type Theme } from '../../context/ThemeContext'
import { useConfirm } from '../ui/ConfirmDialog'
import Menu from '../ui/Menu'

const THEME_ORDER: Theme[] = ['light', 'dark', 'system']

const THEME_ICON = {
  light: <Sun size={14} />,
  dark: <Moon size={14} />,
  system: <Monitor size={14} />,
} as const

const THEME_LABEL = {
  light: 'Light theme',
  dark: 'Dark theme',
  system: 'Match system theme',
} as const

/**
 * Who is signed in, and the handful of things that belong to them.
 *
 * The theme lives here as well as in settings: switching it is a several-times-a-day action, and
 * routing it through a settings page for something that is a preference of the moment is the
 * wrong trade. Both write the same stored value.
 *
 * Settings and Admin stay as links in the sidebar rather than moving in here: a menu item cannot
 * be opened in a new tab, and both are pages worth keeping open beside a conversation.
 */
export default function AccountMenu() {
  const { user, logout } = useAuth()
  const { theme, setTheme } = useTheme()
  const confirm = useConfirm()

  if (!user) return null

  async function signOut() {
    const confirmed = await confirm({
      title: 'Sign out?',
      description: 'You will need your password to sign back in.',
      confirmLabel: 'Sign out',
    })
    if (confirmed) logout()
  }

  const nextTheme = THEME_ORDER[(THEME_ORDER.indexOf(theme) + 1) % THEME_ORDER.length]

  return (
    <Menu
      // Pinned to the bottom of the sidebar, so it must open upwards or it opens off-screen.
      placement="top start"
      className="w-full"
      trigger={
        <button
          className="flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left transition-colors hover:bg-surface-hover"
          aria-label={`Account menu for ${user.email}`}
        >
          <span
            aria-hidden="true"
            className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary-soft text-2xs font-semibold uppercase text-primary-emphasis"
          >
            {user.email.slice(0, 2)}
          </span>
          <span className="min-w-0 flex-1 truncate text-2xs text-muted-foreground">
            {user.email}
          </span>
          <ChevronsUpDown size={13} aria-hidden="true" className="shrink-0 text-muted-foreground" />
        </button>
      }
      actions={[
        {
          label: THEME_LABEL[nextTheme],
          icon: THEME_ICON[nextTheme],
          onSelect: () => setTheme(nextTheme),
        },
        { label: 'Sign out', icon: <LogOut size={14} />, tone: 'danger', separated: true, onSelect: signOut },
      ]}
    />
  )
}
