import { useState, type FormEvent } from 'react'
import { useAuth } from '../../context/AuthContext'
import { requestPasswordReset, resetPassword } from '../../services/authService'
import AuthLayout from './AuthLayout'
import Input from '../ui/Input'
import Button from '../ui/Button'

/**
 * Self-service password reset, in two steps: request a code, then redeem it for a new password.
 *
 * Rendered outside the router, the same way `LoginPage` is — nobody signed out has any routes to
 * be on. `onBack` returns to sign-in; there is no dead end here, since an admin re-sharing a
 * temporary password (Settings → User Management) still works as the outage-recovery path when
 * mail is down, exactly as it always has for a brand-new user's welcome email.
 */
export default function ForgotPasswordPage({ onBack }: { onBack: () => void }) {
  const { applySession } = useAuth()
  const [step, setStep] = useState<'request' | 'reset'>('request')
  const [email, setEmail] = useState('')
  const [otp, setOtp] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function handleRequest(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const { emailSent } = await requestPasswordReset(email.trim())
      setStep('reset')
      setNotice(
        emailSent
          ? `If ${email.trim()} has an account, a reset code was just emailed to it.`
          : "We couldn't send that email right now — mail may be down. If you have an account, "
            + 'ask an admin to re-share a temporary password instead; that works even when email does not.',
      )
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not request a reset code')
    } finally {
      setLoading(false)
    }
  }

  async function handleReset(e: FormEvent) {
    e.preventDefault()
    if (newPassword !== confirmPassword) { setError('Passwords do not match'); return }
    if (newPassword.length < 8) { setError('Password must be at least 8 characters'); return }
    setError('')
    setLoading(true)
    try {
      applySession(await resetPassword(email.trim(), otp.trim(), newPassword))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not reset your password')
    } finally {
      setLoading(false)
    }
  }

  if (step === 'request') {
    return (
      <AuthLayout title="Reset your password" subtitle="We'll email you a one-time code.">
        <form onSubmit={handleRequest} className="flex flex-col gap-4">
          <Input
            label="Email"
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={e => setEmail(e.target.value)}
          />
          {error && <p role="alert" className="text-sm text-danger-emphasis">{error}</p>}
          <Button type="submit" loading={loading} block className="mt-1">
            Send reset code
          </Button>
          <Button type="button" variant="ghost" block onClick={onBack}>
            Back to sign in
          </Button>
        </form>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout title="Enter your reset code">
      <form onSubmit={handleReset} className="flex flex-col gap-4">
        {notice && <p role="status" className="text-sm text-muted-foreground">{notice}</p>}
        <Input
          label="Reset code"
          inputMode="numeric"
          autoComplete="one-time-code"
          required
          value={otp}
          onChange={e => setOtp(e.target.value)}
        />
        <Input
          label="New password"
          type="password"
          autoComplete="new-password"
          required
          value={newPassword}
          onChange={e => setNewPassword(e.target.value)}
        />
        <Input
          label="Confirm new password"
          type="password"
          autoComplete="new-password"
          required
          value={confirmPassword}
          onChange={e => setConfirmPassword(e.target.value)}
        />
        {error && <p role="alert" className="text-sm text-danger-emphasis">{error}</p>}
        <Button type="submit" loading={loading} block className="mt-1">
          Reset password
        </Button>
        <Button type="button" variant="ghost" block onClick={() => setStep('request')}>
          Didn't get a code? Try again
        </Button>
      </form>
    </AuthLayout>
  )
}
