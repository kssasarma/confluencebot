import { useEffect, useState, type FormEvent } from 'react'
import { KeyRound } from 'lucide-react'
import { useAuth } from '../../context/AuthContext'
import { requestPasswordSignIn, wantsPasswordSignIn } from '../../lib/sso'
import AuthLayout from './AuthLayout'
import Input from '../ui/Input'
import Button from '../ui/Button'
import Spinner from '../ui/Spinner'

export default function LoginPage({ onForgotPassword }: { onForgotPassword: () => void }) {
  const { login, sso, ssoError, dismissSsoError } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [showPasswordForm, setShowPasswordForm] = useState(wantsPasswordSignIn)

  const ssoEnabled = sso?.enabled && !!sso.authorizationUrl
  // A deployment can skip the button entirely, but never the escape hatch: a directory outage
  // must not also strand the bootstrap administrator, who exists in no directory. A failed round
  // trip (ssoError set) holds the screen too — leaving again immediately would bounce whoever just
  // saw a rejection straight back to the provider without ever reading why.
  const ssoEnforced = !!ssoEnabled && !!sso!.enforced && !showPasswordForm && !ssoError

  useEffect(() => {
    if (ssoEnforced) {
      window.location.assign(sso!.authorizationUrl!)
    }
  }, [ssoEnforced, sso])

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    dismissSsoError()
    setLoading(true)
    try {
      await login(email, password)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed')
    } finally {
      setLoading(false)
    }
  }

  // A full page load, not a fetch: the identity provider answers with redirects and its own
  // screens, and it has a session cookie of its own to consult that this origin cannot see.
  function startSso() {
    dismissSsoError()
    window.location.assign(sso!.authorizationUrl!)
  }

  function usePasswordInstead() {
    requestPasswordSignIn()
    setShowPasswordForm(true)
  }

  if (ssoEnforced) {
    // The effect above is already leaving; this is what shows for the instant before it does —
    // long enough to offer the one way to stay.
    return (
      <AuthLayout title="Sign in to your account">
        <div className="flex flex-col items-center gap-4 py-6 text-center">
          <Spinner size="lg" />
          <p className="text-sm text-muted-foreground">
            Redirecting you to {sso!.providerName ?? 'your identity provider'}…
          </p>
          <button
            type="button"
            onClick={usePasswordInstead}
            className="text-sm text-muted-foreground underline-offset-2 hover:text-foreground hover:underline"
          >
            Sign in with a password instead
          </button>
        </div>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout title="Sign in to your account">
      {ssoError && (
        <p role="alert" className="mb-4 text-sm text-danger-emphasis">{ssoError}</p>
      )}

      {ssoEnabled && (
        <>
          <Button variant="secondary" block onClick={startSso}>
            <KeyRound className="h-4 w-4" aria-hidden="true" />
            Continue with {sso!.providerName ?? 'single sign-on'}
          </Button>
          <div className="my-5 flex items-center gap-3" aria-hidden="true">
            <span className="h-px flex-1 bg-border" />
            <span className="text-2xs uppercase tracking-wide text-muted-foreground">or</span>
            <span className="h-px flex-1 bg-border" />
          </div>
        </>
      )}

      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
        <Input
          label="Email"
          type="email"
          autoComplete="email"
          value={email}
          onChange={e => setEmail(e.target.value)}
          required
        />
        <Input
          label="Password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={e => setPassword(e.target.value)}
          required
        />
        {error && <p role="alert" className="text-sm text-danger-emphasis">{error}</p>}
        <Button type="submit" loading={loading} block className="mt-1">
          Sign in
        </Button>
        <button
          type="button"
          onClick={onForgotPassword}
          className="text-center text-sm text-muted-foreground underline-offset-2 hover:text-foreground hover:underline"
        >
          Forgot password?
        </button>
      </form>
    </AuthLayout>
  )
}
