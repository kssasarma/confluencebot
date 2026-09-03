import { Component, type ErrorInfo, type ReactNode } from 'react'
import { AlertTriangle, RotateCcw } from 'lucide-react'
import Button from './Button'
import EmptyState from './EmptyState'

/**
 * Stops one broken render from taking the whole application with it.
 *
 * Without a boundary anywhere, a single malformed markdown table — or any payload the renderer
 * did not expect — unmounts the entire tree and leaves a white page whose only recovery is a
 * manual refresh, losing the transcript on screen.
 *
 * Two are used in this app, at different granularities:
 *
 *  - one at the root, as the last line of defence;
 *  - one around the conversation, **keyed by chat id**. The key matters: without it React keeps
 *    the boundary's error state across a chat switch, so a crash in one conversation makes every
 *    other conversation look broken too. Remounting on the key clears it.
 */

interface ErrorBoundaryProps {
  children: ReactNode
  /** Rendered instead of the default panel. Receives a reset that clears the error. */
  fallback?: (error: Error, reset: () => void) => ReactNode
  /** Reported failures — wire to whatever the deployment uses for error tracking. */
  onError?: (error: Error, info: ErrorInfo) => void
  title?: string
}

interface ErrorBoundaryState {
  error: Error | null
}

export default class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Logged even in production: a boundary that swallows the stack turns a reproducible bug into
    // a support ticket that says "it went blank".
    console.error('Render failed:', error, info.componentStack)
    this.props.onError?.(error, info)
  }

  private reset = () => this.setState({ error: null })

  render() {
    const { error } = this.state
    if (!error) return this.props.children

    if (this.props.fallback) return this.props.fallback(error, this.reset)

    return (
      <div className="flex h-full items-center justify-center p-6">
        <EmptyState
          tone="error"
          icon={<AlertTriangle size={18} />}
          title={this.props.title ?? 'Something went wrong displaying this'}
          description={error.message || 'An unexpected error occurred.'}
          action={
            <Button variant="secondary" onClick={this.reset}>
              <RotateCcw size={14} aria-hidden="true" />
              Try again
            </Button>
          }
        />
      </div>
    )
  }
}
