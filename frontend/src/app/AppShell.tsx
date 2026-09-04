import { Suspense, lazy, useEffect, useState } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { Dialog, DialogPanel, Transition, TransitionChild } from '@headlessui/react'
import { Menu as MenuIcon, WifiOff } from 'lucide-react'
import { cn } from '../lib/cn'
import { useIsDesktop } from '../hooks/useMediaQuery'
import { useOnlineStatus } from '../hooks/useOnlineStatus'
import { useResizable } from '../hooks/useResizable'
import { useHotkeys } from '../hooks/useHotkeys'
import Sidebar from '../components/sidebar/Sidebar'
const CommandPalette = lazy(() => import('../components/palette/CommandPalette'))
import ProfileMenu from '../components/layout/ProfileMenu'
import ErrorBoundary from '../components/ui/ErrorBoundary'
import IconButton from '../components/ui/IconButton'
import Spinner from '../components/ui/Spinner'

const SIDEBAR = {
  storageKey: 'cb_sidebar_width',
  defaultWidth: 280,
  minWidth: 220,
  maxWidth: 480,
}

/**
 * The application frame: a resizable sidebar beside the routed content.
 *
 * Below `md` the sidebar is not merely narrower, it is a different component — a modal drawer.
 * A 280px panel that is always present leaves a 95px column on a 375px phone, which is not a
 * layout so much as a rendering accident.
 */
export default function AppShell() {
  const isDesktop = useIsDesktop()
  const online = useOnlineStatus()
  const location = useLocation()

  const [drawerOpen, setDrawerOpen] = useState(false)
  const [paletteOpen, setPaletteOpen] = useState(false)
  const sidebar = useResizable(SIDEBAR)

  // A drawer left open across a navigation would cover the page the reader just asked for.
  useEffect(() => setDrawerOpen(false), [location.pathname])

  useHotkeys([
    { key: 'k', meta: true, allowInInput: true, handler: () => setPaletteOpen(open => !open) },
  ])

  return (
    <div className="flex h-dvh overflow-hidden bg-background">
      {isDesktop ? (
        <>
          <aside
            style={{ width: sidebar.width }}
            className="h-full shrink-0 border-r border-border"
          >
            <Sidebar />
          </aside>

          <div
            {...sidebar.handleProps}
            className={cn(
              'group relative -ml-1 w-2 shrink-0 cursor-col-resize',
              // The hit area is 8px wide; the visible line is 2px, centred inside it. A handle
              // the width of its own graphic is a handle nobody can grab.
              'before:absolute before:inset-y-0 before:left-1/2 before:w-0.5 before:-translate-x-1/2',
              'before:bg-transparent before:transition-colors hover:before:bg-primary/40',
              'focus-visible:before:bg-primary',
              sidebar.isDragging && 'before:bg-primary',
            )}
          />
        </>
      ) : (
        <Transition show={drawerOpen}>
          <Dialog onClose={() => setDrawerOpen(false)} className="relative z-drawer">
            <TransitionChild
              enter="duration-fast ease-out-expo" enterFrom="opacity-0" enterTo="opacity-100"
              leave="duration-fast ease-out-expo" leaveFrom="opacity-100" leaveTo="opacity-0"
            >
              <div className="fixed inset-0 bg-foreground/40" aria-hidden="true" />
            </TransitionChild>

            <TransitionChild
              enter="duration-base ease-out-expo" enterFrom="-translate-x-full" enterTo="translate-x-0"
              leave="duration-fast ease-out-expo" leaveFrom="translate-x-0" leaveTo="-translate-x-full"
            >
              <DialogPanel className="fixed inset-y-0 left-0 w-[min(20rem,85vw)] border-r border-border shadow-overlay">
                <Sidebar onNavigate={() => setDrawerOpen(false)} />
              </DialogPanel>
            </TransitionChild>
          </Dialog>
        </Transition>
      )}

      <div className="flex min-w-0 flex-1 flex-col">
        {!online && <OfflineBanner />}

        {/*
          Always present, on every breakpoint: it is the only place identity, role and account
          actions live now, so a reader on the admin screen or in settings still has a way to see
          who they are signed in as and to sign out without navigating back to the chat list.
        */}
        <div className="flex items-center gap-2 border-b border-border px-2 py-2">
          {!isDesktop && (
            <>
              <IconButton
                label="Open conversations"
                icon={<MenuIcon size={18} />}
                onClick={() => setDrawerOpen(true)}
              />
              <span className="text-sm font-semibold text-foreground">Confluence Bot</span>
            </>
          )}
          <div className="ml-auto">
            <ProfileMenu />
          </div>
        </div>

        <main className="min-h-0 flex-1">
          <ErrorBoundary>
            <Suspense
              fallback={
                <div className="flex h-full items-center justify-center">
                  <Spinner size="lg" label="Loading" />
                </div>
              }
            >
              <Outlet />
            </Suspense>
          </ErrorBoundary>
        </main>
      </div>

      {/* Rendered only once opened, so the chunk is fetched on first use rather than on load. */}
      {paletteOpen && (
        <Suspense fallback={null}>
          <CommandPalette open onClose={() => setPaletteOpen(false)} />
        </Suspense>
      )}
    </div>
  )
}

/**
 * A persistent banner while the browser reports no connection.
 *
 * `role="status"` rather than `alert`: losing connectivity is worth announcing, but not worth
 * interrupting whatever a screen-reader user is in the middle of reading.
 */
function OfflineBanner() {
  return (
    <div
      role="status"
      className="flex items-center justify-center gap-2 bg-warning-soft px-4 py-1.5 text-2xs text-warning-emphasis"
    >
      <WifiOff size={13} aria-hidden="true" />
      You are offline. Answers will work again once you reconnect.
    </div>
  )
}
