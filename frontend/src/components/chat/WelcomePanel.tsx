/**
 * What an empty conversation shows, in two halves.
 *
 * Two components rather than one because the composer belongs between them. A conversation with
 * nothing in it has one thing to offer and it is the question box, so the box is the middle of
 * the screen — greeted from above, prompted from below — rather than a bar on the bottom edge
 * with a screen of empty space between it and everything that explains it. The route places all
 * three; neither half positions itself.
 */

import { BookOpen, MessageSquare, Search, Zap } from 'lucide-react'

const SUGGESTIONS = [
  { icon: Search, text: 'What is our onboarding process for new engineers?' },
  { icon: BookOpen, text: 'Summarise the architecture decision records for the payment service.' },
  { icon: Zap, text: 'What are the steps to deploy to production?' },
  { icon: MessageSquare, text: 'Find all pages related to incident response procedures.' },
]

/** Above the composer: who is here, and what this box answers from. */
export function WelcomeGreeting({ name }: { name: string }) {
  return (
    <div className="flex min-h-0 animate-fade-in flex-col items-center justify-end overflow-y-auto px-4 pb-5 pt-8 sm:flex-1">
      <div
        aria-hidden="true"
        className="mb-4 flex h-11 w-11 items-center justify-center rounded-2xl bg-primary-soft"
      >
        <MessageSquare className="h-5 w-5 text-primary-emphasis" />
      </div>

      <h2 className="max-w-xl text-balance text-center text-2xl font-semibold tracking-tight text-foreground">
        {name ? (
          <>
            Welcome <span className="text-primary-emphasis">{name}</span>, how may I help you?
          </>
        ) : (
          'Welcome, how may I help you?'
        )}
      </h2>

      <p className="mt-2 max-w-lg text-balance text-center text-sm text-muted-foreground">
        Answers come from your Confluence pages, with a link to every page they drew on.
      </p>
    </div>
  )
}

/**
 * Below the composer: four questions that are one click from being asked.
 *
 * The suggestions send immediately rather than filling the composer. Previously the home
 * suggestions filled the box while the follow-up chips sent straight away — two affordances that
 * looked identical and behaved differently, which is a coin flip the reader has to lose once to
 * learn.
 */
export function WelcomeSuggestions({ onSelect }: { onSelect: (prompt: string) => void }) {
  return (
    <div className="min-h-0 flex-1 animate-fade-in-up overflow-y-auto px-4 pb-6 pt-3">
      <div className="mx-auto w-full max-w-3xl">
        <p
          id="welcome-suggestions-label"
          className="mb-2 text-2xs font-medium uppercase tracking-wider text-muted-foreground"
        >
          Or start with one of these
        </p>

        <ul
          aria-labelledby="welcome-suggestions-label"
          className="grid grid-cols-1 gap-2 sm:grid-cols-2"
        >
          {SUGGESTIONS.map(({ icon: Icon, text }) => (
            <li key={text}>
              <button
                onClick={() => onSelect(text)}
                className="group flex h-full w-full items-start gap-3 rounded-xl border border-border bg-surface p-3 text-left transition-colors hover:bg-surface-hover"
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
    </div>
  )
}
