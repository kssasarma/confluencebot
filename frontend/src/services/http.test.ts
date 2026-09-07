import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from './http'
import { REFRESH_KEY, TOKEN_KEY } from '../lib/token'

/**
 * The 401 → refresh → retry path.
 *
 * The case that matters is the last one: a replayed request that is *still* unauthorized after a
 * successful refresh means the session itself is invalid, not just the access token, and the
 * reader must be signed out immediately rather than left holding a session that every request
 * will keep failing.
 */

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn())
  localStorage.clear()
  localStorage.setItem(TOKEN_KEY, 'expired-access-token')
  localStorage.setItem(REFRESH_KEY, 'a-refresh-token')
})

describe('apiFetch', () => {
  it('passes through a healthy response untouched', async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(200, { ok: true }))

    const response = await apiFetch('/chat')

    expect(response.status).toBe(200)
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('refreshes once and replays the request on a 401', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(jsonResponse(401, { detail: 'expired' }))
      .mockResolvedValueOnce(jsonResponse(200, { token: 'fresh-token', refreshToken: 'fresh-refresh' }))
      .mockResolvedValueOnce(jsonResponse(200, { ok: true }))

    const response = await apiFetch('/chat')

    expect(response.status).toBe(200)
    expect(fetch).toHaveBeenCalledTimes(3)
    expect(localStorage.getItem(TOKEN_KEY)).toBe('fresh-token')
  })

  it('clears the session when the refresh fails outright', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(jsonResponse(401, { detail: 'expired' }))
      .mockResolvedValueOnce(jsonResponse(401, { detail: 'invalid refresh token' }))

    const response = await apiFetch('/chat')

    expect(response.status).toBe(401)
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
    expect(localStorage.getItem(REFRESH_KEY)).toBeNull()
  })

  it('clears the session when the replayed request is still unauthorized', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(jsonResponse(401, { detail: 'expired' }))
      .mockResolvedValueOnce(jsonResponse(200, { token: 'fresh-token', refreshToken: 'fresh-refresh' }))
      .mockResolvedValueOnce(jsonResponse(401, { detail: 'session revoked' }))

    const response = await apiFetch('/chat/stream')

    expect(response.status).toBe(401)
    expect(fetch).toHaveBeenCalledTimes(3)
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
    expect(localStorage.getItem(REFRESH_KEY)).toBeNull()
  })
})
