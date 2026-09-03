import { visit } from 'unist-util-visit'
import type { Parent, Root, RootContent, Text } from 'mdast'

/**
 * Rewrites `[1]`, `[2]` … in an answer into nodes a renderer can turn into links.
 *
 * The model is asked to cite excerpt numbers rather than page titles, because a title is not a
 * usable reference: `[Password Reset Guide]` is not markdown link syntax, so it renders as literal
 * text, and matching back to a page by title is fragile — titles repeat across spaces and contain
 * brackets and colons of their own. A number maps to a page unambiguously.
 *
 * This plugin only *marks* the markers. Resolving one to a page, and deciding what to render, is
 * the renderer's job — see `Citation`.
 *
 * Two things it deliberately does not do:
 *
 *  - **Touch code.** `visit` is given a filter that skips `inlineCode` and `code`, so an array
 *    index in a shell snippet stays an array index.
 *  - **Invent links.** A marker with no matching source is left as plain text. A citation that
 *    goes nowhere is worse than no citation, because it looks like it was checked.
 */

/** The node this plugin produces. Rendered by the `citation` component override. */
export interface CitationNode extends Parent {
  type: 'citation'
  marker: number
  data: {
    hName: 'cite-marker'
    /** hast spelling — `property-information` renders this as the `data-marker` attribute. */
    hProperties: { dataMarker: string }
  }
  children: []
}

const MARKER = /\[(\d{1,3})]/g

export interface RemarkCitationsOptions {
  /** Markers outside this set are left as text — see "invent links" above. */
  resolvable: ReadonlySet<number>
}

export function remarkCitations({ resolvable }: RemarkCitationsOptions) {
  return (tree: Root): void => {
    if (resolvable.size === 0) return

    visit(tree, 'text', (node: Text, index, parent) => {
      if (!parent || index === undefined) return
      // A marker inside an existing link is already a reference; rewriting it would nest anchors.
      // Code needs no guard: `inlineCode` and `code` are mdast literals with no children, so a
      // text node never has one as its parent — their content is never visited here at all.
      if (parent.type === 'link' || parent.type === 'linkReference') return

      const replacement = split(node.value, resolvable)
      if (!replacement) return

      // The citation node is synthetic: it exists only between this plugin and the
      // renderer, so mdast's union of standard content types does not include it.
      parent.children.splice(index, 1, ...(replacement as unknown as RootContent[]))
      // Skip the nodes just inserted; revisiting them would loop forever on the same text.
      return index + replacement.length
    })
  }
}

/** @returns the node list to replace this text node with, or null when there is nothing to do. */
function split(value: string, resolvable: ReadonlySet<number>): Array<Text | CitationNode> | null {
  MARKER.lastIndex = 0

  const nodes: Array<Text | CitationNode> = []
  let cursor = 0
  let found = false

  for (const match of value.matchAll(MARKER)) {
    const marker = Number(match[1])
    if (!resolvable.has(marker)) continue

    const start = match.index ?? 0
    if (start > cursor) nodes.push({ type: 'text', value: value.slice(cursor, start) })

    nodes.push({
      type: 'citation',
      marker,
      children: [],
      data: { hName: 'cite-marker', hProperties: { dataMarker: String(marker) } },
    })

    cursor = start + match[0].length
    found = true
  }

  if (!found) return null
  if (cursor < value.length) nodes.push({ type: 'text', value: value.slice(cursor) })
  return nodes
}

/**
 * Closes a code fence that is still being streamed.
 *
 * Mid-fence, the markdown parser sees an odd number of ``` and renders everything after the
 * opening fence as a paragraph — then, one token later, as a code block. The result is a visible
 * flicker on every answer containing a command, which for a documentation assistant is most of
 * them. Appending the closing fence to the *rendered copy* costs nothing and removes it.
 */
export function closeUnterminatedCodeFence(markdown: string): string {
  const fences = markdown.match(/^```/gm)
  if (!fences || fences.length % 2 === 0) return markdown
  return `${markdown}\n\`\`\``
}
