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
const PROVIDER_PARAM = 'sso_provider'

export interface SsoHandoff {
  /** Exchange this, once, for a normal token pair. */
  code?: string
  /** A message to show on the sign-in screen instead. */
  error?: string
  /** Which provider answered, so signing out later can end that session too. */
  providerId?: string
}

/** Returns what the provider sent back, or null when this is an ordinary page load. */
export function readSsoHandoff(): SsoHandoff | null {
  const hash = window.location.hash
  if (hash.length < 2) return null

  const params = new URLSearchParams(hash.slice(1))
  const code = params.get(CODE_PARAM)
  const error = params.get(ERROR_PARAM)
  if (!code && !error) return null

  return {
    code: code ?? undefined,
    error: error ?? undefined,
    providerId: params.get(PROVIDER_PARAM) ?? undefined,
  }
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

const PASSWORD_PARAM = 'password'

/**
 * Whether this visitor has already asked to skip straight to the password form.
 *
 * Enforced SSO leaves for the provider before the sign-in screen renders anything else, so the
 * escape hatch has to be readable before that decision is made — a piece of component state would
 * reset on the very reload someone mid-typing a password is most likely to trigger by accident.
 */
export function wantsPasswordSignIn(): boolean {
  return new URLSearchParams(window.location.search).has(PASSWORD_PARAM)
}

/** Remembers that choice in the address bar, so a reload does not bounce the visitor straight back
 *  to the provider mid-typing. */
export function requestPasswordSignIn(): void {
  const url = new URL(window.location.href)
  url.searchParams.set(PASSWORD_PARAM, '1')
  window.history.replaceState(null, '', url.pathname + url.search + url.hash)
}

/**
 * Ends the session at the identity provider without moving the browser there.
 *
 * A full-page redirect to the provider's end-session endpoint depends on it honoring
 * `post_logout_redirect_uri` and actually sending the browser back — support for that varies
 * enough across deployments (OTDS, Entra ID, Okta, Keycloak) that when it does not work, the
 * visitor is simply stranded on the provider's own page instead of seeing this app again. Loading
 * the same endpoint in a hidden iframe asks the provider to clear its session cookie the same way
 * a top-level visit would, but this tab never leaves, so `LogoutPage` renders immediately either
 * way. Nothing here waits on the iframe: there is no response worth reading, and no result that
 * would change what the visitor should see next.
 */
export function endProviderSession(logoutUrl: string): void {
  const iframe = document.createElement('iframe')
  iframe.hidden = true
  iframe.src = logoutUrl
  document.body.appendChild(iframe)
  window.setTimeout(() => iframe.remove(), 10_000)
}
