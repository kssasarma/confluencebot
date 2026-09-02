import { useState, useEffect, useCallback } from 'react'
import { X, UserPlus, Play, RefreshCw, Ban, Check } from 'lucide-react'
import { useAuth } from '../../context/AuthContext'
import {
  listUsers, createUser, setUserEnabled, ingestSpace, ingestPage, listJobs,
  type AdminUser, type IngestionJob,
} from '../../services/adminService'
import { cn } from '../../lib/cn'

interface AdminPageProps {
  onClose: () => void
}

type Tab = 'users' | 'ingestion'

export default function AdminPage({ onClose }: AdminPageProps) {
  const [activeTab, setActiveTab] = useState<Tab>('users')

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="bg-background border border-border rounded-xl shadow-2xl w-full max-w-3xl max-h-[85vh] flex flex-col">
        <div className="flex items-center justify-between px-6 py-4 border-b border-border">
          <h2 className="text-lg font-semibold text-foreground">Admin Panel</h2>
          <button onClick={onClose} className="p-1.5 rounded-md hover:bg-surface-hover text-muted-foreground">
            <X size={18} />
          </button>
        </div>

        <div className="flex gap-1 px-6 pt-4">
          {(['users', 'ingestion'] as Tab[]).map(tab => (
            <button
              key={tab}
              onClick={() => setActiveTab(tab)}
              className={cn(
                'px-4 py-2 text-sm font-medium rounded-lg capitalize transition-colors',
                activeTab === tab
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:bg-surface-hover',
              )}
            >
              {tab === 'users' ? 'Users' : 'Ingestion'}
            </button>
          ))}
        </div>

        <div className="flex-1 overflow-y-auto px-6 py-4">
          {activeTab === 'users' ? <UsersTab /> : <IngestionTab />}
        </div>
      </div>
    </div>
  )
}

