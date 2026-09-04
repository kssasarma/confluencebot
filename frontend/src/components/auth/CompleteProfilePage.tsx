import { useState, type FormEvent } from 'react'
import { useAuth } from '../../context/AuthContext'
import AuthLayout from './AuthLayout'
import Input from '../ui/Input'
import Button from '../ui/Button'

/** Gates on a missing name the same way App.tsx gates on mustChangePassword: one thing, once. */
export default function CompleteProfilePage() {
  const { updateName } = useAuth()
  const [name, setName] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!name.trim()) { setError('Enter your name'); return }
    setError('')
    setLoading(true)
    try {
      await updateName(name.trim())
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save your name')
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout title="Tell us who you are" subtitle="Add your name before continuing.">
      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
        <Input label="Your name" value={name} onChange={e => setName(e.target.value)} required autoFocus />
        {error && <p role="alert" className="text-sm text-danger-emphasis">{error}</p>}
        <Button type="submit" loading={loading} block className="mt-1">Continue</Button>
      </form>
    </AuthLayout>
  )
}
