import type { ChatPreferences, ResponseStyle } from '../../types'
import TriStateToggle from './TriStateToggle'

const STYLES: Array<{ value: ResponseStyle | null; label: string }> = [
  { value: null, label: 'Default' },
  { value: 'concise', label: 'Concise' },
  { value: 'balanced', label: 'Balanced' },
  { value: 'detailed', label: 'Detailed' },
]

/**
 * The per-conversation override fields, shared by the dialog that saves them immediately and the
 * one that holds them until a conversation exists to save them to.
 *
 * Every control is tri-state: Default / On / Off. `null` meaning "inherit whatever my account
 * says" is a real, distinct value — flattening it to a boolean would silently freeze the
 * conversation to whatever the account happened to say on the day it was opened.
 */
export default function ChatPreferencesFields({
  draft, onChange,
}: {
  draft: ChatPreferences
  onChange: (next: ChatPreferences) => void
}) {
  return (
    <>
      <fieldset>
        <legend className="mb-2 text-2xs font-semibold uppercase tracking-wider text-muted-foreground">
          Response style
        </legend>
        <div className="flex flex-wrap gap-2">
          {STYLES.map(({ value, label }) => (
            <button
              key={label}
              aria-pressed={(draft.responseStyle ?? null) === value}
              onClick={() => onChange({ ...draft, responseStyle: value })}
              className={
                (draft.responseStyle ?? null) === value
                  ? 'rounded-lg border border-primary bg-primary-soft px-3 py-1.5 text-2xs font-medium text-primary-emphasis'
                  : 'rounded-lg border border-border px-3 py-1.5 text-2xs text-muted-foreground hover:bg-surface-hover'
              }
            >
              {label}
            </button>
          ))}
        </div>
      </fieldset>

      <fieldset className="space-y-3">
        <legend className="mb-2 text-2xs font-semibold uppercase tracking-wider text-muted-foreground">
          Display
        </legend>
        <TriStateToggle
          label="Show sources"
          value={draft.showSources ?? null}
          onChange={value => onChange({ ...draft, showSources: value })}
        />
        <TriStateToggle
          label="Show match strength"
          value={draft.showConfidence ?? null}
          onChange={value => onChange({ ...draft, showConfidence: value })}
        />
      </fieldset>

      <div>
        <label
          htmlFor="custom-prompt"
          className="mb-2 block text-2xs font-semibold uppercase tracking-wider text-muted-foreground"
        >
          Custom instruction
        </label>
        <textarea
          id="custom-prompt"
          rows={3}
          value={draft.customPrompt ?? ''}
          onChange={event => onChange({ ...draft, customPrompt: event.target.value || null })}
          placeholder="e.g. Always answer in bullet points"
          className="w-full resize-none rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground"
        />
      </div>
    </>
  )
}
