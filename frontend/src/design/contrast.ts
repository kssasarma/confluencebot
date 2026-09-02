/**
 * WCAG 2.1 relative-luminance and contrast maths, plus a reader for the token stylesheet.
 *
 * Lives in `src/` rather than in the test folder because the contrast test asserts against the
 * <em>real</em> stylesheet: a duplicated copy of the palette would pass its own tests forever
 * while the app shipped different colours.
 */

export interface Hsl {
  h: number
  s: number
  l: number
}

/** Parses the bare-channel form the tokens are written in: `221 83% 48%`. */
export function parseHsl(value: string): Hsl | null {
  const match = value.trim().match(/^(-?[\d.]+)\s+(-?[\d.]+)%\s+(-?[\d.]+)%$/)
  if (!match) return null
  return { h: Number(match[1]), s: Number(match[2]), l: Number(match[3]) }
}

export function hslToRgb({ h, s, l }: Hsl): [number, number, number] {
  const hue = ((h % 360) + 360) % 360 / 360
  const sat = s / 100
  const light = l / 100

  if (sat === 0) return [light, light, light]

  const q = light < 0.5 ? light * (1 + sat) : light + sat - light * sat
  const p = 2 * light - q

  const channel = (t: number): number => {
    let shifted = t
    if (shifted < 0) shifted += 1
    if (shifted > 1) shifted -= 1
    if (shifted < 1 / 6) return p + (q - p) * 6 * shifted
    if (shifted < 1 / 2) return q
    if (shifted < 2 / 3) return p + (q - p) * (2 / 3 - shifted) * 6
    return p
  }

  return [channel(hue + 1 / 3), channel(hue), channel(hue - 1 / 3)]
}

/** WCAG 2.1 relative luminance. */
export function relativeLuminance(colour: Hsl): number {
  const linear = (c: number): number => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
  const [r, g, b] = hslToRgb(colour)
  return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
}

/** WCAG 2.1 contrast ratio, 1–21. Order of the arguments does not matter. */
export function contrastRatio(a: Hsl, b: Hsl): number {
  const la = relativeLuminance(a)
  const lb = relativeLuminance(b)
  const lighter = Math.max(la, lb)
  const darker = Math.min(la, lb)
  return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Extracts the custom properties of one selector block from a stylesheet.
 *
 * A deliberately small parser rather than a CSS AST: it only has to read `--name: value;` pairs
 * out of a block it is pointed at, and a dependency for that would be a dependency to keep.
 */
export function readTokens(css: string, selector: string): Record<string, Hsl> {
  const blockStart = css.indexOf(selector)
  if (blockStart < 0) throw new Error(`No "${selector}" block in the stylesheet`)

  const open = css.indexOf('{', blockStart)
  if (open < 0) throw new Error(`Malformed "${selector}" block`)

  let depth = 0
  let close = open
  for (let i = open; i < css.length; i++) {
    if (css[i] === '{') depth++
    else if (css[i] === '}' && --depth === 0) {
      close = i
      break
    }
  }

  const tokens: Record<string, Hsl> = {}
  const body = css.slice(open + 1, close)
  for (const [, name, value] of body.matchAll(/--([\w-]+)\s*:\s*([^;]+);/g)) {
    const parsed = parseHsl(value)
    if (parsed) tokens[name] = parsed
  }
  return tokens
}
