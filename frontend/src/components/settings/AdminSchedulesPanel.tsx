import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Pencil, Play, Plus, RefreshCw, Trash2, X } from 'lucide-react'
import {
  deleteSchedule,
  listSchedules,
  triggerScheduleNow,
  upsertSchedule,
  type IngestionSchedule,
} from '../../services/adminService'
import { queryKeys } from '../../services/queryKeys'
import { toMessage } from '../../lib/errors'
import { useToast } from '../ui/Toast'
import { useConfirm } from '../ui/ConfirmDialog'
import { absoluteTime, relativeTime } from '../../lib/time'
import Badge from '../ui/Badge'
import Button from '../ui/Button'
import EmptyState from '../ui/EmptyState'
import IconButton from '../ui/IconButton'
import Input from '../ui/Input'
import { SkeletonText } from '../ui/Skeleton'

const INTERVAL_PRESETS = [
  { label: '6 h', value: 6 },
  { label: '12 h', value: 12 },
  { label: '24 h (daily)', value: 24 },
  { label: '48 h', value: 48 },
  { label: '168 h (weekly)', value: 168 },
] as const

function nextRunLabel(nextRunAt: string, enabled: boolean): string {
  if (!enabled) return '—'
  const ms = new Date(nextRunAt).getTime() - Date.now()
  if (ms <= 60_000) return 'soon'
  const h = Math.floor(ms / 3_600_000)
  const m = Math.floor((ms % 3_600_000) / 60_000)
  return h > 0 ? `in ${h}h ${m}m` : `in ${m}m`
}

type FormMode = 'create' | 'edit'

interface ScheduleForm {
  mode: FormMode
  spaceKey: string
  intervalPreset: string
  customInterval: string
  enabled: boolean
  force: boolean
}

const BLANK_FORM: ScheduleForm = {
  mode: 'create',
  spaceKey: '',
  intervalPreset: '24',
  customInterval: '',
  enabled: true,
  force: false,
}

function formFromSchedule(s: IngestionSchedule): ScheduleForm {
  const isPreset = INTERVAL_PRESETS.some(p => p.value === s.intervalHours)
  return {
    mode: 'edit',
    spaceKey: s.spaceKey,
    intervalPreset: isPreset ? String(s.intervalHours) : 'custom',
    customInterval: isPreset ? '' : String(s.intervalHours),
    enabled: s.enabled,
    force: s.force,
  }
}

function resolvedInterval(form: ScheduleForm): number {
  if (form.intervalPreset === 'custom') return parseInt(form.customInterval, 10) || 0
  return parseInt(form.intervalPreset, 10)
}

