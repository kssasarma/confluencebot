import { CornerDownRight } from 'lucide-react'

interface FollowUpsProps {
  questions: string[]
  onAsk: (question: string) => void
  disabled?: boolean
}

/**
 * The suggestions the model proposed after an answer.
 *
 * Rendered inside the message it belongs to, not as a sibling at the bottom of the transcript.
 * The previous placement showed only the last answer's suggestions and discarded every earlier
 * one — they were fetched, stored, and never displayed — and they vanished the instant the next
 * question was asked.
 */
export default function FollowUps({ questions, onAsk, disabled }: FollowUpsProps) {
  if (questions.length === 0) return null

  return (
    <nav aria-label="Suggested follow-up questions" className="mt-3">
      <p className="mb-1.5 text-2xs font-medium text-muted-foreground">Suggested next</p>
      <ul className="flex flex-col items-start gap-1.5">
        {questions.map(question => (
          <li key={question}>
            <button
              onClick={() => onAsk(question)}
              disabled={disabled}
              className="inline-flex items-start gap-2 rounded-xl border border-border bg-surface px-3 py-1.5 text-left text-sm text-foreground transition-colors hover:bg-surface-hover disabled:opacity-50"
            >
              <CornerDownRight
                size={14}
                aria-hidden="true"
                className="mt-0.5 shrink-0 text-muted-foreground"
              />
              <span>{question}</span>
            </button>
          </li>
        ))}
      </ul>
    </nav>
  )
}
