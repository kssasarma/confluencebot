import { useState } from 'react'
import { useAuth } from '../../context/AuthContext'
import { cn } from '../../lib/cn'
import Modal from '../ui/Modal'
import GeneralSettingsPanel from './GeneralSettingsPanel'
import AdminUsersPanel from './AdminUsersPanel'
import AdminIngestionPanel from './AdminIngestionPanel'

type Section = 'general' | 'admin' | 'ingestion'

const SECTION_LABEL: Record<Section, string> = {
  general: 'General',
  admin: 'User Management',
  ingestion: 'Ingestion',
}

/**
 * One entry point for every settings screen: account preferences, user administration, and
 * ingestion control, previously three separate nav items and three separate full-page routes.
 *
 * It stays a dialog rather than a route on purpose — none of it is content worth bookmarking or
 * sharing, and the previous three pages each needed their own "Back to chat" link just to undo the
 * navigation away from the conversation the reader was actually having.
 *
 * Section visibility mirrors exactly what the old pages gated on: General is unconditional, Admin
 * needs `canManageUsers` (a full admin or a read-only one), Ingestion needs `canIngest` (a full
 * admin or an ingestor). Nothing here loosens or tightens who can act on what — the mutations
 * inside each panel are unchanged, and the API enforces the same roles regardless of how the UI
 * that reaches them is organised.
 */
export default function SettingsDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { canManageUsers, canIngest } = useAuth()

  const sections: Section[] = [
    'general',
    ...(canManageUsers ? (['admin'] as const) : []),
    ...(canIngest ? (['ingestion'] as const) : []),
  ]

  const [section, setSection] = useState<Section>('general')
  const activeSection = sections.includes(section) ? section : sections[0]

  return (
    <Modal open={open} onClose={onClose} title="Settings" size="lg" fixedHeight>
      <div className="flex min-h-0 flex-1 flex-col sm:flex-row">
        <nav
          aria-label="Settings sections"
          role="tablist"
          className="flex shrink-0 gap-1 overflow-x-auto border-b border-border p-2 sm:w-40 sm:flex-col sm:border-b-0 sm:border-r sm:overflow-visible"
        >
          {sections.map(name => (
            <button
              key={name}
              type="button"
              role="tab"
              aria-selected={activeSection === name}
              onClick={() => setSection(name)}
              className={cn(
                'shrink-0 rounded-lg px-3 py-2 text-left text-sm font-medium transition-colors',
                activeSection === name
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:bg-surface-hover',
              )}
            >
              {SECTION_LABEL[name]}
            </button>
          ))}
        </nav>

        {/*
          `scrollbar-gutter: stable` reserves the scrollbar's track up front instead of only
          when content overflows. Without it, a panel that starts short (a loading skeleton,
          say) and then grows past the fold — General does this the moment preferences finish
          fetching — pops a scrollbar into existence and the content it sits beside visibly
          shifts left by its width. Reserving the gutter always keeps that width constant.
        */}
        <div
          role="tabpanel"
          className="min-h-0 flex-1 overflow-y-auto p-5 [scrollbar-gutter:stable]"
        >
          {activeSection === 'admin' ? (
            <AdminUsersPanel />
          ) : activeSection === 'ingestion' ? (
            <AdminIngestionPanel />
          ) : (
            <GeneralSettingsPanel />
          )}
        </div>
      </div>
    </Modal>
  )
}