/** Schedule management section — lets admins create, edit, delete and manually trigger auto-ingestion schedules. */
export default function AdminSchedulesPanel() {
  const queryClient = useQueryClient()
  const toast = useToast()
  const confirm = useConfirm()

  const [form, setForm] = useState<ScheduleForm | null>(null)

  const schedules = useQuery({
    queryKey: queryKeys.ingestionSchedules,
    queryFn: listSchedules,
  })

  const refresh = () => queryClient.invalidateQueries({ queryKey: queryKeys.ingestionSchedules })

  const upsert = useMutation({
    mutationFn: (f: ScheduleForm) =>
      upsertSchedule(f.spaceKey.trim(), {
        intervalHours: resolvedInterval(f),
        enabled: f.enabled,
        force: f.force,
      }),
    onSuccess: (_data, f) => {
      toast.success(
        f.mode === 'create' ? 'Schedule created' : 'Schedule updated',
        `Auto-ingestion for ${f.spaceKey.trim()} has been saved.`,
      )
      setForm(null)
      void refresh()
    },
    onError: error => toast.error('Could not save schedule', toMessage(error, 'Please try again.')),
  })

  const remove = useMutation({
    mutationFn: deleteSchedule,
    onSuccess: (_data, spaceKey) => {
      toast.success('Schedule removed', `Auto-ingestion for ${spaceKey} has been stopped.`)
      void refresh()
    },
    onError: error => toast.error('Could not remove schedule', toMessage(error, 'Please try again.')),
  })

  const trigger = useMutation({
    mutationFn: triggerScheduleNow,
    onSuccess: (_data, spaceKey) => {
      toast.success('Job submitted', `An immediate ingestion run for ${spaceKey} has been queued.`)
      queryClient.invalidateQueries({ queryKey: ['admin', 'jobs'] })
    },
    onError: error => toast.error('Could not trigger ingestion', toMessage(error, 'Please try again.')),
  })

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (!form) return
    const interval = resolvedInterval(form)
    if (!interval || interval < 1 || interval > 8760) {
      toast.error('Invalid interval', 'Interval must be between 1 and 8760 hours.')
      return
    }
    upsert.mutate(form)
  }

  function openEdit(s: IngestionSchedule) {
    setForm(formFromSchedule(s))
  }

  async function handleDelete(s: IngestionSchedule) {
    const ok = await confirm({
      title: `Remove schedule for ${s.spaceKey}?`,
      description:
        'Auto-ingestion for this space will stop. Any currently running job completes normally.',
      confirmLabel: 'Remove',
      tone: 'danger',
    })
    if (ok) remove.mutate(s.spaceKey)
  }

  const isFormValid =
    form !== null &&
    form.spaceKey.trim().length > 0 &&
    resolvedInterval(form) >= 1 &&
    resolvedInterval(form) <= 8760

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-foreground">Auto-schedules</h2>
        <div className="flex gap-2">
          <Button
            size="sm"
            variant="ghost"
            onClick={refresh}
            disabled={schedules.isFetching}
          >
            <RefreshCw size={13} aria-hidden="true" />
            Refresh
          </Button>
          {!form && (
            <Button size="sm" onClick={() => setForm(BLANK_FORM)}>
              <Plus size={13} aria-hidden="true" />
              Add schedule
            </Button>
          )}
        </div>
      </div>

      {schedules.isLoading ? (
        <SkeletonText lines={3} />
      ) : !schedules.data?.length && !form ? (
        <EmptyState
          title="No auto-schedules yet"
          description="Add a schedule to have a Confluence space automatically re-ingested at a fixed interval."
        />
      ) : (
        schedules.data && schedules.data.length > 0 && (
          <div className="overflow-x-auto rounded-lg border border-border">
            <table className="w-full text-sm">
              <caption className="sr-only">Ingestion schedules</caption>
              <thead>
                <tr className="border-b border-border text-left">
                  <th scope="col" className="px-3 pb-2 pt-3 font-medium text-muted-foreground">Space</th>
                  <th scope="col" className="px-3 pb-2 pt-3 font-medium text-muted-foreground">Interval</th>
                  <th scope="col" className="px-3 pb-2 pt-3 font-medium text-muted-foreground">Status</th>
                  <th scope="col" className="px-3 pb-2 pt-3 font-medium text-muted-foreground">Force</th>
                  <th scope="col" className="px-3 pb-2 pt-3 font-medium text-muted-foreground">Last run</th>
                  <th scope="col" className="px-3 pb-2 pt-3 font-medium text-muted-foreground">Next run</th>
                  <th scope="col" className="px-3 pb-2 pt-3"><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {schedules.data.map(s => (
                  <tr key={s.id}>
                    <td className="px-3 py-2.5 font-mono text-xs font-semibold">{s.spaceKey}</td>
                    <td className="px-3 py-2.5 text-2xs text-muted-foreground">{s.intervalHours} h</td>
                    <td className="px-3 py-2.5">
                      <Badge tone={s.enabled ? 'success' : 'neutral'}>
                        {s.enabled ? 'Active' : 'Disabled'}
                      </Badge>
                    </td>
                    <td className="px-3 py-2.5 text-2xs text-muted-foreground">{s.force ? 'Yes' : 'No'}</td>
                    <td
                      className="px-3 py-2.5 text-2xs text-muted-foreground"
                      title={absoluteTime(s.lastRunAt) || undefined}
                    >
                      {s.lastRunAt ? relativeTime(s.lastRunAt) : '—'}
                    </td>
                    <td className="px-3 py-2.5 text-2xs text-muted-foreground">
                      {nextRunLabel(s.nextRunAt, s.enabled)}
                    </td>
                    <td className="px-3 py-2.5">
                      <div className="flex justify-end gap-1">
                        <IconButton
                          size="sm"
                          label={`Trigger ${s.spaceKey} ingestion now`}
                          icon={<Play size={13} />}
                          onClick={() => trigger.mutate(s.spaceKey)}
                          disabled={trigger.isPending}
                        />
                        <IconButton
                          size="sm"
                          label={`Edit schedule for ${s.spaceKey}`}
                          icon={<Pencil size={13} />}
                          onClick={() => openEdit(s)}
                        />
                        <IconButton
                          size="sm"
                          label={`Remove schedule for ${s.spaceKey}`}
                          icon={<Trash2 size={13} />}
                          onClick={() => handleDelete(s)}
                          disabled={remove.isPending}
                        />
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}

      {form && (
        <form
          onSubmit={handleSubmit}
          className="space-y-3 rounded-lg border border-border p-4"
        >
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold text-foreground">
              {form.mode === 'create' ? 'New schedule' : `Edit — ${form.spaceKey}`}
            </h3>
            <button
              type="button"
              aria-label="Cancel"
              onClick={() => setForm(null)}
              className="rounded p-1 text-muted-foreground hover:bg-surface-hover"
            >
              <X size={14} />
            </button>
          </div>

          {form.mode === 'create' && (
            <Input
              label="Space key"
              value={form.spaceKey}
              onChange={e => setForm(f => f && { ...f, spaceKey: e.target.value })}
              placeholder="ENG"
              hint="The Confluence space key to auto-ingest."
              required
            />
          )}

          <div className="space-y-1">
            <label className="block text-xs font-medium text-muted-foreground">Interval</label>
            <div className="flex items-center gap-2">
              <select
                value={form.intervalPreset}
                onChange={e =>
                  setForm(f => f && { ...f, intervalPreset: e.target.value, customInterval: '' })
                }
                className="rounded-md border border-border bg-background px-2 py-1.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary"
              >
                {INTERVAL_PRESETS.map(p => (
                  <option key={p.value} value={String(p.value)}>
                    {p.label}
                  </option>
                ))}
                <option value="custom">Custom…</option>
              </select>
              {form.intervalPreset === 'custom' && (
                <div className="flex items-center gap-1">
                  <input
                    type="number"
                    min={1}
                    max={8760}
                    value={form.customInterval}
                    onChange={e => setForm(f => f && { ...f, customInterval: e.target.value })}
                    placeholder="hours"
                    className="w-20 rounded-md border border-border bg-background px-2 py-1.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary"
                  />
                  <span className="text-xs text-muted-foreground">h</span>
                </div>
              )}
            </div>
          </div>

          <div className="space-y-2">
            <label className="flex cursor-pointer items-center gap-2 text-sm text-muted-foreground">
              <input
                type="checkbox"
                checked={form.enabled}
                onChange={e => setForm(f => f && { ...f, enabled: e.target.checked })}
                className="rounded accent-primary"
              />
              Enabled
            </label>
            <label className="flex cursor-pointer items-center gap-2 text-sm text-muted-foreground">
              <input
                type="checkbox"
                checked={form.force}
                onChange={e => setForm(f => f && { ...f, force: e.target.checked })}
                className="rounded accent-primary"
              />
              Force re-embed unchanged pages
            </label>
          </div>

          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" size="sm" onClick={() => setForm(null)}>
              Cancel
            </Button>
            <Button type="submit" size="sm" loading={upsert.isPending} disabled={!isFormValid}>
              {form.mode === 'create' ? 'Add schedule' : 'Save changes'}
            </Button>
          </div>
        </form>
      )}
    </div>
  )
}
