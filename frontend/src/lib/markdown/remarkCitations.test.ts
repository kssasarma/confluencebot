import { describe, expect, it } from 'vitest'
import { unified } from 'unified'
import remarkParse from 'remark-parse'
import { visit } from 'unist-util-visit'
import type { Root } from 'mdast'
import { closeUnterminatedCodeFence, remarkCitations } from './remarkCitations'

function markersIn(markdown: string, resolvable: number[]): number[] {
  const tree = unified()
    .use(remarkParse)
    .use(remarkCitations, { resolvable: new Set(resolvable) })
    .runSync(unified().use(remarkParse).parse(markdown)) as Root

  const found: number[] = []
  visit(tree, 'citation', (node: { marker: number }) => { found.push(node.marker) })
  return found
}

function textOf(markdown: string, resolvable: number[]): string {
  const tree = unified()
    .use(remarkParse)
    .use(remarkCitations, { resolvable: new Set(resolvable) })
    .runSync(unified().use(remarkParse).parse(markdown)) as Root

  let text = ''
  visit(tree, 'text', node => { text += node.value })
  return text
}

describe('remarkCitations', () => {
  it('marks a resolvable citation', () => {
    expect(markersIn('Restart the collector [2].', [1, 2])).toEqual([2])
  })

  it('marks every occurrence, including repeats', () => {
    expect(markersIn('First [1], then [2], and again [1].', [1, 2])).toEqual([1, 2, 1])
  })

  it('leaves an unresolvable marker as plain text rather than a dead link', () => {
    expect(markersIn('See [7] for details.', [1, 2])).toEqual([])
    expect(textOf('See [7] for details.', [1, 2])).toContain('[7]')
  })

  it('keeps the surrounding text intact', () => {
    expect(textOf('Run it [1] twice.', [1])).toBe('Run it  twice.')
  })

  it('ignores markers inside inline code', () => {
    expect(markersIn('Use `items[0]` and `matrix[1]`.', [0, 1])).toEqual([])
  })

  it('ignores markers inside a fenced code block', () => {
    expect(markersIn('```bash\necho ${args[1]}\n```', [1])).toEqual([])
  })

  it('does not rewrite a marker that is already a link', () => {
    expect(markersIn('See [1](https://example.com) here.', [1])).toEqual([])
  })

  it('does nothing when the answer cites nothing', () => {
    expect(markersIn('A plain answer with no citations.', [1, 2])).toEqual([])
  })
})

describe('closeUnterminatedCodeFence', () => {
  it('closes a fence that is still streaming', () => {
    expect(closeUnterminatedCodeFence('Run:\n```bash\nnpm ci'))
      .toBe('Run:\n```bash\nnpm ci\n```')
  })

  it('leaves balanced fences alone', () => {
    const balanced = 'Run:\n```bash\nnpm ci\n```\nDone.'
    expect(closeUnterminatedCodeFence(balanced)).toBe(balanced)
  })

  it('leaves text with no fences alone', () => {
    expect(closeUnterminatedCodeFence('No code here.')).toBe('No code here.')
  })
})
