import { useState } from 'react'
import type { ChatPreferences } from '../../types'
import Modal, { ModalBody } from '../ui/Modal'
import Button from '../ui/Button'
import ChatPreferencesFields from './ChatPreferencesFields'

/**
 * Per-conversation overrides, chosen before the conversation exists on the server.
 *
 * There is nowhere to save this to yet — the endpoint {@link ChatPreferencesDialog} calls requires
 * a conversation row, and one is only created by the first recorded turn. The choice is instead
 * handed back to the caller, which holds it (see `writePendingChatPreferences`) until that first
 * turn lands and replays it as a real save.
 */
export default function PendingChatPreferencesDialog({
  value, onSave, onClose,
}: {
  value: ChatPreferences
  onSave: (preferences: ChatPreferences) => void
  onClose: () => void
}) {
  const [draft, setDraft] = useState<ChatPreferences>(value)

  function handleSave() {
    onSave(draft)
    onClose()
  }

  return (
    <Modal
      open
      onClose={onClose}
      title="Chat settings"
      description="These will apply once you send your first message."
      size="sm"
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button onClick={handleSave}>Save</Button>
        </>
      }
    >
      <ModalBody className="space-y-6">
        <ChatPreferencesFields draft={draft} onChange={setDraft} />
      </ModalBody>
    </Modal>
  )
}
