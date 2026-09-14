import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Pencil, Play, Plus, RefreshCw, Trash2, X } from 'lucide-react'
import {
  deleteSchedule,
  listSchedules,
  triggerScheduleNow,
  upsertSchedule,
  type IngestionSchedule,
  type ScheduleType,
} from '../../services/adminService'
import { fetchSpaces } from '../../services/spaceService'
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

// ── helpers ──────────────────────────────────────────────────────────────────

function totalHours(days: number, hours: number): number {
  return days * 24 + hours
}

function fromIntervalHours(h: number): { days: number; hours: number } {
  return { days: Math.floor(h / 24), hours: h % 24 }
}

function intervalLabel(h: number): string {
  if (h < 24) return `every ${h} h`
  const d = Math.floor(h / 24)
  const rem = h % 24
  const dayPart = d === 1 ? 'every day' : `every ${d} days`
  return rem === 0 ? dayPart : `${dayPart} ${rem} h`
}

function nextRunLabel(nextRunAt: string, enabled: boolean): string {
  if (!enabled) return '—'
  const ms = new Date(nextRunAt).getTime() - Date.now()
  if (ms <= 60_000) return 'soon'
  const h = Math.floor(ms / 3_600_000)
  const m = Math.floor((ms % 3_600_000) / 60_000)
  return h > 0 ? `in ${h}h ${m}m` : `in ${m}m`
}

function runsLabel(s: IngestionSchedule): string {
  if (s.scheduleType === 'CRON') return s.cronExpression ?? '—'
  return intervalLabel(s.intervalHours)
}

// ── form state ────────────────────────────────────────────────────────────────

type FormMode = 'create' | 'edit'

interface ScheduleForm {
  mode: FormMode
  spaceKey: string
  scheduleType: ScheduleType
  days: number
  hours: number
  cronExpression: string
  enabled: boolean
}

const BLANK_FORM: ScheduleForm = {
  mode: 'create',
  spaceKey: '',
  scheduleType: 'FIXED_INTERVAL',
  days: 1,
  hours: 0,
  cronExpression: '',
  enabled: true,
}

function formFromSchedule(s: IngestionSchedule): ScheduleForm {
  const { days, hours } = fromIntervalHours(s.intervalHours)
  return {
    mode: 'edit',
    spaceKey: s.spaceKey,
    scheduleType: s.scheduleType ?? 'FIXED_INTERVAL',
    days,
    hours,
    cronExpression: s.cronExpression ?? '',
    enabled: s.enabled,
  }
}

// ── component ─────────────────────────────────────────────────────────────────

/**
 * Admin-only panel for managing auto-ingestion schedules.
 *
 * Gated upstream by `canManageSchedules` in SettingsDialog — today that means full admins only.
 */
