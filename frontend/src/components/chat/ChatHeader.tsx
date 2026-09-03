import { useEffect, useRef, useState, type ReactNode } from 'react'
import { motion } from 'framer-motion'
import { Check, Pencil, X } from 'lucide-react'
import IconButton from '../ui/IconButton'

interface ChatHeaderProps {
  chatId: string
  title: string | null
  /** True while the title is machine-derived and may still be refined by the server. */
  titleGenerated: boolean
  onRename: (title: string) => void
  actions?: ReactNode
}

/**
 * The conversation's title bar, renameable in place.
 *
 * The title arrives twice for a new conversation: immediately as the clipped question, then a
 * second later as a summary. `key={title}` on the motion element makes that a cross-fade rather
 * than a jump, so the swap reads as the title refining itself instead of as a glitch.
 */
export default function ChatHeader({
  chatId, title, titleGenerated, onRename, actions,
}: ChatHeaderProps) {
  const [isEditing, setIsEditing] = useState(false)
  const [draft, setDraft] = useState(title ?? '')
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => { setIsEditing(false) }, [chatId])
  useEffect(() => { if (isEditing) inputRef.current?.select() }, [isEditing])

  function commit() {
    setIsEditing(false)
    const trimmed = draft.trim()
    if (trimmed && trimmed !== title) onRename(trimmed)
  }

  return (
    <header className="flex h-12 shrink-0 items-center gap-2 border-b border-border px-4">
      {isEditing ? (
        <>
          <input
            ref={inputRef}
            value={draft}
            aria-label="Conversation title"
            onChange={event => setDraft(event.target.value)}
            onKeyDown={event => {
              if (event.key === 'Enter') commit()
              if (event.key === 'Escape') { setDraft(title ?? ''); setIsEditing(false) }
            }}
            className="min-w-0 flex-1 rounded-lg bg-surface px-2 py-1 text-sm text-foreground ring-1 ring-primary"
          />
          <IconButton size="sm" label="Save title" icon={<Check size={14} />} onClick={commit} />
          <IconButton
            size="sm"
            label="Cancel renaming"
            icon={<X size={14} />}
            onClick={() => { setDraft(title ?? ''); setIsEditing(false) }}
          />
        </>
      ) : (
        <>
          <motion.h1
            key={title ?? 'untitled'}
            initial={{ opacity: 0.4 }}
            animate={{ opacity: 1 }}
            transition={{ duration: 0.25 }}
            className="min-w-0 flex-1 truncate text-sm font-medium text-foreground"
          >
            {title ?? 'New chat'}
          </motion.h1>

          {title && (
            <IconButton
              size="sm"
              label="Rename conversation"
              icon={<Pencil size={14} />}
              onClick={() => { setDraft(title); setIsEditing(true) }}
            />
          )}
          {titleGenerated && (
            <span className="sr-only">This title was generated and can be renamed.</span>
          )}
        </>
      )}

      {actions}
    </header>
  )
}
