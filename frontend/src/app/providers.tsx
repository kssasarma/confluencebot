import { useState, type ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MotionConfig } from 'framer-motion'
import { ApiError } from '../services/http'
import { ThemeProvider } from '../context/ThemeContext'
import { AuthProvider } from '../context/AuthContext'
import { ToastProvider } from '../components/ui/Toast'
import { ConfirmProvider } from '../components/ui/ConfirmDialog'

/**
 * Every cross-cutting provider, in the order they depend on each other.
 *
 * `ToastProvider` sits above `ConfirmProvider` and the query client because both report failures
 * through it. `MotionConfig reducedMotion="user"` is the single place the motion preference is
 * honoured for framer-motion — the CSS side is handled by the media query in `index.css`.
 */
export default function Providers({ children }: { children: ReactNode }) {
  // Created inside the component so each test gets an isolated cache; a module-level client
  // leaks state between tests and produces failures that depend on file order.
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        // Retrying a 401 or a 404 wastes three round trips to reach the same answer. Only
        // genuinely transient failures are worth a second attempt.
        retry: (failureCount, error) => {
          if (error instanceof ApiError && error.status < 500) return false
          return failureCount < 2
        },
        refetchOnWindowFocus: true,
      },
      mutations: { retry: false },
    },
  }))

  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <MotionConfig reducedMotion="user">
          <ToastProvider>
            <ConfirmProvider>
              <AuthProvider>{children}</AuthProvider>
            </ConfirmProvider>
          </ToastProvider>
        </MotionConfig>
      </ThemeProvider>
    </QueryClientProvider>
  )
}
