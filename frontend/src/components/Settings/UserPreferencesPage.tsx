import { useState, useEffect } from 'react'
import { fetchUserPreferences, updateUserPreferences } from '../../services/userPreferenceService'
import { useTheme } from '../../context/ThemeContext'
import type { UserPreferences } from '../../types'
import Button from '../ui/Button'

const RESPONSE_STYLES = [
  { value: 'concise', label: 'Concise', desc: 'Short, direct answers' },
  { value: 'balanced', label: 'Balanced', desc: 'Moderate detail' },
  { value: 'detailed', label: 'Detailed', desc: 'In-depth explanations' },
]

export default function UserPreferencesPage({ onClose }: { onClose: () => void }) {
  const { theme, setTheme } = useTheme()
  const [prefs, setPrefs] = useState<UserPreferences | null>(null)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    fetchUserPreferences().then(setPrefs).catch(() => {})
  }, [])

  async function handleSave() {
    if (!prefs) return
    setSaving(true)
    try {
      const updated = await updateUserPreferences({
        responseStyle: prefs.responseStyle,
        showSources: prefs.showSources,
        showConfidence: prefs.showConfidence,
      })
      setPrefs(updated)
      setSaved(true)
      setTimeout(() => setSaved(false), 2000)
    } catch {}
    finally { setSaving(false) }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4">
      <div className="w-full max-w-md bg-surface border border-border rounded-2xl shadow-lg overflow-hidden">
        <div className="flex items-center justify-between px-5 py-4 border-b border-border">
          <h2 className="font-semibold text-foreground">Settings</h2>
          <button onClick={onClose} className="p-1.5 rounded-md hover:bg-surface-hover text-muted-foreground">
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div className="p-5 space-y-6 overflow-y-auto max-h-[70vh]">
          <section>
            <h3 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-3">Appearance</h3>
            <div className="flex gap-2">
              {(['light', 'dark', 'system'] as const).map(t => (
                <button
                  key={t}
                  onClick={() => setTheme(t)}
                  className={`flex-1 py-2 rounded-lg text-sm capitalize border transition-colors ${
                    theme === t
                      ? 'border-primary bg-primary/10 text-primary font-medium'
                      : 'border-border bg-surface hover:bg-surface-hover text-muted-foreground'
                  }`}
                >
                  {t}
                </button>
              ))}
            </div>
          </section>

          {prefs && (
            <>
              <section>
                <h3 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-3">Response Style</h3>
                <div className="space-y-2">
                  {RESPONSE_STYLES.map(({ value, label, desc }) => (
                    <label key={value} className={`flex items-start gap-3 p-3 rounded-xl border cursor-pointer transition-colors ${
                      prefs.responseStyle === value
                        ? 'border-primary bg-primary/5'
                        : 'border-border hover:bg-surface-hover'
                    }`}>
                      <input
                        type="radio"
                        name="responseStyle"
                        value={value}
                        checked={prefs.responseStyle === value}
                        onChange={() => setPrefs({ ...prefs, responseStyle: value as UserPreferences['responseStyle'] })}
                        className="mt-0.5 accent-primary"
                      />
                      <div>
                        <p className="text-sm font-medium text-foreground">{label}</p>
                        <p className="text-xs text-muted-foreground">{desc}</p>
                      </div>
                    </label>
                  ))}
                </div>
              </section>

              <section>
                <h3 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-3">Display</h3>
                <div className="space-y-3">
                  <Toggle
                    label="Show sources"
                    desc="Display Confluence page links with answers"
                    checked={prefs.showSources}
                    onChange={v => setPrefs({ ...prefs, showSources: v })}
                  />
                  <Toggle
                    label="Show confidence"
                    desc="Display confidence level for each answer"
                    checked={prefs.showConfidence}
                    onChange={v => setPrefs({ ...prefs, showConfidence: v })}
                  />
                </div>
              </section>
            </>
          )}
        </div>

        <div className="flex justify-end gap-2 px-5 py-4 border-t border-border">
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button onClick={handleSave} loading={saving}>
            {saved ? 'Saved!' : 'Save changes'}
          </Button>
        </div>
      </div>
    </div>
  )
}

function Toggle({ label, desc, checked, onChange }: {
  label: string; desc: string; checked: boolean; onChange: (v: boolean) => void
}) {
  return (
    <div className="flex items-center justify-between gap-4">
      <div>
        <p className="text-sm font-medium text-foreground">{label}</p>
        <p className="text-xs text-muted-foreground">{desc}</p>
      </div>
      <button
        role="switch"
        aria-checked={checked}
        onClick={() => onChange(!checked)}
        className={`relative inline-flex w-9 h-5 rounded-full transition-colors flex-shrink-0 ${checked ? 'bg-primary' : 'bg-muted'}`}
      >
        <span className={`inline-block w-3.5 h-3.5 rounded-full bg-white shadow-sm transform transition-transform mt-[3px] ${checked ? 'translate-x-4 ml-0.5' : 'translate-x-0.5'}`} />
      </button>
    </div>
  )
}
