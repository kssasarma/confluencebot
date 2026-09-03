import { Suspense, lazy, type ComponentProps } from 'react'
import type MarkdownContent from './MarkdownContent'

/**
 * The markdown renderer, loaded on demand.
 *
 * The parser, the GFM extensions and the highlighting grammars are the largest dependency in the
 * application by a wide margin — and none of it is needed to render the sign-in screen, the empty
 * conversation, or the settings page. Splitting it keeps the initial download inside the budget.
 *
 * Two things stop the split from being visible:
 *
 *  - **The fallback is the answer.** While the chunk loads, the raw markdown is shown as
 *    pre-wrapped text. It is legible, it is the same words, and it does not shift the layout
 *    the way a spinner would.
 *  - **It is prefetched.** {@link prefetchMarkdown} is called when a conversation opens, so the
 *    chunk is almost always in cache by the time the first token arrives.
 */

const Markdown = lazy(() => import('./MarkdownContent'))

type MarkdownProps = ComponentProps<typeof MarkdownContent>

/** Warms the chunk without rendering anything. Safe to call repeatedly. */
export function prefetchMarkdown(): void {
  void import('./MarkdownContent')
}

export default function LazyMarkdown(props: MarkdownProps) {
  return (
    <Suspense
      fallback={
        <p className="whitespace-pre-wrap text-sm leading-relaxed">{props.content}</p>
      }
    >
      <Markdown {...props} />
    </Suspense>
  )
}
