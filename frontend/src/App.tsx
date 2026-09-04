import { RouterProvider } from 'react-router-dom'
import Providers from './app/providers'
import { router } from './app/router'
import { useAuth } from './context/AuthContext'
import LoginPage from './components/auth/LoginPage'
import ChangePasswordPage from './components/auth/ChangePasswordPage'
import CompleteProfilePage from './components/auth/CompleteProfilePage'
import ErrorBoundary from './components/ui/ErrorBoundary'
import Spinner from './components/ui/Spinner'

/**
 * The application root.
 *
 * The authentication gate sits outside the router on purpose: a signed-out visitor has no routes,
 * and a user who must change their password or has not set a name yet has exactly one thing to
 * do. Routing only begins once there is an application to route around.
 */
export default function App() {
  return (
    <ErrorBoundary title="The application could not start">
      <Providers>
        <AuthenticatedApp />
      </Providers>
    </ErrorBoundary>
  )
}

function AuthenticatedApp() {
  const { user, isLoading } = useAuth()

  if (isLoading) {
    return (
      <div className="flex min-h-dvh items-center justify-center bg-background">
        <Spinner size="lg" label="Signing you in" />
      </div>
    )
  }

  if (!user) return <LoginPage />
  if (user.mustChangePassword) return <ChangePasswordPage />
  if (!user.name) return <CompleteProfilePage />

  return <RouterProvider router={router} />
}