function UsersTab() {
  const { token } = useAuth()
  const [users, setUsers] = useState<AdminUser[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [newEmail, setNewEmail] = useState('')
  const [newRole, setNewRole] = useState('USER')
  const [creating, setCreating] = useState(false)
  const [created, setCreated] = useState<{ email: string; tempPassword: string } | null>(null)

  const load = useCallback(async () => {
    if (!token) return
    try {
      setLoading(true)
      setError(null)
      setUsers(await listUsers(token))
    } catch (e) {
      setError(String(e))
    } finally {
      setLoading(false)
    }
  }, [token])

  useEffect(() => { load() }, [load])

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault()
    if (!token || !newEmail.trim()) return
    try {
      setCreating(true)
      setError(null)
      const result = await createUser(token, newEmail.trim(), newRole)
      setCreated({ email: result.user.email, tempPassword: result.tempPassword })
      setNewEmail('')
      setNewRole('USER')
      await load()
    } catch (e) {
      setError(String(e))
    } finally {
      setCreating(false)
    }
  }

  async function handleToggleEnabled(user: AdminUser) {
    if (!token) return
    try {
      const updated = await setUserEnabled(token, user.id, !user.enabled)
      setUsers(prev => prev.map(u => u.id === updated.id ? updated : u))
    } catch (e) {
      setError(String(e))
    }
  }

  return (
    <div className="space-y-6">
      {created && (
        <div className="bg-green-500/10 border border-green-500/30 rounded-lg p-4 text-sm">
          <p className="font-medium text-green-600 dark:text-green-400 mb-1">User created</p>
          <p className="text-muted-foreground">Email: <span className="text-foreground font-mono">{created.email}</span></p>
          <p className="text-muted-foreground">Temp password: <span className="text-foreground font-mono">{created.tempPassword}</span></p>
          <p className="text-xs text-muted-foreground mt-1">Share this password securely. The user must change it on first login.</p>
          <button onClick={() => setCreated(null)} className="mt-2 text-xs text-muted-foreground hover:text-foreground">Dismiss</button>
        </div>
      )}

      <form onSubmit={handleCreate} className="flex gap-2 items-end">
        <div className="flex-1">
          <label className="block text-xs text-muted-foreground mb-1">Email</label>
          <input
            type="email"
            value={newEmail}
            onChange={e => setNewEmail(e.target.value)}
            placeholder="user@example.com"
            required
            className="w-full px-3 py-2 rounded-lg border border-border bg-surface text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
          />
        </div>
        <div>
          <label className="block text-xs text-muted-foreground mb-1">Role</label>
          <select
            value={newRole}
            onChange={e => setNewRole(e.target.value)}
            className="px-3 py-2 rounded-lg border border-border bg-surface text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
          >
            <option value="USER">User</option>
            <option value="ADMIN">Admin</option>
          </select>
        </div>
        <button
          type="submit"
          disabled={creating || !newEmail.trim()}
          className="flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-sm font-medium hover:opacity-90 disabled:opacity-50 transition-opacity"
        >
          <UserPlus size={15} />
          {creating ? 'Creating…' : 'Add user'}
        </button>
      </form>

      {error && <p className="text-sm text-red-500">{error}</p>}

      {loading ? (
        <p className="text-sm text-muted-foreground">Loading users…</p>
      ) : (
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-border text-left">
              <th className="pb-2 font-medium text-muted-foreground">Email</th>
              <th className="pb-2 font-medium text-muted-foreground">Role</th>
              <th className="pb-2 font-medium text-muted-foreground">Status</th>
              <th className="pb-2 font-medium text-muted-foreground">Must change pwd</th>
              <th className="pb-2" />
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {users.map(u => (
              <tr key={u.id} className="text-foreground">
                <td className="py-2.5 pr-4 font-mono text-xs">{u.email}</td>
                <td className="py-2.5 pr-4">
                  <span className={cn(
                    'px-2 py-0.5 rounded text-xs font-medium',
                    u.role === 'ADMIN' ? 'bg-purple-500/15 text-purple-600 dark:text-purple-400' : 'bg-blue-500/15 text-blue-600 dark:text-blue-400',
                  )}>{u.role}</span>
                </td>
                <td className="py-2.5 pr-4">
                  <span className={cn(
                    'px-2 py-0.5 rounded text-xs font-medium',
                    u.enabled ? 'bg-green-500/15 text-green-600 dark:text-green-400' : 'bg-red-500/15 text-red-500',
                  )}>{u.enabled ? 'Active' : 'Disabled'}</span>
                </td>
                <td className="py-2.5 pr-4 text-muted-foreground text-xs">{u.mustChangePassword ? 'Yes' : 'No'}</td>
                <td className="py-2.5 text-right">
                  <button
                    onClick={() => handleToggleEnabled(u)}
                    title={u.enabled ? 'Disable user' : 'Enable user'}
                    className="p-1.5 rounded hover:bg-surface-hover text-muted-foreground"
                  >
                    {u.enabled ? <Ban size={14} /> : <Check size={14} />}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

const STATUS_COLOR: Record<string, string> = {
  PENDING: 'text-yellow-600 dark:text-yellow-400',
  RUNNING: 'text-blue-600 dark:text-blue-400',
  COMPLETED: 'text-green-600 dark:text-green-400',
  FAILED: 'text-red-500',
}

function IngestionTab() {
  const { token } = useAuth()
  const [jobs, setJobs] = useState<IngestionJob[]>([])
  const [loadingJobs, setLoadingJobs] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [spaceKey, setSpaceKey] = useState('')
  const [force, setForce] = useState(false)
  const [pageId, setPageId] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const loadJobs = useCallback(async () => {
    if (!token) return
    try {
      setLoadingJobs(true)
      setJobs(await listJobs(token))
    } catch {
      // silent
    } finally {
      setLoadingJobs(false)
    }
  }, [token])

  useEffect(() => { loadJobs() }, [loadJobs])

  async function handleIngestSpace(e: React.FormEvent) {
    e.preventDefault()
    if (!token) return
    try {
      setSubmitting(true)
      setError(null)
      await ingestSpace(token, spaceKey.trim(), force)
      setSpaceKey('')
      setForce(false)
      await loadJobs()
    } catch (e) {
      setError(String(e))
    } finally {
      setSubmitting(false)
    }
  }

  async function handleIngestPage(e: React.FormEvent) {
    e.preventDefault()
    if (!token || !pageId.trim()) return
    try {
      setSubmitting(true)
      setError(null)
      await ingestPage(token, pageId.trim())
      setPageId('')
      await loadJobs()
    } catch (e) {
      setError(String(e))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 gap-4">
        <form onSubmit={handleIngestSpace} className="border border-border rounded-lg p-4 space-y-3">
          <h3 className="text-sm font-semibold text-foreground">Ingest Space</h3>
          <div>
            <label className="block text-xs text-muted-foreground mb-1">Space key</label>
            <input
              value={spaceKey}
              onChange={e => setSpaceKey(e.target.value)}
              placeholder="e.g. ENG (leave blank for default)"
              className="w-full px-3 py-2 rounded-lg border border-border bg-surface text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
            />
          </div>
          <label className="flex items-center gap-2 text-sm text-muted-foreground cursor-pointer">
            <input type="checkbox" checked={force} onChange={e => setForce(e.target.checked)} className="rounded" />
            Force re-ingest unchanged pages
          </label>
          <button
            type="submit"
            disabled={submitting}
            className="flex items-center gap-2 w-full justify-center px-4 py-2 rounded-lg bg-primary text-primary-foreground text-sm font-medium hover:opacity-90 disabled:opacity-50 transition-opacity"
          >
            <Play size={14} />
            {submitting ? 'Submitting…' : 'Start ingestion'}
          </button>
        </form>

        <form onSubmit={handleIngestPage} className="border border-border rounded-lg p-4 space-y-3">
          <h3 className="text-sm font-semibold text-foreground">Ingest Single Page</h3>
          <div>
            <label className="block text-xs text-muted-foreground mb-1">Page ID</label>
            <input
              value={pageId}
              onChange={e => setPageId(e.target.value)}
              placeholder="e.g. 131073"
              required
              className="w-full px-3 py-2 rounded-lg border border-border bg-surface text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
            />
          </div>
          <p className="text-xs text-muted-foreground">Find the page ID in the Confluence page URL.</p>
          <button
            type="submit"
            disabled={submitting || !pageId.trim()}
            className="flex items-center gap-2 w-full justify-center px-4 py-2 rounded-lg bg-primary text-primary-foreground text-sm font-medium hover:opacity-90 disabled:opacity-50 transition-opacity"
          >
            <Play size={14} />
            {submitting ? 'Submitting…' : 'Ingest page'}
          </button>
        </form>
      </div>

      {error && <p className="text-sm text-red-500">{error}</p>}

      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-foreground">Job History</h3>
        <button
          onClick={loadJobs}
          className="flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground"
        >
          <RefreshCw size={13} />
          Refresh
        </button>
      </div>

      {loadingJobs ? (
        <p className="text-sm text-muted-foreground">Loading jobs…</p>
      ) : jobs.length === 0 ? (
        <p className="text-sm text-muted-foreground">No jobs yet.</p>
      ) : (
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-border text-left">
              <th className="pb-2 font-medium text-muted-foreground">Type</th>
              <th className="pb-2 font-medium text-muted-foreground">Target</th>
              <th className="pb-2 font-medium text-muted-foreground">Status</th>
              <th className="pb-2 font-medium text-muted-foreground">Pages</th>
              <th className="pb-2 font-medium text-muted-foreground">Chunks</th>
              <th className="pb-2 font-medium text-muted-foreground">Started</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {jobs.map(j => (
              <tr key={j.jobId}>
                <td className="py-2 pr-3 text-muted-foreground text-xs">{j.jobType}</td>
                <td className="py-2 pr-3 font-mono text-xs">{j.spaceKey ?? j.pageId ?? '—'}</td>
                <td className="py-2 pr-3">
                  <span className={cn('text-xs font-medium', STATUS_COLOR[j.status] ?? 'text-muted-foreground')}>
                    {j.status}
                  </span>
                </td>
                <td className="py-2 pr-3 text-muted-foreground text-xs">{j.pagesProcessed ?? '—'}</td>
                <td className="py-2 pr-3 text-muted-foreground text-xs">{j.chunksStored ?? '—'}</td>
                <td className="py-2 text-muted-foreground text-xs">
                  {j.startedAt ? new Date(j.startedAt).toLocaleTimeString() : '—'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
