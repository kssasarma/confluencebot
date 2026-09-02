import { useEffect } from 'react'
import { APP_TITLE } from '../config/env'

/**
 * Names the browser tab after whatever the reader is looking at.
 *
 * With a dozen tabs open, the title is the only way to find the conversation again — and it is
 * also what a bookmark and the history entry are named.
 */
export function useDocumentTitle(title: string | null): void {
  useEffect(() => {
    const previous = document.title
    document.title = title ? `${title} · ${APP_TITLE}` : APP_TITLE
    return () => { document.title = previous }
  }, [title])
}
