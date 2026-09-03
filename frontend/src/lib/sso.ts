/**
 * Reading what the identity provider sent the browser back with.
 *
 * The backend finishes a single sign-on by redirecting here with a result in the URL *fragment*
 * rather than the query string. A fragment never leaves the browser — not to this app's own
 * server, not in the `Referer` of the next request the page makes — so the single-use code it
 * carries is readable here and nowhere else. It is also, for the same reason, invisible to the
 * server, which is why unpacking it is the browser's job.
 */

const CODE_PARAM = 'sso_code'
const ERROR_PARAM = 'sso_error'

export interface SsoHandoff {
  /** Exchange this, once, for a normal token pair. */
  code?: string
  /** A message to show on the sign-in screen instead. */
  error?: string
}

/** Returns what the provider sent back, or null when this is an ordinary page load. */
export function readSsoHandoff(): SsoHandoff | null {
  const hash = window.location.hash
  if (hash.length < 2) return null

  const params = new URLSearchParams(hash.slice(1))
  const code = params.get(CODE_PARAM)
  const error = params.get(ERROR_PARAM)
  if (!code && !error) return null

  return { code: code ?? undefined, error: error ?? undefined }
}

/**
 * Rewrites the address bar back to the application root.
 *
 * Both halves matter. The fragment goes because a code left in the URL is a code left in browser
 * history and in every screenshot of this tab. The path goes because `/sso/callback` is a landing
 * spot the provider redirects to, not a route this application knows how to render — leaving it
 * there means a reload lands on nothing.
 */
export function clearSsoHandoff(): void {
  window.history.replaceState(null, '', import.meta.env.BASE_URL)
}
