/**
 * A name to greet a reader by, derived from the address they signed in with.
 *
 * An account carries no display name — the server stores an email, a role and nothing else — so
 * the local part is all there is to work with. Greeting someone by the whole address reads like a
 * mail merge, so it is split on the separators addresses conventionally use between name parts
 * and title-cased: `priya.sharma@acme.com` becomes `Priya Sharma`, `kssasarma@gmail.com` becomes
 * `Kssasarma`.
 *
 * Nothing is guessed beyond that. Parts are not reordered, initials are not expanded, and a local
 * part with no letters in it at all yields nothing, so the greeting can drop the name rather than
 * address someone as "12345".
 */

/** What addresses put between name parts. */
const SEPARATORS = /[._\-\s]+/

/** Beyond this the greeting stops being a greeting and starts being a layout problem. */
const MAX_LENGTH = 24

export function displayNameFromEmail(email: string | null | undefined): string {
  const local = (email ?? '')
    .split('@')[0]
    // Plus-addressing is a routing tag the reader added, not part of their name.
    .split('+')[0]
    .trim()

  const name = local
    .split(SEPARATORS)
    .filter(part => /\p{L}/u.test(part))
    .map(titleCase)
    .join(' ')

  return name.length > MAX_LENGTH ? `${name.slice(0, MAX_LENGTH).trimEnd()}…` : name
}

/**
 * Capitalises without flattening.
 *
 * Only the first letter is forced, so `McCarthy` survives as itself. The exception is a part
 * shouted entirely in capitals: `KSSASARMA` is an address written in caps lock, not a name, while
 * a short run like `KS` is plausibly initials and is left alone.
 */
function titleCase(part: string): string {
  const rest = part.length > 3 && part === part.toUpperCase() ? part.slice(1).toLowerCase() : part.slice(1)
  return part.charAt(0).toUpperCase() + rest
}
