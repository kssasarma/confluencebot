import { MessageSquare, BookOpen, Search, Zap } from 'lucide-react'

const SUGGESTIONS = [
  { icon: Search, text: 'What is our onboarding process for new engineers?' },
  { icon: BookOpen, text: 'Summarize the architecture decision records for the payment service.' },
  { icon: Zap, text: 'What are the steps to deploy to production?' },
  { icon: MessageSquare, text: 'Find all pages related to incident response procedures.' },
]

interface HomeScreenProps {
  onSelectPrompt: (prompt: string) => void
  /** True when an empty conversation is already open, so the copy stops saying "start one". */
  isNewChat?: boolean
}

export default function HomeScreen({ onSelectPrompt, isNewChat = false }: HomeScreenProps) {
  return (
    <div className="flex flex-col items-center justify-center min-h-[60vh] px-4 py-12">
      <div className="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center mb-5">
        <svg className="w-6 h-6 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z" />
        </svg>
      </div>
      <h2 className="text-xl font-semibold text-foreground mb-1">
        {isNewChat ? 'New chat' : 'Confluence Bot'}
      </h2>
      <p className="text-sm text-muted-foreground mb-8 text-center max-w-xs">
        Ask a question about your Confluence workspace. I'll search the docs and answer with citations.
      </p>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 w-full max-w-lg">
        {SUGGESTIONS.map(({ icon: Icon, text }) => (
          <button
            key={text}
            onClick={() => onSelectPrompt(text)}
            className="flex items-start gap-3 p-3.5 rounded-xl border border-border bg-surface hover:bg-surface-hover text-left transition-colors group"
          >
            <div className="flex-shrink-0 w-7 h-7 rounded-lg bg-muted flex items-center justify-center mt-0.5">
              <Icon size={14} className="text-muted-foreground group-hover:text-primary transition-colors" />
            </div>
            <span className="text-sm text-foreground leading-snug">{text}</span>
          </button>
        ))}
      </div>
    </div>
  )
}
