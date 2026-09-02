import { useCallback, useMemo } from 'react'
import {
  useInfiniteQuery, useMutation, useQueryClient, type InfiniteData,
} from '@tanstack/react-query'
import type { ChatSession, ChatSessionPage } from '../types'
import {
  deleteSession, fetchSessions, updateSession,
} from '../services/chatService'
import { queryKeys } from '../services/queryKeys'
import { useToast } from '../components/ui/Toast'
import { toMessage } from '../lib/errors'

const PAGE_SIZE = 30

type SessionPages = InfiniteData<ChatSessionPage, string | null>

export interface SessionsApi {
  sessions: ChatSession[]
  isLoading: boolean
  isFetchingMore: boolean
  hasMore: boolean
  error: string | null
  loadMore: () => void
  refresh: () => void
  rename: (chatId: string, title: string) => void
  togglePin: (chatId: string) => void
  remove: (chatId: string) => void
  /** Puts a conversation into the list without a round trip, for one just created by an answer. */
  upsert: (session: Pick<ChatSession, 'chatId' | 'title'>) => void
  applyTitle: (chatId: string, title: string) => void
}

/**
 * The conversation list: paginated, searchable, and optimistic about edits.
 *
 * Every mutation writes to the cache first and rolls back on failure, with the reason surfaced as
 * a toast. The alternative — the previous behaviour — was an optimistic update with a swallowed
 * catch, so a failed rename looked exactly like a successful one until the next reload silently
 * put the old name back.
 */
export function useSessions(search: string): SessionsApi {
  const queryClient = useQueryClient()
  const toast = useToast()
  const queryKey = queryKeys.sessions(search)

  const query = useInfiniteQuery<ChatSessionPage, Error, SessionPages, readonly unknown[], string | null>({
    queryKey,
    initialPageParam: null,
    queryFn: ({ pageParam }) => fetchSessions({ q: search, cursor: pageParam, limit: PAGE_SIZE }),
    getNextPageParam: page => page.nextCursor,
    // The list is re-read whenever the window regains focus, but a keystroke in the search box
    // must not refetch every page that is already on screen.
    staleTime: 30_000,
  })

  const sessions = useMemo(
    () => query.data?.pages.flatMap(page => page.items) ?? [],
    [query.data],
  )

  /** Applies an edit to every cached page of every search, so no view shows a stale row. */
  const patchCache = useCallback((
    chatId: string,
    patch: (session: ChatSession) => ChatSession | null,
  ): SessionPages[] => {
    const snapshots = queryClient.getQueriesData<SessionPages>({ queryKey: queryKeys.sessionsRoot })

    queryClient.setQueriesData<SessionPages>({ queryKey: queryKeys.sessionsRoot }, current => {
      if (!current) return current
      return {
        ...current,
        pages: current.pages.map(page => ({
          ...page,
          items: page.items
            .map(session => (session.chatId === chatId ? patch(session) : session))
            .filter((session): session is ChatSession => session !== null),
        })),
      }
    })

    return snapshots.map(([, data]) => data).filter((data): data is SessionPages => !!data)
  }, [queryClient])

  const restore = useCallback((snapshots: SessionPages[]) => {
    const entries = queryClient.getQueriesData<SessionPages>({ queryKey: queryKeys.sessionsRoot })
    entries.forEach(([key], index) => {
      if (snapshots[index]) queryClient.setQueryData(key, snapshots[index])
    })
  }, [queryClient])

  const renameMutation = useMutation({
    mutationFn: ({ chatId, title }: { chatId: string; title: string }) =>
      updateSession(chatId, { title }),
    onMutate: ({ chatId, title }) =>
      ({ snapshots: patchCache(chatId, s => ({ ...s, title, titleGenerated: false })) }),
    onError: (error, _variables, context) => {
      restore(context?.snapshots ?? [])
      toast.error('Could not rename the conversation', toMessage(error, 'Please try again.'))
    },
  })

  const pinMutation = useMutation({
    mutationFn: ({ chatId, pinned }: { chatId: string; pinned: boolean }) =>
      updateSession(chatId, { pinned }),
    onMutate: ({ chatId, pinned }) => ({ snapshots: patchCache(chatId, s => ({ ...s, pinned })) }),
    onError: (error, _variables, context) => {
      restore(context?.snapshots ?? [])
      toast.error('Could not update the conversation', toMessage(error, 'Please try again.'))
    },
    // Pinning changes the sort order, which is a server-side decision; re-read rather than
    // guessing where the row now belongs.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.sessionsRoot }),
  })

  const deleteMutation = useMutation({
    mutationFn: (chatId: string) => deleteSession(chatId),
    onMutate: chatId => ({ snapshots: patchCache(chatId, () => null) }),
    onError: (error, _chatId, context) => {
      restore(context?.snapshots ?? [])
      toast.error('Could not delete the conversation', toMessage(error, 'Please try again.'))
    },
  })

  const upsert = useCallback(({ chatId, title }: Pick<ChatSession, 'chatId' | 'title'>) => {
    const existing = sessions.some(session => session.chatId === chatId)
    if (existing) {
      patchCache(chatId, session => ({
        ...session,
        title: title ?? session.title,
        messageCount: session.messageCount + 2,
        updatedAt: new Date().toISOString(),
      }))
      return
    }

    // A brand-new conversation is only added to the unfiltered list: whether it matches the
    // current search is the server's judgement, and guessing would show a row that a refetch
    // then removes.
    queryClient.setQueryData<SessionPages>(queryKeys.sessions(''), current => {
      const row: ChatSession = {
        chatId,
        title,
        pinned: false,
        messageCount: 2,
        updatedAt: new Date().toISOString(),
        titleGenerated: true,
      }
      if (!current) return { pages: [{ items: [row], nextCursor: null }], pageParams: [null] }
      return {
        ...current,
        pages: current.pages.map((page, index) =>
          index === 0 ? { ...page, items: [row, ...page.items] } : page),
      }
    })
  }, [patchCache, queryClient, sessions])

  const applyTitle = useCallback((chatId: string, title: string) => {
    patchCache(chatId, session => ({ ...session, title, titleGenerated: true }))
  }, [patchCache])

  return {
    sessions,
    isLoading: query.isLoading,
    isFetchingMore: query.isFetchingNextPage,
    hasMore: query.hasNextPage,
    error: query.error ? toMessage(query.error, 'Could not load your conversations.') : null,
    loadMore: () => { if (query.hasNextPage && !query.isFetchingNextPage) void query.fetchNextPage() },
    refresh: () => { void queryClient.invalidateQueries({ queryKey: queryKeys.sessionsRoot }) },
    rename: (chatId, title) => {
      const trimmed = title.trim()
      if (trimmed) renameMutation.mutate({ chatId, title: trimmed })
    },
    togglePin: chatId => {
      const target = sessions.find(session => session.chatId === chatId)
      if (target) pinMutation.mutate({ chatId, pinned: !target.pinned })
    },
    remove: chatId => deleteMutation.mutate(chatId),
    upsert,
    applyTitle,
  }
}