export default function AdminSchedulesPanel() {
  const queryClient = useQueryClient()
  const toast = useToast()
  const confirm = useConfirm()

  const [form, setForm] = useState<ScheduleForm | null>(null)

  const schedules = useQuery({
    queryKey: queryKeys.ingestionSchedules,
    queryFn: listSchedules,
  })

  const spaces = useQuery({
    queryKey: queryKeys.spaces,
    queryFn: fetchSpaces,
  })

  const refresh = () => queryClient.invalidateQueries({ queryKey: queryKeys.ingestionSchedules })

  const scheduledKeys = new Set((schedules.data ?? []).map(s => s.spaceKey))
  const availableSpaces = (spaces.data ?? []).filter(s => !scheduledKeys.has(s.key))

  const upsert = useMutation({
    mutationFn: (f: ScheduleForm) => {
      const isCron = f.scheduleType === 'CRON'
      return upsertSchedule(f.spaceKey.trim(), {
        scheduleType: f.scheduleType,
        intervalHours: isCron ? 0 : totalHours(f.days, f.hours),
        enabled: f.enabled,
        cronExpression: isCron ? f.cronExpression.trim() : null,
      })
    },
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
      toast.success('Job submitted', `Immediate ingestion for ${spaceKey} has been queued.`)
      queryClient.invalidateQueries({ queryKey: ['admin', 'jobs'] })
    },
    onError: error => toast.error('Could not trigger ingestion', toMessage(error, 'Please try again.')),
  })

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (!form) return

    if (form.scheduleType === 'FIXED_INTERVAL') {
      const h = totalHours(form.days, form.hours)
      if (h < 1 || h > 8760) {
        toast.error('Invalid interval', 'Total must be between 1 hour and 8760 hours (365 days).')
        return
      }
    } else {
      if (!form.cronExpression.trim()) {
        toast.error('Missing cron expression', 'Enter a cron expression for CRON schedules.')
        return
      }
    }

    upsert.mutate(form)
  }

  async function handleDelete(s: IngestionSchedule) {
    const ok = await confirm({
      title: `Remove schedule for ${s.spaceKey}?`,
      description: 'Auto-ingestion for this space will stop. Any currently running job completes normally.',
      confirmLabel: 'Remove',
      tone: 'danger',
    })
    if (ok) remove.mutate(s.spaceKey)
  }

  const formTotal = form ? totalHours(form.days, form.hours) : 0
  const isFormValid = form !== null && form.spaceKey.trim().length > 0 && (
    form.scheduleType === 'CRON'
      ? form.cronExpression.trim().length > 0
      : formTotal >= 1 && formTotal <= 8760
  )

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-sm font-semibold text-foreground">Auto-ingestion schedules</h2>
          <p className="mt-0.5 text-2xs text-muted-foreground">
            Each schedule re-crawls and re-embeds a Confluence space automatically at the configured interval.
          </p>
        </div>
        <div className="flex shrink-0 gap-2">
          <Button size="sm" variant="ghost" onClick={refresh} disabled={schedules.isFetching}>
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
          title="No schedules yet"
          description="Add a schedule to have a Confluence space re-indexed automatically."
        />
      ) : (
        schedules.data && schedules.data.length > 0 && (
          <div className="overflow-x-auto rounded-lg border border-border">
            <table className="w-full text-sm">
              <caption className="sr-only">Auto-ingestion schedules</caption>
              <thead>
                <tr className="border-b border-border text-left">
                  <th scope="col" className="px-3 pb-2 pt-3 font-medium text-muted-foreground">Space</th>
                  <th scope="col" className="px-3 pb-2 pt-3 font-medium text-muted-foreground">Runs</th>
                  <th scope="col" className="px-3 pb-2 pt-3 font-medium text-muted-foreground">Status</th>
                  <th scope="col" className="px-3 pb-2 pt-3 font-medium text-muted-foreground">Last run</th>
                  <th scope="col" className="px-3 pb-2 pt-3 font-medium text-muted-foreground">Next run</th>
                  <th scope="col" className="px-3 pb-2 pt-3"><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {schedules.data.map(s => (
                  <tr key={s.id}>
                    <td className="px-3 py-2.5 font-mono text-xs font-semibold">{s.spaceKey}</td>
                    <td className="px-3 py-2.5 text-2xs text-muted-foreground font-mono">
                      {runsLabel(s)}
                    </td>
                    <td className="px-3 py-2.5">
                      <Badge tone={s.enabled ? 'success' : 'neutral'}>
                        {s.enabled ? 'Active' : 'Disabled'}
                      </Badge>
                    </td>
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
                          onClick={() => setForm(formFromSchedule(s))}
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
          className="space-y-4 rounded-lg border border-border p-4"
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
            <div className="space-y-1.5">
              <label className="block text-xs font-medium text-muted-foreground" htmlFor="schedule-space-key">
                Space
              </label>
              {availableSpaces.length > 0 ? (
                <select
                  id="schedule-space-key"
                  value={form.spaceKey}
                  onChange={e => setForm(f => f && { ...f, spaceKey: e.target.value })}
                  required
                  className="w-full rounded-md border border-border bg-background px-2 py-1.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary"
                >
                  <option value="" disabled>Select a space…</option>
                  {availableSpaces.map(s => (
                    <option key={s.key} value={s.key}>{s.name} ({s.key})</option>
                  ))}
                </select>
              ) : (
                <Input
                  id="schedule-space-key"
                  value={form.spaceKey}
                  onChange={e => setForm(f => f && { ...f, spaceKey: e.target.value })}
                  placeholder="ENG"
                  hint={
                    spaces.isLoading
                      ? 'Loading spaces…'
                      : 'No additional spaces available — enter a key manually.'
                  }
                  required
                />
              )}
            </div>
          )}

          {/* Schedule type selector */}
          <div className="space-y-1.5">
            <label className="block text-xs font-medium text-muted-foreground">
              Schedule type
            </label>
            <div className="flex gap-3">
              {(['FIXED_INTERVAL', 'CRON'] as ScheduleType[]).map(type => (
                <label key={type} className="flex cursor-pointer items-center gap-2 text-sm text-muted-foreground">
                  <input
                    type="radio"
                    name="scheduleType"
                    value={type}
                    checked={form.scheduleType === type}
                    onChange={() => setForm(f => f && { ...f, scheduleType: type })}
                    className="accent-primary"
                  />
                  {type === 'FIXED_INTERVAL' ? 'Fixed interval' : 'Cron expression'}
                </label>
              ))}
            </div>
          </div>

          {form.scheduleType === 'FIXED_INTERVAL' && (
            <div className="space-y-1.5">
              <label className="block text-xs font-medium text-muted-foreground">Repeat every</label>
              <div className="flex items-center gap-3">
                <div className="flex items-center gap-1.5">
                  <input
                    type="number"
                    min={0}
                    max={365}
                    value={form.days}
                    onChange={e => setForm(f => f && { ...f, days: Math.max(0, parseInt(e.target.value, 10) || 0) })}
                    className="w-16 rounded-md border border-border bg-background px-2 py-1.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary"
                  />
                  <span className="text-sm text-muted-foreground">days</span>
                </div>
                <div className="flex items-center gap-1.5">
                  <input
                    type="number"
                    min={0}
                    max={23}
                    value={form.hours}
                    onChange={e => setForm(f => f && { ...f, hours: Math.max(0, parseInt(e.target.value, 10) || 0) })}
                    className="w-16 rounded-md border border-border bg-background px-2 py-1.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary"
                  />
                  <span className="text-sm text-muted-foreground">hours</span>
                </div>
                {formTotal >= 1 && formTotal <= 8760 && (
                  <span className="text-2xs text-muted-foreground">
                    = {intervalLabel(formTotal)} ({formTotal} h total)
                  </span>
                )}
                {formTotal > 8760 && (
                  <span className="text-2xs text-danger-emphasis">Maximum is 365 days (8760 h)</span>
                )}
                {formTotal < 1 && form.days === 0 && form.hours === 0 && (
                  <span className="text-2xs text-danger-emphasis">Minimum is 1 hour</span>
                )}
              </div>
            </div>
          )}

          {form.scheduleType === 'CRON' && (
            <div className="space-y-1.5">
              <label
                className="block text-xs font-medium text-muted-foreground"
                htmlFor="schedule-cron-expression"
              >
                Cron expression
              </label>
              <Input
                id="schedule-cron-expression"
                value={form.cronExpression}
                onChange={e => setForm(f => f && { ...f, cronExpression: e.target.value })}
                placeholder="0 2 * * 1"
                required
                hint="5-field format: minute hour day-of-month month day-of-week. Example: '0 2 * * 1' = every Monday at 02:00."
              />
            </div>
          )}

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
