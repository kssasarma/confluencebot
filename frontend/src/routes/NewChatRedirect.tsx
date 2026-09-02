import { useEffect, useRef } from 'react'
import { Navigate } from 'react-router-dom'
import { useChat } from '../context/ChatContext'

/**
 * `/` opens a conversation rather than being a screen of its own.
 *
 * Giving the empty state its own route would mean the first question navigates away mid-send, so
 * instead a draft id is minted here and `/` redirects to it. The conversation is only created
 * server-side by that first question, so this leaves nothing behind if the reader never asks one.
 */
export default function NewChatRedirect() {
  const chat = useChat()

  // Minted once per mount: calling startDraft during render would mutate state mid-render, and
  // recalculating it on a re-render would redirect somewhere new each time.
  const draftId = useRef<string>()
  if (!draftId.current) draftId.current = chat.startDraft()

  useEffect(() => {
    if (draftId.current) chat.openTranscript(draftId.current)
  }, [chat])

  return <Navigate to={`/chat/${draftId.current}`} replace />
}
