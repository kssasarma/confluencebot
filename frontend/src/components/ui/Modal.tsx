import { Fragment, type ReactNode } from 'react'
import {
  Description, Dialog, DialogPanel, DialogTitle, Transition, TransitionChild,
} from '@headlessui/react'
import { X } from 'lucide-react'
import { cn } from '../../lib/cn'
import IconButton from './IconButton'

/**
 * The one modal in the application.
 *
 * Built on Headless UI's `Dialog` rather than a `fixed inset-0` div, because the div version is
 * missing everything that makes a dialog a dialog and each omission is invisible to a mouse user:
 * a focus trap, Escape, restoring focus to the trigger, `aria-modal`, a labelled title, a portal
 * that escapes ancestor `overflow` and stacking contexts, and a scroll lock on the page behind.
 *
 * Headless UI's own transitions are used here rather than `framer-motion`. Animating a portal that
 * `Dialog` also controls the unmount of means two libraries racing to decide when the node leaves
 * the tree, and the loser leaves a dead scroll lock behind.
 */

export interface ModalProps {
  open: boolean
  onClose: () => void
  title: string

  /** Optional line under the title. Announced as the dialog's description. */
  description?: string

  /** `md` for a form, `sm` for a confirmation, `lg` for something with a table in it. */
  size?: 'sm' | 'md' | 'lg'

  /**
   * Set false for a dialog the user must answer — a destructive confirmation, say. Escape and the
   * backdrop stop closing, and the close button disappears, so there is no affordance that
   * silently does nothing.
   */
  dismissable?: boolean

  children: ReactNode
  footer?: ReactNode
}

const SIZES = {
  sm: 'max-w-sm',
  md: 'max-w-lg',
  lg: 'max-w-3xl',
} as const

export default function Modal({
  open, onClose, title, description, size = 'md', dismissable = true, children, footer,
}: ModalProps) {
  return (
    <Transition show={open} as={Fragment}>
      <Dialog onClose={dismissable ? onClose : () => {}} className="relative z-modal">
        <TransitionChild
          as={Fragment}
          enter="duration-fast ease-out-expo" enterFrom="opacity-0" enterTo="opacity-100"
          leave="duration-fast ease-out-expo" leaveFrom="opacity-100" leaveTo="opacity-0"
        >
          <div className="fixed inset-0 bg-foreground/40 backdrop-blur-[2px]" aria-hidden="true" />
        </TransitionChild>

        <div className="fixed inset-0 overflow-y-auto">
          <div className="flex min-h-full items-end justify-center p-4 sm:items-center">
            <TransitionChild
              as={Fragment}
              enter="duration-base ease-out-expo"
              enterFrom="opacity-0 translate-y-2 sm:scale-95 sm:translate-y-0"
              enterTo="opacity-100 translate-y-0 sm:scale-100"
              leave="duration-fast ease-out-expo"
              leaveFrom="opacity-100 translate-y-0 sm:scale-100"
              leaveTo="opacity-0 translate-y-2 sm:scale-95 sm:translate-y-0"
            >
              <DialogPanel
                className={cn(
                  'w-full rounded-2xl border border-border bg-surface shadow-overlay',
                  'flex max-h-[85vh] flex-col overflow-hidden',
                  SIZES[size],
                )}
              >
                <div className="flex items-start justify-between gap-4 border-b border-border px-5 py-4">
                  <div className="min-w-0">
                    <DialogTitle className="truncate font-semibold text-foreground">
                      {title}
                    </DialogTitle>
                    {/*
                      `Description` rather than a paragraph with a hand-written id: Headless UI
                      owns which element carries role="dialog", so wiring aria-describedby by
                      hand points at the wrong node and the description is never announced.
                    */}
                    {description && (
                      <Description className="mt-0.5 text-sm text-muted-foreground">
                        {description}
                      </Description>
                    )}
                  </div>
                  {dismissable && (
                    <IconButton
                      label="Close dialog"
                      icon={<X size={16} />}
                      onClick={onClose}
                      className="-mr-1 shrink-0"
                    />
                  )}
                </div>

                {children}

                {footer && (
                  <div className="flex shrink-0 justify-end gap-2 border-t border-border px-5 py-4">
                    {footer}
                  </div>
                )}
              </DialogPanel>
            </TransitionChild>
          </div>
        </div>
      </Dialog>
    </Transition>
  )
}

/** The scrolling region of a modal. Keeps the header and footer pinned. */
export function ModalBody({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={cn('min-h-0 flex-1 overflow-y-auto px-5 py-5', className)}>
      {children}
    </div>
  )
}
