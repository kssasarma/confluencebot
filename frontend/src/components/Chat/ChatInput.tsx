import { useRef, type KeyboardEvent } from 'react'
import { Send, Square } from 'lucide-react'
import { cn } from '../../lib/cn'

interface ChatInputProps {
  value: string
  onChange: (v: string) => void
  onSend: () => void
  onStop: () => void
  disabled?: boolean
  streaming?: boolean
}

export default function ChatInput({ value, onChange, onSend, onStop, disabled, streaming }: ChatInputProps) {
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  function handleKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      if (!streaming && value.trim()) onSend()
    }
  }

  function handleInput() {
    const el = textareaRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = Math.min(el.scrollHeight, 200) + 'px'
  }

  return (
    <div className="border-t border-border bg-background px-4 py-3">
      <div className="max-w-3xl mx-auto">
        <div className="flex items-end gap-2 rounded-xl border border-border bg-surface px-3 py-2 focus-within:ring-2 focus-within:ring-primary focus-within:border-primary transition-shadow">
          <textarea
            ref={textareaRef}
            rows={1}
            value={value}
            onChange={e => { onChange(e.target.value); handleInput() }}
            onKeyDown={handleKeyDown}
            disabled={disabled}
            placeholder="Ask a question about your Confluence pages…"
            className={cn(
              'flex-1 resize-none bg-transparent text-sm text-foreground placeholder:text-muted-foreground',
              'outline-none min-h-[24px] max-h-[200px] py-1',
            )}
          />
          {streaming ? (
            <button
              onClick={onStop}
              className="flex-shrink-0 p-1.5 rounded-lg bg-danger text-white hover:bg-danger/90 transition-colors"
              title="Stop"
            >
              <Square size={14} fill="currentColor" />
            </button>
          ) : (
            <button
              onClick={onSend}
              disabled={!value.trim() || disabled}
              className={cn(
                'flex-shrink-0 p-1.5 rounded-lg transition-colors',
                value.trim() && !disabled
                  ? 'bg-primary text-white hover:bg-primary/90'
                  : 'bg-muted text-muted-foreground cursor-not-allowed',
              )}
              title="Send (Enter)"
            >
              <Send size={14} />
            </button>
          )}
        </div>
        <p className="text-xs text-muted-foreground mt-1.5 text-center">
          Shift + Enter for new line
        </p>
      </div>
    </div>
  )
}
