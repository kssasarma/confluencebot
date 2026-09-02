import { useEffect, useRef } from 'react'
import ChatInput from './ChatInput'
import MessageBubble from './MessageBubble'
import FollowUpQuestions from './FollowUpQuestions'
import HomeScreen from '../Home/HomeScreen'
import Spinner from '../ui/Spinner'
import type { Message } from '../../types'

interface ChatAreaProps {
  chatId: string | null
  messages: Message[]
  isLoading: boolean
  isStreaming: boolean
  draft: string
  onDraftChange: (value: string) => void
  onSend: () => void
  onStop: () => void
  /** Asks a suggested question straight away, without a detour through the composer. */
  onAsk: (question: string) => void
}

export default function ChatArea({
  chatId, messages, isLoading, isStreaming, draft, onDraftChange, onSend, onStop, onAsk,
}: ChatAreaProps) {
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const lastAnswer = [...messages].reverse().find(m => m.role === 'assistant')
  const followUps = !isStreaming ? lastAnswer?.followUpQuestions ?? [] : []

  return (
    <div className="flex flex-col h-full">
      <div className="flex-1 overflow-y-auto px-4">
        <div className="max-w-3xl mx-auto py-4">
          {isLoading ? (
            <div className="flex items-center justify-center min-h-[60vh]">
              <Spinner size="lg" />
            </div>
          ) : messages.length === 0 ? (
            <HomeScreen onSelectPrompt={onDraftChange} isNewChat={chatId !== null} />
          ) : (
            <>
              {messages.map(message => <MessageBubble key={message.id} message={message} />)}
              {followUps.length > 0 && (
                <FollowUpQuestions questions={followUps} onSelect={onAsk} />
              )}
            </>
          )}
          <div ref={bottomRef} />
        </div>
      </div>
      <ChatInput
        value={draft}
        onChange={onDraftChange}
        onSend={onSend}
        onStop={onStop}
        streaming={isStreaming}
      />
    </div>
  )
}
