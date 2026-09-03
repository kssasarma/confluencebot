import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowLeft, Check } from 'lucide-react'
import type { ResponseStyle, UserPreferences } from '../types'
import { useUserPreferences } from '../hooks/usePreferences'
import { useTheme } from '../context/ThemeContext'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import Button from '../components/ui/Button'
import EmptyState from '../components/ui/EmptyState'
import Switch from '../components/ui/Switch'
import { SkeletonText } from '../components/ui/Skeleton'
import { cn } from '../lib/cn'

const STYLES: Array<{ value: ResponseStyle; label: string; description: string }> = [
  { value: 'concise', label: 'Concise', description: 'Short, direct answers' },
  { value: 'balanced', label: 'Balanced', description: 'Moderate detail' },
  { value: 'detailed', label: 'Detailed', description: 'In-depth explanations' },
]

/**
 * Account-wide preferences, as a page rather than an overlay.
 *
 * Settings have a URL now: they can be linked to, the browser's Back button leaves them, and the
 * page behind them is not a modal that had to be dismissed to see anything else.
 */
export default function SettingsRoute() {
  const { theme, setTheme } = useTheme()
  const { preferences, isLoading, error, save, isSaving } = useUserPreferences()
  const [draft, setDraft] = useState<UserPreferences | null>(null)
  const [justSaved, setJustSaved] = useState(false)

  useDocumentTitle('Settings')

  useEffect(() => { if (!isLoading && !error) setDraft(preferences) }, [isLoading, error, preferences])

  function handleSave() {
    if (!draft) return
    save({
      responseStyle: draft.responseStyle,
      showSources: draft.showSources,
      showConfidence: draft.showConfidence,
    })
    setJustSaved(true)
    setTimeout(() => setJustSaved(false), 2000)
  }

  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto max-w-2xl px-4 py-8">
        <Link
          to="/"
          className="mb-6 inline-flex items-center gap-1.5 rounded text-2xs text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft size={13} aria-hidden="true" />
          Back to chat
        </Link>

        <h1 className="mb-8 text-xl font-semibold text-foreground">Settings</h1>

        <section className="mb-8">
          <h2 className="mb-3 text-2xs font-semibold uppercase tracking-wider text-muted-foreground">
            Appearance
          </h2>
          <div role="radiogroup" aria-label="Theme" className="flex gap-2">
            {(['light', 'dark', 'system'] as const).map(option => (
              <button
                key={option}
                role="radio"
                aria-checked={theme === option}
                onClick={() => setTheme(option)}
                className={cn(
                  'flex-1 rounded-lg border py-2 text-sm capitalize transition-colors',
                  theme === option
                    ? 'border-primary bg-primary-soft font-medium text-primary-emphasis'
                    : 'border-border bg-surface text-muted-foreground hover:bg-surface-hover',
                )}
              >
                {option}
              </button>
            ))}
          </div>
        </section>

        {isLoading ? (
          <SkeletonText lines={8} />
        ) : error ? (
          <EmptyState
            tone="error"
            title="Could not load your preferences"
            description={error}
            action={
              <Button variant="secondary" onClick={() => window.location.reload()}>Reload</Button>
            }
          />
        ) : draft && (
          <>
            <section className="mb-8">
              <h2 className="mb-3 text-2xs font-semibold uppercase tracking-wider text-muted-foreground">
                Response style
              </h2>
              <div className="space-y-2">
                {STYLES.map(({ value, label, description }) => (
                  <label
                    key={value}
                    className={cn(
                      'flex cursor-pointer items-start gap-3 rounded-xl border p-3 transition-colors',
                      draft.responseStyle === value
                        ? 'border-primary bg-primary-soft'
                        : 'border-border hover:bg-surface-hover',
                    )}
                  >
                    <input
                      type="radio"
                      name="responseStyle"
                      value={value}
                      checked={draft.responseStyle === value}
                      onChange={() => setDraft({ ...draft, responseStyle: value })}
                      className="mt-0.5 accent-primary"
                    />
                    <span>
                      <span className="block text-sm font-medium text-foreground">{label}</span>
                      <span className="block text-2xs text-muted-foreground">{description}</span>
                    </span>
                  </label>
                ))}
              </div>
            </section>

            <section className="mb-8 space-y-4">
              <h2 className="text-2xs font-semibold uppercase tracking-wider text-muted-foreground">
                Display
              </h2>
              <Switch
                label="Show sources"
                description="List the Confluence pages behind every answer"
                checked={draft.showSources}
                onChange={value => setDraft({ ...draft, showSources: value })}
              />
              <Switch
                label="Show match strength"
                description="How well your question matched the indexed pages. It does not verify the answer."
                checked={draft.showConfidence}
                onChange={value => setDraft({ ...draft, showConfidence: value })}
              />
            </section>

            <div className="flex items-center gap-3">
              <Button onClick={handleSave} loading={isSaving}>Save changes</Button>
              {justSaved && (
                <span role="status" className="flex items-center gap-1 text-2xs text-success-emphasis">
                  <Check size={13} aria-hidden="true" />
                  Saved
                </span>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  )
}
