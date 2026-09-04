import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { Message, Source } from '../types'
import { fetchTranscript, streamChatMessage, type StreamCompletion } from '../services/chatService'
import { ApiError } from '../services/http'
import { classifyError } from '../lib/errors'
import { newLocalId } from '../lib/id'
import { queryKeys } from '../services/queryKeys'

/**
 * What reading a transcript established about a conversation.
 *
 *  - `loaded`       — the server returned a non-empty transcript.
 *  - `loaded-empty` — the server has recorded this conversation, but it holds no messages yet.
 *  - `unsaved`      — the server has never heard of it, because no question has been asked in it.
 *  - `failed`       — the request itself broke, and the reader must be told.
 */
export type TranscriptOutcome = 'loaded' | 'loaded-empty' | 'unsaved' | 'failed'

/**
 * How long buffered tokens may wait when no animation frame arrives.
 *
 * Long enough that a painting tab always flushes on its frame first, short enough that a throttled
 * one still reads as live rather than as stalled.
 */
const FLUSH_BACKSTOP_MS = 100

export interface ChatController {
  /** Transcripts by conversation. Switching away from a streaming answer and back keeps it. */
  messagesByChat: Record<string, Message[]>
  loadingChatId: string | null
  loadErrorByChat: Record<string, string>
  streamingChatId: string | null

  /** Reads a conversation's transcript. Resolves with what the server turned out to hold. */
  loadTranscript: (chatId: string) => Promise<TranscriptOutcome>
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
  //
  // A timer runs alongside the frame as a backstop, because `requestAnimationFrame` is not a
  // clock: a background tab, a minimised window or a browser that decides the page is not worth
  // painting stops delivering frames altogether. The buffer then holds the whole answer until the
  // stream completes, and the reader watches an empty bubble for the entire generation. Whichever
  // of the two fires first flushes and cancels the other, so a visible tab still paints on the
  // frame and pays nothing for the timer.
  const buffer = useRef<{ chatId: string; messageId: string; text: string } | null>(null)
  const frame = useRef<number | null>(null)
  const backstop = useRef<ReturnType<typeof setTimeout> | null>(null)

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

  const cancelFlush = useCallback(() => {
    if (frame.current !== null) {
      cancelAnimationFrame(frame.current)
      frame.current = null
    }
    if (backstop.current !== null) {
      clearTimeout(backstop.current)
      backstop.current = null
    }
  }, [])

  const flushNow = useCallback(() => {
    cancelFlush()
    appendBuffered()
  }, [appendBuffered, cancelFlush])

  const queueToken = useCallback((chatId: string, messageId: string, delta: string) => {
    if (!delta || settledMessages.current.has(messageId)) return

    if (buffer.current?.messageId === messageId) buffer.current.text += delta
    else {
      // A token for a different message means the buffer holds someone else's text. Land it
      // before starting a new one rather than discarding it.
      appendBuffered()
      buffer.current = { chatId, messageId, text: delta }
    }

    if (frame.current === null && backstop.current === null) {
      frame.current = requestAnimationFrame(flushNow)
      backstop.current = setTimeout(flushNow, FLUSH_BACKSTOP_MS)
    }
  }, [appendBuffered, flushNow])

  useEffect(() => () => {
    cancelFlush()
    abortRef.current?.abort()
  }, [cancelFlush])

  // ── Transcript loading ────────────────────────────────────────────────────

  const patchMessage = useCallback((chatId: string, messageId: string, patch: Partial<Message>) => {
    setMessagesByChat(previous => ({
      ...previous,
      [chatId]: (previous[chatId] ?? []).map(message =>
        message.id === messageId ? { ...message, ...patch } : message),
    }))
  }, [])

  const clearLoadError = useCallback((chatId: string) => {
    setLoadErrorByChat(previous => {
      if (!(chatId in previous)) return previous
      const { [chatId]: _cleared, ...rest } = previous
      return rest
    })
  }, [])

  const loadTranscript = useCallback(async (chatId: string): Promise<TranscriptOutcome> => {
    setMessagesByChat(previous => (chatId in previous ? previous : { ...previous, [chatId]: [] }))
    setLoadingChatId(chatId)

    // What the transcript held when the request went out. A reader who asks a question before it
    // comes back has already put their turn in here, and overwriting that with the server's copy
    // — which predates the question — deletes the question and the answer arriving under it.
    const askedBefore = messagesRef.current[chatId]?.length ?? 0
    const untouched = () => (messagesRef.current[chatId]?.length ?? 0) === askedBefore

    try {
      const loaded = await fetchTranscript(chatId)
      const applied = untouched()
      if (applied) setMessagesByChat(previous => ({ ...previous, [chatId]: loaded }))
      clearLoadError(chatId)
      // Whether this is a draft depends on what the transcript actually held, not on
      // `messagesRef` reflecting it — that ref only catches up once React re-renders, and the
      // caller's `.then` can run before that happens. A conversation the reader already asked
      // something in while this was in flight (`!applied`) is never empty, whatever `loaded` says.
      return applied && loaded.length === 0 ? 'loaded-empty' : 'loaded'
    } catch (error) {
      // A conversation reaches the database only when it carries its first answer, so a 404 here
      // is not a failure: it is a conversation nobody has asked anything in yet. Reporting it as
      // an error is what turned every reload of a fresh chat — and every first visit to one —
      // into a dead end with a working composer underneath it. The empty transcript is already in
      // place from the top of this function; there is nothing further to write.
      if (error instanceof ApiError && error.status === 404) {
        clearLoadError(chatId)
        return 'unsaved'
      }

      // An outage must not look like "no messages yet" — that is the same lie in a different
      // place, and the reader has no way to tell they are looking at a broken request.
      setLoadErrorByChat(previous => ({ ...previous, [chatId]: classifyError(error).message }))
      return 'failed'
    } finally {
      setLoadingChatId(current => (current === chatId ? null : current))
    }
  }, [clearLoadError])

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

    // Whatever went wrong reading this conversation is now beside the point: there is a question
    // in it, and the answer to that question is what the reader needs to see.
    clearLoadError(chatId)

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
  }, [clearLoadError, runStream, streamingChatId])

  /**
   * Re-asks the question behind the last failed answer.
   *
   * The failed answer is replaced rather than appended to, so a retry cannot leave two attempts
   * in the transcript — and the question is read back from the transcript rather than remembered
   * in a ref, which keeps retry working after a reload or a chat switch.
   */
  const retry = useCallback(async (chatId: string) => {
    if (streamingChatId) return

    clearLoadError(chatId)

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
  }, [clearLoadError, runStream, streamingChatId])

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
