/**
 * A conversation identifier minted in the browser.
 *
 * The client names a conversation so that the server only sees it once it carries a real
 * question — which is what stops "New chat" from creating an empty row every time it is clicked.
 * The backend validates the shape, so this must be a real UUID and not merely unique.
 */
export function newChatId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // `randomUUID` needs a secure context; a LAN deployment served over plain HTTP has crypto but
  // not that method. getRandomValues is available in both, so fall back to it rather than to
  // Math.random.
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80

  const hex = [...bytes].map(b => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** A local id for a message that has no server identity yet. */
let localCounter = 0
export const newLocalId = (): string => `local-${++localCounter}-${Date.now().toString(36)}`
