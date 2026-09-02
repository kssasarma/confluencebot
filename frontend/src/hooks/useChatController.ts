import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { Message, Source } from '../types'
import { fetchTranscript, streamChatMessage, type StreamCompletion } from '../services/chatService'
import { classifyError } from '../lib/errors'
import { newLocalId } from '../lib/id'
import { queryKeys } from '../services/queryKeys'

export interface ChatController {
  /** Transcripts by conversation. Switching away from a streaming answer and back keeps it. */
  messagesByChat: Record<string, Message[]>
  loadingChatId: string | null
  loadErrorByChat: Record<string, string>
  streamingChatId: string | null

  loadTranscript: (chatId: string) => void
  sendMessage: (chatId: string, question: string) => Promise<void>
  /** Re-asks the question that produced a failed answer, replacing it in place. */
  retry: (chatId: string) => Promise<void>
  stopStreaming: () => void
  discardChat: (chatId: string) => void
}

interface SessionRegistration {
  chatId: string
  title: string | null
}

interface ChatControllerOptions {
  /** Called when the server has recorded a turn, so the sidebar can show the conversation. */
  onSessionRecorded: (registration: SessionRegistration) => void
  /** Called when a summarised title arrives after the answer. */
  onTitleRefined: (registration: SessionRegistration) => void
}

/**
 * Owns the in-flight answer and every transcript the user has opened.
 *
 * Transcripts are kept as `Record<chatId, Message[]>` rather than nested inside a session list:
 * appending a token then costs one array copy of one conversation, not a clone of every
 * conversation the user has ever opened.
 */
