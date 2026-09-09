import { useRef, useState } from 'react'
import { RouterProvider } from 'react-router-dom'
import Providers from './app/providers'
import { createAppRouter } from './app/router'
import { useAuth } from './context/AuthContext'
import LoginPage from './components/auth/LoginPage'
import LogoutPage from './components/auth/LogoutPage'
import ForgotPasswordPage from './components/auth/ForgotPasswordPage'
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
  const { user, isLoading, justLoggedOut, dismissJustLoggedOut } = useAuth()
  const [showForgotPassword, setShowForgotPassword] = useState(false)
  // Built the first time it is actually needed, not on the first render of this component — see
  // the comment on createAppRouter for why that timing is what makes an SSO landing work.
  const routerRef = useRef<ReturnType<typeof createAppRouter> | null>(null)

  if (isLoading) {
    return (
      <div className="flex min-h-dvh items-center justify-center bg-background">
        <Spinner size="lg" label="Signing you in" />
      </div>
    )
  }

  if (!user) {
    // Checked before anything else a signed-out visitor might see, including the SSO-enforced
    // auto-redirect inside LoginPage: someone who just signed out gets confirmation of that, not
    // an immediate bounce back into the identity provider.
    if (justLoggedOut) return <LogoutPage onLoginClick={dismissJustLoggedOut} />
    return showForgotPassword
      ? <ForgotPasswordPage onBack={() => setShowForgotPassword(false)} />
      : <LoginPage onForgotPassword={() => setShowForgotPassword(true)} />
  }
  if (user.mustChangePassword) return <ChangePasswordPage />
  if (!user.name) return <CompleteProfilePage />

  if (!routerRef.current) {
    routerRef.current = createAppRouter()
  }
  return <RouterProvider router={routerRef.current} />
}
