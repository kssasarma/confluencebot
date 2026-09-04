import { Fragment, type ReactNode } from 'react'
import {
  Menu as HeadlessMenu, MenuButton, MenuItem, MenuItems, Transition,
} from '@headlessui/react'
import { cn } from '../../lib/cn'

/**
 * A keyboard-navigable dropdown.
 *
 * `anchor` rather than absolute positioning: a menu on a row pinned to the bottom of the sidebar
 * has to open upwards, and one inside a scrolling list has to escape its `overflow`. Headless UI
 * anchors through a portal and flips automatically, which is the whole reason to take the
 * dependency.
 */

export interface MenuAction {
  label: string
  icon?: ReactNode
  onSelect: () => void
  tone?: 'default' | 'danger'
  disabled?: boolean
  /** Draws a rule above this item. */
  separated?: boolean
}

interface MenuProps {
  trigger: ReactNode
  actions: MenuAction[]
  placement?: 'bottom end' | 'bottom start' | 'top end' | 'top start'
  className?: string
  /** Non-interactive content above the actions — identity, status, anything that isn't a command. */
  header?: ReactNode
}

export default function Menu({
  trigger, actions, placement = 'bottom end', className, header,
}: MenuProps) {
  return (
    <HeadlessMenu as="div" className={cn('relative inline-block', className)}>
      <MenuButton as={Fragment}>{trigger}</MenuButton>

      <Transition
        as={Fragment}
        enter="duration-fast ease-out-expo" enterFrom="opacity-0 scale-95" enterTo="opacity-100 scale-100"
        leave="duration-fast ease-out-expo" leaveFrom="opacity-100 scale-100" leaveTo="opacity-0 scale-95"
      >
        <MenuItems
          anchor={{ to: placement, gap: 6 }}
          className="z-dropdown w-52 rounded-xl border border-border bg-surface p-1 shadow-overlay focus:outline-none"
        >
          {header && <div className="border-b border-border px-2.5 py-2">{header}</div>}
          {actions.map(action => (
            <Fragment key={action.label}>
              {action.separated && <div className="my-1 h-px bg-border" role="none" />}
              <MenuItem disabled={action.disabled}>
                {({ focus }) => (
                  <button
                    onClick={action.onSelect}
                    disabled={action.disabled}
                    data-focus-ring="none"
                    className={cn(
                      'flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-sm',
                      'transition-colors duration-fast disabled:opacity-40',
                      action.tone === 'danger' ? 'text-danger-emphasis' : 'text-foreground',
                      // Headless UI drives the highlight so that hover and keyboard focus are the
                      // same visual state — otherwise arrowing through a menu shows nothing.
                      focus && (action.tone === 'danger' ? 'bg-danger-soft' : 'bg-surface-hover'),
                    )}
                  >
                    {action.icon && <span aria-hidden="true" className="shrink-0">{action.icon}</span>}
                    <span className="truncate">{action.label}</span>
                  </button>
                )}
              </MenuItem>
            </Fragment>
          ))}
        </MenuItems>
      </Transition>
    </HeadlessMenu>
  )
}
