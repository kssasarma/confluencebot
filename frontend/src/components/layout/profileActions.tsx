import { LogOut, Settings } from 'lucide-react'
import type { MenuAction } from '../ui/Menu'

/**
 * The action list, factored out of the component so it is a plain function a test can call
 * directly, rather than something only observable by opening a floating-ui-anchored dropdown.
 *
 * Settings, Admin and Ingestor Settings used to be three separate entries here, two of them
 * gated on `canAdminister`. They are now the one dialog `onOpenSettings` opens, which does its own
 * gating per section — so this list no longer needs to know who can administer at all.
 */
export function buildProfileActions(options: {
  onOpenSettings: () => void
  onSignOut: () => void
}): MenuAction[] {
  const { onOpenSettings, onSignOut } = options
  return [
    { label: 'Settings', icon: <Settings size={14} />, onSelect: onOpenSettings },
    { label: 'Sign out', icon: <LogOut size={14} />, tone: 'danger' as const, separated: true, onSelect: onSignOut },
  ]
}
