import { useState, type FormEvent } from 'react'
import { useAuth } from '../../context/AuthContext'
import AuthLayout from './AuthLayout'
import Input from '../ui/Input'
import Button from '../ui/Button'

export default function ChangePasswordPage() {
  const { changePassword } = useAuth()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (next !== confirm) { setError('Passwords do not match'); return }
    if (next.length < 8) { setError('Password must be at least 8 characters'); return }
    setError('')
    setLoading(true)
    try {
      await changePassword(current, next)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to change password')
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout title="Change your password" subtitle="You must set a new password before continuing.">
      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
        <Input label="Current password" type="password" value={current} onChange={e => setCurrent(e.target.value)} required />
        <Input label="New password" type="password" value={next} onChange={e => setNext(e.target.value)} required />
        <Input label="Confirm new password" type="password" value={confirm} onChange={e => setConfirm(e.target.value)} required />
        {error && <p role="alert" className="text-sm text-danger-emphasis">{error}</p>}
        <Button type="submit" loading={loading} block className="mt-1">Update password</Button>
      </form>
    </AuthLayout>
  )
}
