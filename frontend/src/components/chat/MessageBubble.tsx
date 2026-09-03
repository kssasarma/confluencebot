import { memo, useState } from 'react'
import { AlertCircle, Check, Copy, RotateCcw, ThumbsDown, ThumbsUp } from 'lucide-react'
import { cn } from '../../lib/cn'
import { absoluteTime, relativeTime } from '../../lib/time'
import type { Message } from '../../types'
import Button from '../ui/Button'
import IconButton from '../ui/IconButton'
import LazyMarkdown from './LazyMarkdown'
import SourcesPanel from './SourcesPanel'
import ConfidenceBadge, { UnknownConfidenceBadge } from './ConfidenceBadge'
import FollowUps from './FollowUps'

interface MessageBubbleProps {
  message: Message
  /** Effective preference — account default, overridden per conversation. */
  showSources: boolean
  showConfidence: boolean
  onAsk: (question: string) => void
  onRetry: () => void
  isStreaming: boolean
}

/**
 * One turn of the conversation.
 *
 * The answer is a structured object rather than a blob of markdown: prose, the pages behind it,
 * how well those matched, what to ask next, and — when something went wrong — what and whether it
 * can be retried. Each of those is rendered where it belongs, attached to this message.
 */
function MessageBubble({
  message, showSources, showConfidence, onAsk, onRetry, isStreaming,
}: MessageBubbleProps) {
  const [copied, setCopied] = useState(false)
  const [vote, setVote] = useState<'up' | 'down' | null>(null)
  const [focusedPageId, setFocusedPageId] = useState<string | null>(null)

  const isUser = message.role === 'user'
  const hasContent = message.content.length > 0

  async function copy() {
    try {
      await navigator.clipboard.writeText(message.content)
      setCopied(true)
      setTimeout(() => setCopied(false), 1600)
    } catch {
      /* Clipboard access is unavailable outside a secure context; the text is selectable. */
    }
  }

  return (
    <article
      className={cn('flex gap-3 py-3', isUser && 'flex-row-reverse')}
      aria-label={isUser ? 'Your message' : 'Assistant answer'}
    >
      <div
        aria-hidden="true"
        className={cn(
          'flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-2xs font-semibold',
          isUser ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground',
        )}
      >
        {isUser ? 'You' : 'AI'}
      </div>

      <div className={cn('flex min-w-0 max-w-[80%] flex-col gap-1', isUser && 'items-end')}>
        <div
          className={cn(
            'break-words rounded-2xl px-4 py-2.5 text-sm leading-relaxed',
            isUser
              ? 'rounded-tr-sm bg-primary text-primary-foreground'
              : 'rounded-tl-sm border border-border bg-surface text-foreground',
          )}
        >
          {isUser ? (
            <p className="whitespace-pre-wrap">{message.content}</p>
          ) : hasContent ? (
            <>
              <LazyMarkdown
                content={message.content}
                citations={message.citations}
                sources={message.sources}
                streaming={message.streaming}
                onFocusSource={pageId => setFocusedPageId(pageId ?? null)}
              />
              {message.streaming && (
                <span
                  aria-hidden="true"
                  className="ml-0.5 inline-block h-3.5 w-1 animate-pulse-soft rounded-sm bg-current align-text-bottom"
                />
              )}
            </>
          ) : message.streaming ? (
            <span className="flex items-center gap-2 text-muted-foreground">
              <span
                aria-hidden="true"
                className="h-3.5 w-1 animate-pulse-soft rounded-sm bg-current"
              />
              <span className="text-2xs">Searching your Confluence pages…</span>
            </span>
          ) : null}
        </div>

        {message.stopped && (
          <p className="px-1 text-2xs text-muted-foreground">
            Stopped — this answer was not saved.
          </p>
        )}

        {/*
          The failure is a strip under the partial answer, not a separate bubble. A stream that
          died halfway is one damaged answer; appending a second red message reads as a good
          answer followed by a bad one.
        */}
        {message.error && (
          <div
            role="alert"
            className="flex w-full items-start gap-2 rounded-lg border border-danger/40 bg-danger-soft px-3 py-2"
          >
            <AlertCircle
              size={14}
              aria-hidden="true"
              className="mt-0.5 shrink-0 text-danger-emphasis"
            />
            <p className="min-w-0 flex-1 text-2xs text-danger-emphasis">{message.error.message}</p>
            {message.error.retryable && (
              <Button size="sm" variant="secondary" onClick={onRetry} disabled={isStreaming}>
                <RotateCcw size={12} aria-hidden="true" />
                Retry
              </Button>
            )}
          </div>
        )}

        {!isUser && !message.streaming && hasContent && !message.error && (
          <>
            <div className="flex flex-wrap items-center gap-1.5 px-1">
              {showConfidence && (
                message.confidence != null
                  ? <ConfidenceBadge confidence={message.confidence} />
                  : <UnknownConfidenceBadge />
              )}

              <span className="text-2xs text-muted-foreground" title={absoluteTime(message.createdAt)}>
                {relativeTime(message.createdAt)}
              </span>

              <span className="flex items-center gap-0.5">
                <IconButton
                  size="sm"
                  label={copied ? 'Copied' : 'Copy answer'}
                  icon={copied ? <Check size={13} /> : <Copy size={13} />}
                  onClick={copy}
                />
                <IconButton
                  size="sm"
                  label="This answer was helpful"
                  icon={<ThumbsUp size={13} />}
                  active={vote === 'up'}
                  onClick={() => setVote(current => (current === 'up' ? null : 'up'))}
                />
                <IconButton
                  size="sm"
                  label="This answer was not helpful"
                  icon={<ThumbsDown size={13} />}
                  active={vote === 'down'}
                  onClick={() => setVote(current => (current === 'down' ? null : 'down'))}
                />
                <IconButton
                  size="sm"
                  label="Regenerate this answer"
                  icon={<RotateCcw size={13} />}
                  onClick={onRetry}
                  disabled={isStreaming}
                />
              </span>
            </div>

            {showSources && message.sources && (
              <SourcesPanel sources={message.sources} focusedPageId={focusedPageId} />
            )}

            {message.followUpQuestions && (
              <FollowUps
                questions={message.followUpQuestions}
                onAsk={onAsk}
                disabled={isStreaming}
              />
            )}
          </>
        )}
      </div>
    </article>
  )
}

/**
 * A message re-renders only when its own data changes.
 *
 * Without this, every streamed token re-renders every message in the transcript — which is
 * quadratic in the length of the conversation and is what makes a long answer stutter.
 */
export default memo(MessageBubble)
