import { useEffect, useState, type FormEvent } from 'react'
import { Check } from 'lucide-react'
import type { ResponseStyle, UserPreferences } from '../../types'
import { useUserPreferences } from '../../hooks/usePreferences'
import { useAuth } from '../../context/AuthContext'
import { useTimedFlag } from '../../hooks/useTimedFlag'
import Button from '../ui/Button'
import EmptyState from '../ui/EmptyState'
import Input from '../ui/Input'
import Switch from '../ui/Switch'
import { SkeletonText } from '../ui/Skeleton'
import { cn } from '../../lib/cn'

/** The user's own name — editable; email is the sign-in identity and never is. */
function ProfileSection() {
  const { user, updateName } = useAuth()
  const [name, setName] = useState(user?.name ?? '')
  const [saving, setSaving] = useState(false)
  const [justSaved, triggerJustSaved] = useTimedFlag()
  const [error, setError] = useState('')

  useEffect(() => { setName(user?.name ?? '') }, [user?.name])

  if (!user) return null

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!name.trim()) { setError('Name cannot be empty'); return }
    setError('')
    setSaving(true)
    try {
      await updateName(name.trim())
      triggerJustSaved()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save your name')
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="mb-8">
      <h2 className="mb-3 text-2xs font-semibold uppercase tracking-wider text-muted-foreground">
        Profile
      </h2>
      <form onSubmit={handleSubmit} className="space-y-3">
        {/* Name first, email second: the name is who they are to everyone else in the app, the
            email is only the sign-in identity underneath it. */}
        <Input label="Name" value={name} onChange={e => setName(e.target.value)} required />
        <Input label="Email" value={user.email} disabled hint="Your sign-in email cannot be changed." />
        {error && <p role="alert" className="text-sm text-danger-emphasis">{error}</p>}
        <div className="flex items-center gap-3">
          <Button type="submit" size="sm" loading={saving} disabled={name.trim() === (user.name ?? '')}>
            Save name
          </Button>
          {justSaved && (
            <span role="status" className="flex items-center gap-1 text-2xs text-success-emphasis">
              <Check size={13} aria-hidden="true" />
              Saved
            </span>
          )}
        </div>
      </form>
    </section>
  )
}

const STYLES: Array<{ value: ResponseStyle; label: string; description: string }> = [
  { value: 'concise', label: 'Concise', description: 'Short, direct answers' },
  { value: 'balanced', label: 'Balanced', description: 'Moderate detail' },
  { value: 'detailed', label: 'Detailed', description: 'In-depth explanations' },
]

/** Account-wide preferences: profile and the defaults every new conversation starts from. */
export default function GeneralSettingsPanel() {
  const { preferences, isLoading, error, save, isSaving } = useUserPreferences()
  const [draft, setDraft] = useState<UserPreferences | null>(null)
  const [justSaved, triggerJustSaved] = useTimedFlag()

  useEffect(() => { if (!isLoading && !error) setDraft(preferences) }, [isLoading, error, preferences])

  function handleSave() {
    if (!draft) return
    save({
      responseStyle: draft.responseStyle,
      showSources: draft.showSources,
      showConfidence: draft.showConfidence,
    })
    triggerJustSaved()
  }

  return (
    <div>
      <ProfileSection />

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
  )
}
