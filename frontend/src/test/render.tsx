import { type ReactElement, type ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { render, type RenderOptions, type RenderResult } from '@testing-library/react'
import { ThemeProvider } from '../context/ThemeContext'
import { AuthProvider } from '../context/AuthContext'
import { ToastProvider } from '../components/ui/Toast'
import { ConfirmProvider } from '../components/ui/ConfirmDialog'

/**
 * Renders a component with the providers it can reasonably expect to be inside.
 *
 * A fresh `QueryClient` per render, with retries off: a test that exercises a failure would
 * otherwise wait out two retries before asserting, and a shared client would leak one test's
 * cache into the next.
 *
 * `AuthProvider` is here because the chat surface greets the reader by name and so genuinely
 * depends on it. With no token in storage it settles immediately on a signed-out session and
 * makes no request, which is the right default for a component test that is not about signing in.
 */
export function renderWithProviders(
  ui: ReactElement,
  { route = '/', ...options }: RenderOptions & { route?: string } = {},
): RenderResult {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  })

  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <ThemeProvider>
          <ToastProvider>
            <ConfirmProvider>
              <AuthProvider>
                <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter>
              </AuthProvider>
            </ConfirmProvider>
          </ToastProvider>
        </ThemeProvider>
      </QueryClientProvider>
    )
  }

  return render(ui, { wrapper: Wrapper, ...options })
}
