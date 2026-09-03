import { BookOpen, MessageSquare, Search, Zap } from 'lucide-react'

const SUGGESTIONS = [
  { icon: Search, text: 'What is our onboarding process for new engineers?' },
  { icon: BookOpen, text: 'Summarise the architecture decision records for the payment service.' },
  { icon: Zap, text: 'What are the steps to deploy to production?' },
  { icon: MessageSquare, text: 'Find all pages related to incident response procedures.' },
]

/**
 * What an empty conversation shows.
 *
 * The suggestions send immediately rather than filling the composer. Previously the home
 * suggestions filled the box while the follow-up chips sent straight away — two affordances that
 * looked identical and behaved differently, which is a coin flip the reader has to lose once to
 * learn.
 */
export default function WelcomePanel({ onSelect }: { onSelect: (prompt: string) => void }) {
  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center px-4 py-12">
      <div
        aria-hidden="true"
        className="mb-5 flex h-12 w-12 items-center justify-center rounded-xl bg-primary-soft"
      >
        <MessageSquare className="h-6 w-6 text-primary-emphasis" />
      </div>

      <h2 className="mb-1 text-xl font-semibold text-foreground">Ask your documentation</h2>
      <p className="mb-8 max-w-sm text-center text-sm text-muted-foreground">
        Questions are answered from your Confluence pages, with a link to every page the answer
        drew on.
      </p>

      <ul className="grid w-full max-w-lg grid-cols-1 gap-3 sm:grid-cols-2">
        {SUGGESTIONS.map(({ icon: Icon, text }) => (
          <li key={text}>
            <button
              onClick={() => onSelect(text)}
              className="group flex h-full w-full items-start gap-3 rounded-xl border border-border bg-surface p-3.5 text-left transition-colors hover:bg-surface-hover"
            >
              <span
                aria-hidden="true"
                className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-muted"
              >
                <Icon size={14} className="text-muted-foreground transition-colors group-hover:text-primary-emphasis" />
              </span>
              <span className="text-sm leading-snug text-foreground">{text}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
