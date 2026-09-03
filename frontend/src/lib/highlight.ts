/**
 * The delimiters the search endpoint wraps matches in.
 *
 * Plain text rather than `<mark>` tags, so a snippet drawn from a transcript can be highlighted by
 * splitting it into React elements. The alternative — server-rendered HTML through
 * `dangerouslySetInnerHTML` — would execute whatever a user once pasted into a conversation.
 */
export const HIGHLIGHT_OPEN = '[[HL]]'
export const HIGHLIGHT_CLOSE = '[[/HL]]'

/** Strips the delimiters, for places that show the text without styling it. */
export function stripHighlights(snippet: string): string {
  return snippet.split(HIGHLIGHT_OPEN).join('').split(HIGHLIGHT_CLOSE).join('')
}
