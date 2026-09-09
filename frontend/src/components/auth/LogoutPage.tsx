import { CheckCircle2 } from 'lucide-react'
import AuthLayout from './AuthLayout'
import Button from '../ui/Button'

/**
 * Shown in place of the sign-in screen for the one render right after `logout()` runs, so signing
 * out has a destination of its own rather than silently reusing the sign-in screen a visitor who
 * was never authenticated would also see. `onLoginClick` is what actually moves on to `LoginPage`
 * — this page does not navigate on its own, the same way `ForgotPasswordPage`'s `onBack` doesn't.
 */
export default function LogoutPage({ onLoginClick }: { onLoginClick: () => void }) {
  return (
    <AuthLayout title="You've been signed out">
      <div className="flex flex-col items-center gap-4 py-2 text-center">
        <div className="flex h-12 w-12 items-center justify-center rounded-full bg-primary-soft">
          <CheckCircle2 className="h-6 w-6 text-primary-emphasis" aria-hidden="true" />
        </div>
        <p className="text-sm text-muted-foreground">
          You have successfully signed out of your account. It's safe to close this tab, or sign
          back in below.
        </p>
        <Button block onClick={onLoginClick}>
          Login here
        </Button>
      </div>
    </AuthLayout>
  )
}
