import { useEffect, useState } from 'react'
import type { ChatPreferences, ResponseStyle } from '../../types'
import { useChatPreferences } from '../../hooks/usePreferences'
import Modal, { ModalBody } from '../ui/Modal'
import Button from '../ui/Button'
import { SkeletonText } from '../ui/Skeleton'
import TriStateToggle from './TriStateToggle'

const STYLES: Array<{ value: ResponseStyle | null; label: string }> = [
  { value: null, label: 'Default' },
  { value: 'concise', label: 'Concise' },
  { value: 'balanced', label: 'Balanced' },
  { value: 'detailed', label: 'Detailed' },
]

/**
 * Per-conversation overrides of the account defaults.
 *
 * Every control is tri-state: Default / On / Off. `null` meaning "inherit whatever my account
 * says" is a real, distinct value — flattening it to a boolean would silently freeze the
 * conversation to whatever the account happened to say on the day it was opened.
 */
export default function ChatPreferencesDialog({
  chatId, onClose,
}: { chatId: string; onClose: () => void }) {
  const { overrides, isLoading, save, isSaving } = useChatPreferences(chatId)
  const [draft, setDraft] = useState<ChatPreferences>({})

  useEffect(() => { if (overrides) setDraft(overrides) }, [overrides])

  function handleSave() {
    save(draft)
    onClose()
  }

  return (
    <Modal
      open
      onClose={onClose}
      title="Chat settings"
      description="These apply to this conversation only."
      size="sm"
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button onClick={handleSave} loading={isSaving}>Save</Button>
        </>
      }
    >
      <ModalBody className="space-y-6">
        {isLoading ? (
          <SkeletonText lines={6} />
        ) : (
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
                    onClick={() => setDraft(current => ({ ...current, responseStyle: value }))}
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
                onChange={value => setDraft(current => ({ ...current, showSources: value }))}
              />
              <TriStateToggle
                label="Show match strength"
                value={draft.showConfidence ?? null}
                onChange={value => setDraft(current => ({ ...current, showConfidence: value }))}
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
                onChange={event =>
                  setDraft(current => ({ ...current, customPrompt: event.target.value || null }))}
                placeholder="e.g. Always answer in bullet points"
                className="w-full resize-none rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground"
              />
            </div>
          </>
        )}
      </ModalBody>
    </Modal>
  )
}
