import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react'
import Modal, { ModalBody } from './Modal'
import Button from './Button'

/**
 * `window.confirm`, replaced by something that matches the rest of the application.
 *
 * The browser dialog is unstyleable, blocks the event loop, is suppressible by the user in a way
 * the page cannot detect, and is announced by screen readers as a page-level interruption. It is
 * also synchronous, which is why call sites that use it tend to grow a tangle of boolean state.
 *
 * This keeps the ergonomics that made `confirm` popular — one `await`, no state to hold — and
 * gives up none of the accessibility of a real dialog:
 *
 * ```ts
 * const confirm = useConfirm()
 * if (await confirm({ title: 'Delete this conversation?', tone: 'danger' })) remove()
 * ```
 */

export interface ConfirmOptions {
  title: string
  description?: string
  confirmLabel?: string
  cancelLabel?: string
  /** `danger` for anything that destroys data. */
  tone?: 'primary' | 'danger'
}

type ConfirmFn = (options: ConfirmOptions) => Promise<boolean>

const ConfirmContext = createContext<ConfirmFn | null>(null)

interface PendingConfirm extends ConfirmOptions {
  resolve: (confirmed: boolean) => void
}

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<PendingConfirm | null>(null)

  // Held in a ref as well as in state so that settling can never race a re-render: whichever of
  // confirm, cancel or dismiss happens first resolves the promise exactly once.
  const pendingRef = useRef<PendingConfirm | null>(null)

  const settle = useCallback((confirmed: boolean) => {
    const current = pendingRef.current
    pendingRef.current = null
    setPending(null)
    current?.resolve(confirmed)
  }, [])

  const confirm = useCallback<ConfirmFn>(options => new Promise<boolean>(resolve => {
    // A second request while one is open would strand the first promise forever. Declining it is
    // the honest answer: the caller learns the user did not confirm.
    if (pendingRef.current) {
      resolve(false)
      return
    }
    const next = { ...options, resolve }
    pendingRef.current = next
    setPending(next)
  }), [])

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      <Modal
        open={pending !== null}
        onClose={() => settle(false)}
        title={pending?.title ?? ''}
        size="sm"
        footer={
          <>
            <Button variant="secondary" onClick={() => settle(false)}>
              {pending?.cancelLabel ?? 'Cancel'}
            </Button>
            <Button
              variant={pending?.tone === 'danger' ? 'danger' : 'primary'}
              onClick={() => settle(true)}
              autoFocus
            >
              {pending?.confirmLabel ?? 'Confirm'}
            </Button>
          </>
        }
      >
        <ModalBody>
          <p className="text-sm text-muted-foreground">
            {pending?.description ?? 'This cannot be undone.'}
          </p>
        </ModalBody>
      </Modal>
    </ConfirmContext.Provider>
  )
}

export function useConfirm(): ConfirmFn {
  const confirm = useContext(ConfirmContext)
  if (!confirm) throw new Error('useConfirm must be used inside ConfirmProvider')
  return confirm
}
