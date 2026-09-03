import { describe, expect, it } from 'vitest'
import { contrastRatio, readTokens, type Hsl } from './contrast'
// The stylesheet itself, not a transcription of it: `?raw` is what makes this test impossible to
// satisfy with colours the app does not actually ship.
import TOKENS_CSS from './tokens.css?raw'

/**
 * The palette's contract, enforced.
 *
 * This reads `tokens.css` itself rather than a copy, so it cannot pass while the app ships
 * different colours. Retuning a token without checking it here is not possible: the build fails.
 *
 * Thresholds are WCAG 2.1 AA — 4.5:1 for body text (1.4.3), 3:1 for the focus indicator as a
 * non-text UI component (1.4.11). Decorative dividers are deliberately not asserted: 1.4.11
 * targets meaningful boundaries and focus indicators, and holding a hairline rule to 3:1 would
 * force a border darker than any design that uses one.
 */

const THEMES = {
  light: readTokens(TOKENS_CSS, ':root'),
  dark: readTokens(TOKENS_CSS, '.dark'),
}

/** Every ground that body or secondary text is allowed to sit on. */
const TEXT_GROUNDS = ['background', 'surface', 'surface-hover', 'muted'] as const

const STATUSES = ['primary', 'accent', 'success', 'warning', 'danger', 'info'] as const

const AA_TEXT = 4.5
const AA_NON_TEXT = 3

interface Pair {
  foreground: string
  background: string
  minimum: number
}

function documentedPairs(): Pair[] {
  const pairs: Pair[] = []

  for (const foreground of ['foreground', 'muted-foreground']) {
    for (const background of TEXT_GROUNDS) {
      pairs.push({ foreground, background, minimum: AA_TEXT })
    }
  }

  for (const status of STATUSES) {
    // Text sitting on the solid fill.
    pairs.push({ foreground: `${status}-foreground`, background: status, minimum: AA_TEXT })

    // The hue used as text, on any ground it can land on including its own tint.
    for (const background of [...TEXT_GROUNDS, `${status}-soft`]) {
      pairs.push({ foreground: `${status}-emphasis`, background, minimum: AA_TEXT })
    }
  }

  // Hovered fills must not drop their label below AA — the easy regression when a dark-mode
  // hover is written as "lighter" out of habit.
  pairs.push({ foreground: 'primary-foreground', background: 'primary-hover', minimum: AA_TEXT })
  pairs.push({ foreground: 'danger-foreground', background: 'danger-hover', minimum: AA_TEXT })

  // The focus ring is a non-text indicator and must be visible on every ground it can appear over.
  for (const background of TEXT_GROUNDS) {
    pairs.push({ foreground: 'ring', background, minimum: AA_NON_TEXT })
  }

  return pairs
}

describe.each(Object.entries(THEMES))('%s theme', (theme, tokens) => {
  it('defines every token the app references', () => {
    const required = [
      ...TEXT_GROUNDS, 'foreground', 'muted-foreground', 'border', 'ring',
      ...STATUSES.flatMap(s => [s, `${s}-foreground`, `${s}-emphasis`, `${s}-soft`]),
      'primary-hover', 'danger-hover',
    ]
    expect(Object.keys(tokens)).toEqual(expect.arrayContaining(required))
  })

  it.each(documentedPairs())(
    '$foreground on $background meets $minimum:1',
    ({ foreground, background, minimum }) => {
      const fg = tokens[foreground] as Hsl | undefined
      const bg = tokens[background] as Hsl | undefined

      expect(fg, `${theme}: --${foreground} is not defined`).toBeDefined()
      expect(bg, `${theme}: --${background} is not defined`).toBeDefined()

      const ratio = contrastRatio(fg!, bg!)
      expect(
        Number(ratio.toFixed(2)),
        `${theme}: --${foreground} on --${background} is ${ratio.toFixed(2)}:1, below ${minimum}:1`,
      ).toBeGreaterThanOrEqual(minimum)
    },
  )
})

describe('contrast maths', () => {
  it('agrees with the reference values at the extremes', () => {
    const white = { h: 0, s: 0, l: 100 }
    const black = { h: 0, s: 0, l: 0 }

    expect(contrastRatio(white, black)).toBeCloseTo(21, 5)
    expect(contrastRatio(white, white)).toBeCloseTo(1, 5)
  })

  it('is symmetric', () => {
    const a = { h: 221, s: 83, l: 48 }
    const b = { h: 0, s: 0, l: 100 }
    expect(contrastRatio(a, b)).toBeCloseTo(contrastRatio(b, a), 10)
  })
})
