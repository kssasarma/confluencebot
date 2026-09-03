import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useChat } from '../context/ChatContext'
import { useEventCallback } from '../hooks/useEventCallback'

/**
 * `/` opens a conversation rather than being a screen of its own.
 *
 * Giving the empty state its own route would mean the first question navigates away mid-send, so
 * instead a draft id is minted here and `/` redirects to it. The conversation is only created
 * server-side by that first question, so this leaves nothing behind if the reader never asks one.
 *
 * The mint happens in an effect, not while rendering. Minting during render wrote to the chat
 * provider's state from inside another component's render pass, and the redirect that followed
 * then raced that write: the route it landed on could not yet be told the id was a draft, asked
 * the server for its transcript, and showed the 404 as "Could not load this conversation" on a
 * conversation nobody had said anything in.
 */
export default function NewChatRedirect() {
  const navigate = useNavigate()
  const chat = useChat()

  // Reads the live provider without making this effect depend on it — the context value changes
  // on every streamed token, and depending on it would redirect again mid-answer.
  const open = useEventCallback(() => { navigate(`/chat/${chat.startDraft()}`, { replace: true }) })

  useEffect(() => open(), [open])

  return null
}
