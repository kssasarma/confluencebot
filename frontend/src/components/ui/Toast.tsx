import {
  createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode,
} from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { AlertTriangle, CheckCircle2, Info, X, XCircle } from 'lucide-react'
import { cn } from '../../lib/cn'
import IconButton from './IconButton'

/**
 * The application's only way of reporting something that happened in the background.
 *
 * Its absence is why every failing preference save in this codebase was written as `catch {}`:
 * with nowhere to put the message, swallowing it was the path of least resistance, and the UI
 * ended up claiming success it had not achieved. A toast layer is the prerequisite for deleting
 * those.
 *
 * Announcements go through a live region rather than the toast nodes themselves. A screen reader
 * ignores an `aria-live` element that is inserted already-populated, which is exactly how a toast
 * arrives, so the text is copied into a region that was mounted from the start.
 */

export type ToastTone = 'success' | 'error' | 'warning' | 'info'

export interface ToastOptions {
  title: string
  description?: string
  tone?: ToastTone
  /** Milliseconds on screen. Pass 0 to require a dismissal. */
  duration?: number
  action?: { label: string; onClick: () => void }
}

interface Toast extends ToastOptions {
  id: number
  tone: ToastTone
}

interface ToastApi {
  show: (options: ToastOptions) => void
  success: (title: string, description?: string) => void
  /** Reports a failure. Errors default to staying until dismissed. */
  error: (title: string, description?: string) => void
  dismiss: (id: number) => void
}

const ToastContext = createContext<ToastApi | null>(null)

const DEFAULT_DURATION = 5000

const TONE = {
  success: { icon: CheckCircle2, className: 'text-success-emphasis' },
  error: { icon: XCircle, className: 'text-danger-emphasis' },
  warning: { icon: AlertTriangle, className: 'text-warning-emphasis' },
  info: { icon: Info, className: 'text-info-emphasis' },
} as const

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const [announcement, setAnnouncement] = useState('')
  const nextId = useRef(1)
  const timers = useRef(new Map<number, ReturnType<typeof setTimeout>>())

  const dismiss = useCallback((id: number) => {
    const timer = timers.current.get(id)
    if (timer) {
      clearTimeout(timer)
      timers.current.delete(id)
    }
    setToasts(current => current.filter(toast => toast.id !== id))
  }, [])

  const show = useCallback((options: ToastOptions) => {
    const tone = options.tone ?? 'info'
    const id = nextId.current++
    const toast: Toast = { ...options, id, tone }

    setToasts(current => [...current, toast])
    setAnnouncement([options.title, options.description].filter(Boolean).join('. '))

    // A failure the user has not read yet must not disappear on its own; anything else does.
    const duration = options.duration ?? (tone === 'error' ? 0 : DEFAULT_DURATION)
    if (duration > 0) {
      timers.current.set(id, setTimeout(() => dismiss(id), duration))
    }
  }, [dismiss])

  useEffect(() => {
    const pending = timers.current
    return () => {
      pending.forEach(clearTimeout)
      pending.clear()
    }
  }, [])

  const api = useMemo<ToastApi>(() => ({
    show,
    dismiss,
    success: (title, description) => show({ title, description, tone: 'success' }),
    error: (title, description) => show({ title, description, tone: 'error' }),
  }), [show, dismiss])

  return (
    <ToastContext.Provider value={api}>
      {children}

      {/* Mounted empty and populated afterwards, which is what makes it audible. */}
      <div aria-live="polite" aria-atomic="true" className="sr-only">{announcement}</div>

      <div
        className="pointer-events-none fixed inset-x-0 bottom-0 z-toast flex flex-col items-center gap-2 p-4 sm:items-end"
      >
        <AnimatePresence initial={false}>
          {toasts.map(toast => (
            <ToastCard key={toast.id} toast={toast} onDismiss={() => dismiss(toast.id)} />
          ))}
        </AnimatePresence>
      </div>
    </ToastContext.Provider>
  )
}

function ToastCard({ toast, onDismiss }: { toast: Toast; onDismiss: () => void }) {
  const { icon: Icon, className } = TONE[toast.tone]

  return (
    <motion.div
      layout
      initial={{ opacity: 0, y: 12, scale: 0.98 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      exit={{ opacity: 0, scale: 0.98, transition: { duration: 0.12 } }}
      transition={{ type: 'spring', stiffness: 480, damping: 34 }}
      className={cn(
        'pointer-events-auto flex w-full max-w-sm items-start gap-3 rounded-xl border',
        'border-border bg-surface p-3.5 shadow-overlay',
      )}
    >
      <Icon size={18} className={cn('mt-0.5 shrink-0', className)} aria-hidden="true" />

      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-foreground">{toast.title}</p>
        {toast.description && (
          <p className="mt-0.5 break-words text-sm text-muted-foreground">{toast.description}</p>
        )}
        {toast.action && (
          <button
            onClick={() => { toast.action?.onClick(); onDismiss() }}
            className="mt-2 rounded text-sm font-medium text-primary-emphasis hover:underline"
          >
            {toast.action.label}
          </button>
        )}
      </div>

      <IconButton label="Dismiss" icon={<X size={14} />} size="sm" onClick={onDismiss} className="-mr-1 -mt-1" />
    </motion.div>
  )
}

export function useToast(): ToastApi {
  const api = useContext(ToastContext)
  if (!api) throw new Error('useToast must be used inside ToastProvider')
  return api
}
