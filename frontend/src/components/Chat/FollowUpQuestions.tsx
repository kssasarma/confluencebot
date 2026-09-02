import { CornerDownRight } from 'lucide-react'

interface FollowUpQuestionsProps {
  questions: string[]
  onSelect: (question: string) => void
}

/** Suggestions the model proposed after its last answer. */
export default function FollowUpQuestions({ questions, onSelect }: FollowUpQuestionsProps) {
  return (
    <div className="mt-2 mb-4 pl-10">
      <p className="text-xs font-medium text-muted-foreground mb-1.5">Suggested next</p>
      <div className="flex flex-col items-start gap-1.5">
        {questions.map(question => (
          <button
            key={question}
            onClick={() => onSelect(question)}
            className="inline-flex items-start gap-2 text-left text-sm text-foreground border border-border
                       rounded-xl px-3 py-1.5 bg-surface hover:bg-surface-hover transition-colors"
          >
            <CornerDownRight size={14} className="mt-0.5 flex-shrink-0 text-muted-foreground" />
            <span>{question}</span>
          </button>
        ))}
      </div>
    </div>
  )
}
