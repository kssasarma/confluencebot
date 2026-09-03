import { useState, type ReactNode } from 'react'
import { Check, Copy } from 'lucide-react'
import { cn } from '../../lib/cn'
import IconButton from '../ui/IconButton'

/**
 * A fenced code block with its own copy button.
 *
 * This overrides `pre`, not `code`. Overriding `code` — which is the obvious-looking choice — has
 * already been wrapped in a `<pre>` by react-markdown, so returning another produces
 * `<pre><pre><code>`: invalid HTML, a React warning, and styling that half-applies.
 */
export default function CodeBlock({ children }: { children?: ReactNode }) {
  const [copied, setCopied] = useState(false)

  async function copy(event: React.MouseEvent<HTMLButtonElement>) {
    const code = event.currentTarget
      .closest('[data-code-block]')
      ?.querySelector('code')?.textContent

    if (!code) return

    try {
      await navigator.clipboard.writeText(code)
      setCopied(true)
      setTimeout(() => setCopied(false), 1600)
    } catch {
      // Clipboard access is denied outside a secure context and in some locked-down browsers.
      // The code is selectable either way, so there is nothing useful to say about it.
    }
  }

  return (
    <div data-code-block className="group relative my-3">
      <pre
        className={cn(
          'overflow-x-auto rounded-lg border border-border bg-background p-3',
          'text-2xs leading-relaxed',
        )}
      >
        {children}
      </pre>

      <IconButton
        size="sm"
        variant="subtle"
        label={copied ? 'Copied' : 'Copy code'}
        icon={copied ? <Check size={13} /> : <Copy size={13} />}
        onClick={copy}
        className={cn(
          'absolute right-2 top-2 opacity-0 transition-opacity',
          'group-hover:opacity-100 focus-visible:opacity-100',
        )}
      />
    </div>
  )
}
