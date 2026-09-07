import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronDown, ChevronRight, Play, RefreshCw, RotateCcw } from 'lucide-react'
import {
  ingestPage, ingestSpace, listJobs, retriggerJob, type IngestionJob,
} from '../../services/adminService'
import { queryKeys } from '../../services/queryKeys'
import { toMessage } from '../../lib/errors'
import { useToast } from '../ui/Toast'
import { absoluteTime } from '../../lib/time'
import Badge from '../ui/Badge'
import Button from '../ui/Button'
import EmptyState from '../ui/EmptyState'
import IconButton from '../ui/IconButton'
import Input from '../ui/Input'
import { SkeletonText } from '../ui/Skeleton'

const JOB_TONE: Record<string, 'warning' | 'info' | 'success' | 'danger' | 'neutral'> = {
  PENDING: 'warning',
  RUNNING: 'info',
  COMPLETED: 'success',
  FAILED: 'danger',
}

/** Ingestion control: the "Ingestion" section of the unified Settings dialog. */
export default function AdminIngestionPanel() {
  const queryClient = useQueryClient()
  const toast = useToast()

  const [spaceKey, setSpaceKey] = useState('')
  const [force, setForce] = useState(false)
  const [pageId, setPageId] = useState('')

  // Job history isn't the primary reason anyone opens this tab, so it stays collapsed — and
  // unfetched — until someone actually wants to see it.
  const [historyOpen, setHistoryOpen] = useState(false)
  const [historyPage, setHistoryPage] = useState(0)

  const jobs = useQuery({
    queryKey: queryKeys.ingestionJobs(historyPage),
    queryFn: () => listJobs(historyPage),
    enabled: historyOpen,
    // Ingestion runs in the background, so the open page is polled while anything on it is still
    // in flight, and left alone once everything has settled.
    refetchInterval: query => {
      const data = query.state.data
      const active = data?.jobs.some(job => job.status === 'PENDING' || job.status === 'RUNNING')
      return active ? 4000 : false
    },
  })

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['admin', 'jobs'] })

  const startSpace = useMutation({
    mutationFn: () => ingestSpace(spaceKey.trim(), force),
    onSuccess: () => { setSpaceKey(''); setForce(false); void refresh() },
    onError: error => toast.error('Could not start ingestion', toMessage(error, 'Please try again.')),
  })

  const startPage = useMutation({
    mutationFn: () => ingestPage(pageId.trim()),
    onSuccess: () => { setPageId(''); void refresh() },
    onError: error => toast.error('Could not ingest the page', toMessage(error, 'Please try again.')),
  })

  const retrigger = useMutation({
    mutationFn: (job: IngestionJob) => retriggerJob(job.jobId),
    onSuccess: () => {
      toast.success('Job resubmitted', 'The failed run stays in the history below.')
      void refresh()
    },
    onError: error => toast.error('Could not retrigger the job', toMessage(error, 'Please try again.')),
  })

  return (
    <div className="space-y-6">
      <div className="grid gap-4 sm:grid-cols-2">
        <form
          onSubmit={event => { event.preventDefault(); startSpace.mutate() }}
          className="space-y-3 rounded-lg border border-border p-4"
        >
          <h2 className="text-sm font-semibold text-foreground">Ingest a space</h2>
          <Input
            label="Space key"
            value={spaceKey}
            onChange={event => setSpaceKey(event.target.value)}
            placeholder="ENG"
            hint="Leave blank to use the configured default space."
          />
          <label className="flex cursor-pointer items-center gap-2 text-sm text-muted-foreground">
            <input
              type="checkbox"
              checked={force}
              onChange={event => setForce(event.target.checked)}
              className="rounded accent-primary"
            />
            Re-ingest pages that have not changed
          </label>
          <Button type="submit" block loading={startSpace.isPending}>
            <Play size={14} aria-hidden="true" />
            Start ingestion
          </Button>
        </form>

        <form
          onSubmit={event => { event.preventDefault(); startPage.mutate() }}
          className="space-y-3 rounded-lg border border-border p-4"
        >
          <h2 className="text-sm font-semibold text-foreground">Ingest a single page</h2>
          <Input
            label="Page ID"
            required
            value={pageId}
            onChange={event => setPageId(event.target.value)}
            placeholder="131073"
            hint="The numeric id in the Confluence page URL."
          />
          <Button type="submit" block loading={startPage.isPending} disabled={!pageId.trim()}>
            <Play size={14} aria-hidden="true" />
            Ingest page
          </Button>
        </form>
      </div>

      <div className="rounded-lg border border-border">
        <div className="flex items-center justify-between px-2 py-1">
          <button
            type="button"
            onClick={() => setHistoryOpen(open => !open)}
            aria-expanded={historyOpen}
            className="flex flex-1 items-center gap-2 rounded-lg px-2 py-2 text-left text-sm font-semibold text-foreground hover:bg-surface-hover"
          >
            {historyOpen ? (
              <ChevronDown size={16} aria-hidden="true" />
            ) : (
              <ChevronRight size={16} aria-hidden="true" />
            )}
            Job history
          </button>
          {historyOpen && (
            <Button size="sm" variant="ghost" onClick={refresh}>
              <RefreshCw size={13} aria-hidden="true" />
              Refresh
            </Button>
          )}
        </div>

        {historyOpen && (
          <div className="border-t border-border p-4">
            {jobs.isLoading ? (
              <SkeletonText lines={4} />
            ) : jobs.data?.jobs.length === 0 ? (
              <EmptyState title="No ingestion jobs yet" description="Start one above to index a space." />
            ) : (
              <>
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <caption className="sr-only">Recent ingestion jobs</caption>
                    <thead>
                      <tr className="border-b border-border text-left">
                        <th scope="col" className="pb-2 font-medium text-muted-foreground">Type</th>
                        <th scope="col" className="pb-2 font-medium text-muted-foreground">Target</th>
                        <th scope="col" className="pb-2 font-medium text-muted-foreground">Status</th>
                        <th scope="col" className="pb-2 font-medium text-muted-foreground">Pages</th>
                        <th scope="col" className="pb-2 font-medium text-muted-foreground">Chunks</th>
                        <th scope="col" className="pb-2 font-medium text-muted-foreground">Started</th>
                        <th scope="col" className="pb-2"><span className="sr-only">Actions</span></th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border">
                      {jobs.data?.jobs.map(job => (
                        <tr key={job.jobId}>
                          <td className="py-2 pr-3 text-2xs text-muted-foreground">{job.jobType}</td>
                          <td className="py-2 pr-3 font-mono text-2xs">{job.spaceKey ?? job.pageId ?? '—'}</td>
                          <td className="py-2 pr-3">
                            <Badge tone={JOB_TONE[job.status] ?? 'neutral'}>{job.status}</Badge>
                            {job.errorMessage && (
                              <p className="mt-1 max-w-xs text-2xs text-danger-emphasis">{job.errorMessage}</p>
                            )}
                          </td>
                          <td className="py-2 pr-3 text-2xs text-muted-foreground">{job.pagesProcessed ?? '—'}</td>
                          <td className="py-2 pr-3 text-2xs text-muted-foreground">{job.chunksStored ?? '—'}</td>
                          <td className="py-2 pr-3 text-2xs text-muted-foreground">
                            {job.startedAt ? absoluteTime(job.startedAt) : '—'}
                          </td>
                          <td className="py-2 text-right">
                            {job.status === 'FAILED' && (
                              <IconButton
                                size="sm"
                                label={`Retrigger ${job.jobType.toLowerCase()} job for ${job.spaceKey ?? job.pageId ?? 'this target'}`}
                                icon={<RotateCcw size={14} />}
                                onClick={() => retrigger.mutate(job)}
                                disabled={retrigger.isPending}
                              />
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                {jobs.data && jobs.data.totalElements > 0 && (
                  <div className="mt-3 flex items-center justify-between text-2xs text-muted-foreground">
                    <span>
                      Page {jobs.data.page + 1} of {Math.max(jobs.data.totalPages, 1)}
                      {' · '}{jobs.data.totalElements} job{jobs.data.totalElements === 1 ? '' : 's'}
                    </span>
                    <div className="flex gap-2">
                      <Button
                        size="sm"
                        variant="secondary"
                        onClick={() => setHistoryPage(page => Math.max(page - 1, 0))}
                        disabled={historyPage === 0 || jobs.isFetching}
                      >
                        Previous
                      </Button>
                      <Button
                        size="sm"
                        variant="secondary"
                        onClick={() => setHistoryPage(page => page + 1)}
                        disabled={!jobs.data.hasNext || jobs.isFetching}
                      >
                        Next
                      </Button>
                    </div>
                  </div>
                )}
              </>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
