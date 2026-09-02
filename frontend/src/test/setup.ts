import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

afterEach(() => cleanup())

/**
 * jsdom implements neither of these, and both are load-bearing in this app rather than
 * decorative: the theme provider reads `matchMedia` on mount, and the message list measures with
 * observers. Stubbing them here keeps every component test from having to.
 */
if (!window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }),
  })
}

class NoopObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() { return [] }
}

globalThis.ResizeObserver ??= NoopObserver as unknown as typeof ResizeObserver
globalThis.IntersectionObserver ??= NoopObserver as unknown as typeof IntersectionObserver

// jsdom has no layout, so every element is 0×0 and `scrollIntoView` is undefined. Components that
// autoscroll would throw rather than fail an assertion, which hides the real failure.
Element.prototype.scrollIntoView ??= vi.fn()
Element.prototype.scrollTo ??= vi.fn() as unknown as typeof Element.prototype.scrollTo

// Pointer capture is used by the sidebar resize handle; jsdom stubs it out entirely.
Element.prototype.setPointerCapture ??= vi.fn()
Element.prototype.releasePointerCapture ??= vi.fn()
Element.prototype.hasPointerCapture ??= vi.fn(() => false) as never
