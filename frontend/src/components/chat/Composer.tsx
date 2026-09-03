import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { ArrowUp, Square } from 'lucide-react'
import { cn } from '../../lib/cn'
import { useHotkeys } from '../../hooks/useHotkeys'
import { readDraft, writeDraft } from '../../hooks/usePersistentState'
import IconButton from '../ui/IconButton'

interface ComposerProps {
  chatId: string
  onSend: (question: string) => void
  onStop: () => void
  isStreaming: boolean
  /** Fired on ArrowUp in an empty composer, to bring back the last question for editing. */
  lastQuestion?: string
}

/** Beyond this the composer scrolls rather than eating the transcript. */
const MAX_HEIGHT_RATIO = 0.4
const MAX_HEIGHT_PX = 320
const CHARACTER_LIMIT = 4000
const COUNTER_THRESHOLD = 0.9

/**
 * The question box.
 *
 * Drafts are persisted per conversation, which is the difference between switching chats to check
 * something and losing a paragraph you had just typed.
 */
export default function Composer({
  chatId, onSend, onStop, isStreaming, lastQuestion,
}: ComposerProps) {
  const [value, setValue] = useState(() => readDraft(chatId))
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  // Re-read on a conversation switch: each conversation keeps its own unsent question.
  useEffect(() => setValue(readDraft(chatId)), [chatId])
  useEffect(() => writeDraft(chatId, value), [chatId, value])

  useEffect(() => {
    const element = textareaRef.current
    if (!element) return

    // Reset before measuring: `scrollHeight` on an element already sized to its content only ever
    // grows, so a composer that has been tall never shrinks again.
    element.style.height = 'auto'
    const limit = Math.min(window.innerHeight * MAX_HEIGHT_RATIO, MAX_HEIGHT_PX)
    element.style.height = `${Math.min(element.scrollHeight, limit)}px`
  }, [value])

  useHotkeys([
    // `/` focuses the composer from anywhere — but never while the reader is already typing,
    // which would swallow the slash out of a URL they are pasting.
    { key: '/', handler: event => { event.preventDefault(); textareaRef.current?.focus() } },
    { key: 'escape', allowInInput: true, enabled: isStreaming, handler: onStop },
  ])

  function send() {
    const question = value.trim()
    if (!question || isStreaming) return
    setValue('')
    writeDraft(chatId, '')
    onSend(question)
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      send()
      return
    }
    // The terminal convention: ArrowUp in an empty prompt recalls the last thing you said.
    if (event.key === 'ArrowUp' && value === '' && lastQuestion) {
      event.preventDefault()
      setValue(lastQuestion)
    }
  }

  const remaining = CHARACTER_LIMIT - value.length
  const showCounter = value.length >= CHARACTER_LIMIT * COUNTER_THRESHOLD
  const overLimit = remaining < 0

  return (
    <div className="border-t border-border bg-background px-4 py-3">
      <div className="mx-auto max-w-3xl">
        <div
          className={cn(
            'flex items-end gap-2 rounded-xl border bg-surface px-3 py-2 transition-colors',
            overLimit ? 'border-danger' : 'border-border focus-within:border-primary',
          )}
        >
          <textarea
            ref={textareaRef}
            rows={1}
            value={value}
            onChange={event => setValue(event.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Ask a question about your Confluence pages…"
            aria-label="Ask a question"
            aria-describedby="composer-hint"
            data-focus-ring="none"
            className={cn(
              'min-h-[24px] flex-1 resize-none bg-transparent py-1 text-sm text-foreground',
              'outline-none placeholder:text-muted-foreground',
            )}
          />

          {isStreaming ? (
            <IconButton
              variant="primary"
              label="Stop generating"
              icon={<Square size={13} fill="currentColor" />}
              onClick={onStop}
              className="shrink-0 bg-danger hover:bg-danger-hover"
            />
          ) : (
            <IconButton
              variant="primary"
              label="Send question"
              icon={<ArrowUp size={15} />}
              onClick={send}
              disabled={!value.trim() || overLimit}
              className="shrink-0"
            />
          )}
        </div>

        <div className="mt-1.5 flex items-center justify-between gap-3">
          <p id="composer-hint" className="text-2xs text-muted-foreground">
            Enter to send · Shift + Enter for a new line · / to focus
          </p>
          {showCounter && (
            <p
              className={cn('text-2xs tabular-nums', overLimit ? 'text-danger-emphasis' : 'text-muted-foreground')}
              aria-live="polite"
            >
              {remaining} characters left
            </p>
          )}
        </div>
      </div>
    </div>
  )
}
