import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearSsoHandoff, readSsoHandoff } from './sso'

/**
 * Reading the identity provider's answer out of the URL.
 *
 * The fragment is the only part of a URL a browser never transmits, which is why the one-time
 * code is put there and why unpacking it can only happen here. These tests pin the two halves of
 * that: that an ordinary page load is not mistaken for a sign-in, and that once read, the code is
 * gone from the address bar rather than left in history for whoever opens this tab next.
 */

function land(url: string): void {
  window.history.replaceState(null, '', url)
}

describe('readSsoHandoff', () => {
  afterEach(() => land('/'))

  it('returns nothing on an ordinary page load', () => {
    land('/chat/abc')

    expect(readSsoHandoff()).toBeNull()
  })

  it('returns nothing for a fragment that is a plain anchor', () => {
    // An in-page anchor is not a sign-in, and treating one as a failed one would put an error on
    // the screen of someone who simply followed a link.
    land('/settings#notifications')

    expect(readSsoHandoff()).toBeNull()
  })

  it('reads the one-time code the provider sent back', () => {
    land('/sso/callback#sso_code=abc123&sso_provider=otds')

    expect(readSsoHandoff()).toEqual({ code: 'abc123', error: undefined, providerId: 'otds' })
  })

  it('reads whichever provider answered, without assuming one', () => {
    land('/sso/callback#sso_code=abc123&sso_provider=keycloak')

    expect(readSsoHandoff()?.providerId).toBe('keycloak')
  })

  it('reads a failure message, spaces and all', () => {
    land('/sso/callback#sso_error=This+account+has+been+disabled.')

    expect(readSsoHandoff())
      .toEqual({ code: undefined, error: 'This account has been disabled.', providerId: undefined })
  })

  it('decodes a percent-encoded message', () => {
    land('/sso/callback#sso_error=Sign-in%20failed%20%28invalid_grant%29.')

    expect(readSsoHandoff()?.error).toBe('Sign-in failed (invalid_grant).')
  })
})

describe('clearSsoHandoff', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
    land('/')
  })

  it('takes both the code and the landing path out of the address bar', () => {
    land('/sso/callback#sso_code=abc123')

    clearSsoHandoff()

    // The code goes because history is forever. The path goes because /sso/callback is where the
    // provider drops the browser, not a screen this app can render on a reload.
    expect(window.location.hash).toBe('')
    expect(window.location.pathname).toBe('/')
  })

  it('returns to the base path the app is served from, not to the site root', () => {
    vi.stubEnv('BASE_URL', '/ot-confluence-bot/')
    land('/ot-confluence-bot/sso/callback#sso_code=abc123')

    clearSsoHandoff()

    expect(window.location.pathname).toBe('/ot-confluence-bot/')
  })
})
