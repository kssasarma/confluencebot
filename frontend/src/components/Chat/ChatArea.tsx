import { useEffect, useRef, useState, useCallback } from 'react'
import ChatInput from './ChatInput'
import MessageBubble from './MessageBubble'
import HomeScreen from '../Home/HomeScreen'
import { streamChatMessage } from '../../services/chatService'
import { fetchChatPreferences } from '../../services/userPreferenceService'
import type { ChatSession, ChatPreferences, Message } from '../../types'

interface ChatAreaProps {
  session: ChatSession | null
  onFirstMessage?: (sessionId: string, firstUserMessage: string) => void
}

export default function ChatArea({ session, onFirstMessage }: ChatAreaProps) {
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [chatPrefs, setChatPrefs] = useState<ChatPreferences | null>(null)
  const abortRef = useRef<AbortController | null>(null)
  const bottomRef = useRef<HTMLDivElement>(null)
  const isFirstRef = useRef(true)

  useEffect(() => {
    if (!session) { setMessages([]); return }
    setMessages([])
    isFirstRef.current = true
    fetchChatPreferences(session.chatId).then(setChatPrefs).catch(() => {})
  }, [session?.chatId])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const appendMessage = useCallback((msg: Message) => {
    setMessages(prev => [...prev, msg])
  }, [])

  const patchLast = useCallback((role: Message['role'], patch: Partial<Message>) => {
    setMessages(prev => {
      for (let i = prev.length - 1; i >= 0; i--) {
        if (prev[i].role === role) {
          const next = [...prev]
          next[i] = { ...next[i], ...patch }
          return next
        }
      }
      return prev
    })
  }, [])

  async function handleSend() {
    if (!input.trim() || streaming || !session) return
    const userText = input.trim()
    setInput('')

    const userMsg: Message = { localId: crypto.randomUUID(), role: 'user', content: userText }
    const assistantMsg: Message = { localId: crypto.randomUUID(), role: 'assistant', content: '', streaming: true }
    appendMessage(userMsg)
    appendMessage(assistantMsg)
    setStreaming(true)

    if (isFirstRef.current) {
      isFirstRef.current = false
      onFirstMessage?.(session.chatId, userText)
    }

    const controller = new AbortController()
    abortRef.current = controller

    try {
      let accumulated = ''
      await streamChatMessage(
        { chatId: session.chatId, message: userText, preferences: chatPrefs },
        (delta) => {
          accumulated += delta
          patchLast('assistant', { content: accumulated })
        },
        (sources) => { patchLast('assistant', { sources }) },
        controller.signal,
      )
    } catch (err) {
      if (!controller.signal.aborted) {
        patchLast('assistant', { content: 'An error occurred. Please try again.' })
      }
    } finally {
      patchLast('assistant', { streaming: false })
      setStreaming(false)
      abortRef.current = null
    }
  }

  function handleStop() {
    abortRef.current?.abort()
  }

  if (!session) {
    return (
      <div className="flex flex-col h-full">
        <div className="flex-1 overflow-y-auto">
          <HomeScreen onSelectPrompt={(p) => setInput(p)} />
        </div>
        <ChatInput value={input} onChange={setInput} onSend={handleSend} onStop={handleStop} streaming={streaming} />
      </div>
    )
  }

  return (
    <div className="flex flex-col h-full">
      <div className="flex-1 overflow-y-auto px-4">
        <div className="max-w-3xl mx-auto py-4">
          {messages.length === 0 ? (
            <div className="flex items-center justify-center min-h-[60vh]">
              <p className="text-muted-foreground text-sm">Start a conversation about your Confluence pages.</p>
            </div>
          ) : (
            messages.map((m) => <MessageBubble key={m.localId} message={m} />)
          )}
          <div ref={bottomRef} />
        </div>
      </div>
      <ChatInput value={input} onChange={setInput} onSend={handleSend} onStop={handleStop} streaming={streaming} />
    </div>
  )
}
