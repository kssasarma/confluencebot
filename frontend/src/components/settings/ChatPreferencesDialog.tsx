import { useEffect, useState } from 'react'
import type { ChatPreferences } from '../../types'
import { useChatPreferences } from '../../hooks/usePreferences'
import Modal, { ModalBody } from '../ui/Modal'
import Button from '../ui/Button'
import { SkeletonText } from '../ui/Skeleton'
import ChatPreferencesFields from './ChatPreferencesFields'

/**
 * Per-conversation overrides of the account defaults, for a conversation the server already holds.
 *
 * A conversation that does not exist there yet uses {@link PendingChatPreferencesDialog} instead —
 * the save below hits an endpoint that 404s until the conversation's first turn is recorded.
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
          <ChatPreferencesFields draft={draft} onChange={setDraft} />
        )}
      </ModalBody>
    </Modal>
  )
}
