import { useState, useEffect } from 'react'
import { fetchChatPreferences, saveChatPreferences } from '../../services/userPreferenceService'
import type { ChatPreferences } from '../../types'
import Button from '../ui/Button'

interface ChatPreferencesPanelProps {
  chatId: string
  onClose: () => void
}

const STYLES = ['concise', 'balanced', 'detailed'] as const

export default function ChatPreferencesPanel({ chatId, onClose }: ChatPreferencesPanelProps) {
  const [prefs, setPrefs] = useState<ChatPreferences | null>(null)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    fetchChatPreferences(chatId).then(setPrefs).catch(() => {})
  }, [chatId])

  async function handleSave() {
    if (!prefs) return
    setSaving(true)
    try {
      const updated = await saveChatPreferences(chatId, prefs)
      setPrefs(updated)
      setSaved(true)
      setTimeout(() => setSaved(false), 2000)
    } catch {}
    finally { setSaving(false) }
  }

  if (!prefs) return null

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4">
      <div className="w-full max-w-sm bg-surface border border-border rounded-2xl shadow-lg overflow-hidden">
        <div className="flex items-center justify-between px-5 py-4 border-b border-border">
          <h2 className="font-semibold text-foreground text-sm">Chat Preferences</h2>
          <button onClick={onClose} className="p-1.5 rounded-md hover:bg-surface-hover text-muted-foreground">
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div className="p-5 space-y-5">
          <section>
            <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2">Response Style</p>
            <p className="text-xs text-muted-foreground mb-3">Override your default style for this chat.</p>
            <div className="flex gap-2">
              {([null, ...STYLES] as const).map(s => (
                <button
                  key={String(s)}
                  onClick={() => setPrefs({ ...prefs, responseStyle: s })}
                  className={`flex-1 py-1.5 rounded-lg text-xs border transition-colors ${
                    prefs.responseStyle === s
                      ? 'border-primary bg-primary/10 text-primary font-medium'
                      : 'border-border bg-surface hover:bg-surface-hover text-muted-foreground'
                  }`}
                >
                  {s ?? 'Default'}
                </button>
              ))}
            </div>
          </section>

          <section>
            <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-3">Overrides</p>
            <div className="space-y-3">
              <TriToggle
                label="Show sources"
                value={prefs.showSources ?? null}
                onChange={v => setPrefs({ ...prefs, showSources: v })}
              />
              <TriToggle
                label="Show confidence"
                value={prefs.showConfidence ?? null}
                onChange={v => setPrefs({ ...prefs, showConfidence: v })}
              />
            </div>
          </section>

          <section>
            <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2">Custom Prompt</p>
            <textarea
              rows={3}
              value={prefs.customPrompt ?? ''}
              onChange={e => setPrefs({ ...prefs, customPrompt: e.target.value || null })}
              placeholder="Additional instructions for this chat (e.g. 'Always respond in bullet points')…"
              className="w-full text-sm bg-background border border-border rounded-lg px-3 py-2 text-foreground placeholder:text-muted-foreground resize-none focus:outline-none focus:ring-2 focus:ring-primary"
            />
          </section>
        </div>

        <div className="flex justify-end gap-2 px-5 py-4 border-t border-border">
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button onClick={handleSave} loading={saving}>
            {saved ? 'Saved!' : 'Save'}
          </Button>
        </div>
      </div>
    </div>
  )
}

function TriToggle({ label, value, onChange }: {
  label: string; value: boolean | null; onChange: (v: boolean | null) => void
}) {
  const options: Array<{ val: boolean | null; text: string }> = [
    { val: null, text: 'Default' },
    { val: true, text: 'On' },
    { val: false, text: 'Off' },
  ]
  return (
    <div className="flex items-center justify-between gap-4">
      <span className="text-sm text-foreground">{label}</span>
      <div className="flex gap-1">
        {options.map(({ val, text }) => (
          <button
            key={String(val)}
            onClick={() => onChange(val)}
            className={`px-2.5 py-1 rounded-md text-xs border transition-colors ${
              value === val
                ? 'border-primary bg-primary/10 text-primary font-medium'
                : 'border-border text-muted-foreground hover:bg-surface-hover'
            }`}
          >
            {text}
          </button>
        ))}
      </div>
    </div>
  )
}