export function useChatController({
  onSessionRecorded, onTitleRefined,
}: ChatControllerOptions): ChatController {
  const queryClient = useQueryClient()

  const [messagesByChat, setMessagesByChat] = useState<Record<string, Message[]>>({})
  const [loadingChatId, setLoadingChatId] = useState<string | null>(null)
  const [loadErrorByChat, setLoadErrorByChat] = useState<Record<string, string>>({})
  const [streamingChatId, setStreamingChatId] = useState<string | null>(null)

  const abortRef = useRef<AbortController | null>(null)

  /**
   * The transcripts, readable without being a dependency.
   *
   * `retry` needs the current transcript to find the question behind a failed answer. Taking
   * `messagesByChat` as a dependency would give it a new identity on every streamed token, which
   * ripples through every consumer of the chat context and undoes the memoisation on the message
   * rows. Assigning during render is safe here: it is a mirror of state, never a source of it.
   */
  const messagesRef = useRef(messagesByChat)
  messagesRef.current = messagesByChat

  // ── Streaming buffer ──────────────────────────────────────────────────────
  //
  // Tokens arrive far faster than the screen refreshes. A `setState` per token re-renders the
  // whole transcript dozens of times between paints, which is what makes a long answer stutter.
  // Tokens accumulate here instead and are flushed at most once per animation frame.
  const buffer = useRef<{ chatId: string; messageId: string; text: string } | null>(null)
  const frame = useRef<number | null>(null)

  // ── The settled guard ─────────────────────────────────────────────────────
  //
  // The final token delta and the `done` event routinely arrive in the same synchronous read of
  // the stream, so the frame scheduled for that last flush is still pending when completion runs.
  // Without this flag it fires on the next paint and re-opens a message the completion just
  // closed — the symptom is a caret that blinks forever on a finished answer.
  const settledMessages = useRef(new Set<string>())

  const appendBuffered = useCallback(() => {
    const buffered = buffer.current
    buffer.current = null
    if (!buffered || !buffered.text) return

    setMessagesByChat(previous => ({
      ...previous,
      [buffered.chatId]: (previous[buffered.chatId] ?? []).map(message =>
        message.id === buffered.messageId
          ? { ...message, content: message.content + buffered.text }
          : message),
    }))
  }, [])

  const flushNow = useCallback(() => {
    if (frame.current !== null) {
      cancelAnimationFrame(frame.current)
      frame.current = null
    }
    appendBuffered()
  }, [appendBuffered])

  const queueToken = useCallback((chatId: string, messageId: string, delta: string) => {
    if (!delta || settledMessages.current.has(messageId)) return

    if (buffer.current?.messageId === messageId) buffer.current.text += delta
    else {
      // A token for a different message means the buffer holds someone else's text. Land it
      // before starting a new one rather than discarding it.
      appendBuffered()
      buffer.current = { chatId, messageId, text: delta }
    }

    if (frame.current === null) {
      frame.current = requestAnimationFrame(() => {
        frame.current = null
        appendBuffered()
      })
    }
  }, [appendBuffered])

  useEffect(() => () => {
    if (frame.current !== null) cancelAnimationFrame(frame.current)
    abortRef.current?.abort()
  }, [])

  // ── Transcript loading ────────────────────────────────────────────────────

  const patchMessage = useCallback((chatId: string, messageId: string, patch: Partial<Message>) => {
    setMessagesByChat(previous => ({
      ...previous,
      [chatId]: (previous[chatId] ?? []).map(message =>
        message.id === messageId ? { ...message, ...patch } : message),
    }))
  }, [])

  const loadTranscript = useCallback((chatId: string) => {
    setMessagesByChat(previous => (chatId in previous ? previous : { ...previous, [chatId]: [] }))
    setLoadingChatId(chatId)

    fetchTranscript(chatId)
      .then(loaded => {
        setMessagesByChat(previous => ({ ...previous, [chatId]: loaded }))
        setLoadErrorByChat(({ [chatId]: _cleared, ...rest }) => rest)
      })
      .catch(error => {
        // An outage must not look like "no messages yet" — that is the same lie in a different
        // place, and the reader has no way to tell they are looking at a broken request.
        setLoadErrorByChat(previous => ({ ...previous, [chatId]: classifyError(error).message }))
      })
      .finally(() => setLoadingChatId(current => (current === chatId ? null : current)))
  }, [])

  // ── Answering ─────────────────────────────────────────────────────────────

  const completeAnswer = useCallback((
    chatId: string, answerId: string, result: StreamCompletion,
  ) => {
    // Order matters: land every buffered token and cancel the pending frame *before* closing the
    // message, or the frame that survives will append after the trim.
    settledMessages.current.add(answerId)
    flushNow()

    setMessagesByChat(previous => ({
      ...previous,
      [chatId]: (previous[chatId] ?? []).map(message => message.id === answerId
        ? {
            ...message,
            content: message.content.trimEnd(),
            streaming: false,
            error: undefined,
            followUpQuestions: result.followUpQuestions,
            citations: result.citations,
            confidence: result.confidence,
          }
        : message),
    }))

    onSessionRecorded({ chatId: result.chatId ?? chatId, title: result.title })
  }, [flushNow, onSessionRecorded])

  const failAnswer = useCallback((chatId: string, answerId: string, error: unknown) => {
    settledMessages.current.add(answerId)
    flushNow()

    const failure = classifyError(error)

    // The failure is attached to the answer it belongs to rather than pushed as a second bubble.
    // A stream that died halfway is one damaged answer, and appending a separate red message
    // beneath the partial text reads as though a good answer were followed by a bad one.
    patchMessage(chatId, answerId, failure.kind === 'aborted'
      ? { streaming: false, stopped: true }
      : { streaming: false, error: { message: failure.message, retryable: failure.retryable } })
  }, [flushNow, patchMessage])

  const runStream = useCallback(async (
    chatId: string, question: string, answerId: string,
  ) => {
    setStreamingChatId(chatId)

    const controller = new AbortController()
    abortRef.current = controller

    try {
      await streamChatMessage(
        { chatId, question },
        {
          onSources: (sources: Source[]) => patchMessage(chatId, answerId, { sources }),
          onToken: (delta: string) => queueToken(chatId, answerId, delta),
          onDone: result => completeAnswer(chatId, answerId, result),
          onTitle: (refinedChatId, title) => onTitleRefined({ chatId: refinedChatId, title }),
        },
        controller.signal,
      )
    } catch (error) {
      failAnswer(chatId, answerId, controller.signal.aborted
        ? new DOMException('Aborted', 'AbortError')
        : error)
    } finally {
      setStreamingChatId(current => (current === chatId ? null : current))
      abortRef.current = null
      settledMessages.current.delete(answerId)
      // The transcript on the server now differs from the one in the cache.
      queryClient.invalidateQueries({ queryKey: queryKeys.transcript(chatId), exact: true })
    }
  }, [completeAnswer, failAnswer, onTitleRefined, patchMessage, queryClient, queueToken])

  const sendMessage = useCallback(async (chatId: string, text: string) => {
    const question = text.trim()
    if (!question || streamingChatId) return

    const answerId = newLocalId()
    setMessagesByChat(previous => ({
      ...previous,
      [chatId]: [
        ...(previous[chatId] ?? []),
        { id: newLocalId(), role: 'user', content: question, createdAt: new Date().toISOString() },
        { id: answerId, role: 'assistant', content: '', streaming: true },
      ],
    }))

    await runStream(chatId, question, answerId)
  }, [runStream, streamingChatId])

  /**
   * Re-asks the question behind the last failed answer.
   *
   * The failed answer is replaced rather than appended to, so a retry cannot leave two attempts
   * in the transcript — and the question is read back from the transcript rather than remembered
   * in a ref, which keeps retry working after a reload or a chat switch.
   */
  const retry = useCallback(async (chatId: string) => {
    if (streamingChatId) return

    const transcript = messagesRef.current[chatId] ?? []
    const answerIndex = transcript.findLastIndex(message => message.role === 'assistant')
    if (answerIndex < 1) return

    const question = transcript[answerIndex - 1]
    if (question.role !== 'user') return

    const answerId = newLocalId()
    setMessagesByChat(previous => ({
      ...previous,
      [chatId]: (previous[chatId] ?? []).map((message, index) => index === answerIndex
        ? { id: answerId, role: 'assistant', content: '', streaming: true }
        : message),
    }))

    await runStream(chatId, question.content, answerId)
  }, [runStream, streamingChatId])

  const stopStreaming = useCallback(() => abortRef.current?.abort(), [])

  const discardChat = useCallback((chatId: string) => {
    setMessagesByChat(({ [chatId]: _removed, ...rest }) => rest)
    setLoadErrorByChat(({ [chatId]: _clearedError, ...rest }) => rest)
  }, [])

  return {
    messagesByChat,
    loadingChatId,
    loadErrorByChat,
    streamingChatId,
    loadTranscript,
    sendMessage,
    retry,
    stopStreaming,
    discardChat,
  }
}
