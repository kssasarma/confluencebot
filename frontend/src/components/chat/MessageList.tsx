import { ArrowDown } from 'lucide-react'
import { cn } from '../../lib/cn'
import type { Message } from '../../types'
import { useAutoScroll } from '../../hooks/useAutoScroll'
import { useEventCallback } from '../../hooks/useEventCallback'
import MessageBubble from './MessageBubble'

interface MessageListProps {
  messages: Message[]
  showSources: boolean
  showConfidence: boolean
  isStreaming: boolean
  onAsk: (question: string) => void
  onRetry: () => void
}

/**
 * The transcript.
 *
 * Not virtualised, deliberately. Windowing a list whose last item changes height on every frame
 * means the measurement cache is invalid every frame, and the scroll anchor fights the streaming
 * content — a well-known source of jumping. `content-visibility: auto` gets most of the benefit
 * (the browser skips layout and paint for off-screen messages) with none of that risk, and
 * `React.memo` on the rows stops the re-render cost from growing with the conversation.
 */
export default function MessageList({
  messages, showSources, showConfidence, isStreaming, onAsk, onRetry,
}: MessageListProps) {
  // Keyed on the streamed text so that following the answer down is driven by content arriving,
  // not by a re-render of the list for some unrelated reason.
  // Stable identities, so a parent that recreates its handlers each render cannot defeat the
  // memoisation on the rows.
  const ask = useEventCallback(onAsk)
  const retry = useEventCallback(onRetry)

  const streamedLength = messages[messages.length - 1]?.content.length ?? 0
  const { containerRef, isPinnedToBottom, scrollToBottom } =
    useAutoScroll<HTMLDivElement>(streamedLength)

  const streamingText = isStreaming
    ? messages.findLast(message => message.role === 'assistant')?.content ?? ''
    : ''

  return (
    <div className="relative min-h-0 flex-1">
      <div ref={containerRef} className="h-full overflow-y-auto px-4">
        <div className="mx-auto max-w-3xl py-4">
          {messages.map(message => (
            <div key={message.id} className="content-auto">
              <MessageBubble
                message={message}
                showSources={showSources}
                showConfidence={showConfidence}
                isStreaming={isStreaming}
                onAsk={ask}
                onRetry={retry}
              />
            </div>
          ))}
        </div>
      </div>

      {/*
        A screen reader gets no indication that an answer is arriving otherwise. The text is
        mirrored into a live region rather than the region wrapping the transcript itself: marking
        the whole transcript live would re-announce every message on each token.
      */}
      <div aria-live="polite" aria-atomic="false" className="sr-only">
        {streamingText}
      </div>

      {!isPinnedToBottom && (
        <button
          onClick={() => scrollToBottom()}
          className={cn(
            'absolute bottom-4 left-1/2 flex -translate-x-1/2 items-center gap-1.5 rounded-full',
            'border border-border bg-surface px-3 py-1.5 text-2xs text-foreground shadow-raised',
            'animate-fade-in-up transition-colors hover:bg-surface-hover',
          )}
        >
          <ArrowDown size={12} aria-hidden="true" />
          Jump to latest
        </button>
      )}
    </div>
  )
}
