import { LogOut, Monitor, Moon, ShieldCheck, Settings, Sun } from 'lucide-react'
import type { Theme } from '../../context/ThemeContext'
import type { MenuAction } from '../ui/Menu'

export const THEME_ORDER: Theme[] = ['light', 'dark', 'system']

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
 * The action list, factored out of the component so the one rule that matters here — Admin is
 * offered only to a reader who can actually reach it — is a plain function a test can call
 * directly, rather than something only observable by opening a floating-ui-anchored dropdown.
 */
export function buildProfileActions(options: {
  canAdminister: boolean
  nextTheme: Theme
  onThemeChange: () => void
  onGoToAdmin: () => void
  onGoToSettings: () => void
  onSignOut: () => void
}): MenuAction[] {
  const { canAdminister, nextTheme, onThemeChange, onGoToAdmin, onGoToSettings, onSignOut } = options
  return [
    { label: THEME_LABEL[nextTheme], icon: THEME_ICON[nextTheme], onSelect: onThemeChange },
    ...(canAdminister
      ? [{ label: 'Admin', icon: <ShieldCheck size={14} />, onSelect: onGoToAdmin }]
      : []),
    { label: 'Settings', icon: <Settings size={14} />, onSelect: onGoToSettings },
    { label: 'Sign out', icon: <LogOut size={14} />, tone: 'danger' as const, separated: true, onSelect: onSignOut },
  ]
}
