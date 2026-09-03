import { ApiError } from '../services/http'

/**
 * What kind of failure a caught error represents, and what to tell the reader about it.
 *
 * The point of naming the kinds is that they need different words and different affordances. A
 * user who stopped generation has not hit an error; a user whose laptop is offline does not need
 * a retry button that cannot work; and a stream that died *after* the answer completed needs
 * nothing at all, because the answer is whole and already saved.
 */
export type FailureKind = 'aborted' | 'offline' | 'busy' | 'unauthorized' | 'unknown'

export interface Failure {
  kind: FailureKind
  message: string
  /** False when retrying cannot help until something else changes. */
  retryable: boolean
}

export function classifyError(error: unknown): Failure {
  if (isAbortError(error)) {
    return { kind: 'aborted', message: 'Stopped.', retryable: true }
  }

  // Checked before the status codes: a request that never left the machine has no status, and
  // "check your connection" is far more useful than "something went wrong".
  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    return {
      kind: 'offline',
      message: "You're offline. The answer will work once you're connected again.",
      retryable: false,
    }
  }

  if (error instanceof ApiError) {
    if (error.status === 503) {
      return {
        kind: 'busy',
        message: error.message || 'The assistant is busy right now. Try again in a moment.',
        retryable: true,
      }
    }
    if (error.status === 401 || error.status === 403) {
      return { kind: 'unauthorized', message: error.message, retryable: false }
    }
    return { kind: 'unknown', message: error.message, retryable: true }
  }

  if (error instanceof TypeError) {
    // `fetch` rejects with a TypeError for every network-layer failure: DNS, CORS, a dropped
    // connection. The browser deliberately withholds the detail, so the copy must not pretend.
    return {
      kind: 'unknown',
      message: 'The connection was lost before the answer finished.',
      retryable: true,
    }
  }

  return {
    kind: 'unknown',
    message: error instanceof Error && error.message
      ? error.message
      : 'The answer could not be generated. Please try again.',
    retryable: true,
  }
}

export function isAbortError(error: unknown): boolean {
  return error instanceof DOMException
    ? error.name === 'AbortError'
    : error instanceof Error && error.name === 'AbortError'
}

/** A message for a failure that is not an answer failure — a save, a rename, a delete. */
export function toMessage(error: unknown, fallback: string): string {
  const failure = classifyError(error)
  return failure.kind === 'unknown' && !(error instanceof ApiError) ? fallback : failure.message
}
