import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { SpaceSummary } from '../../types'
import SpaceSelector from './SpaceSelector'
import { buildSpaceActions } from './spaceActions'

/**
 * `buildSpaceActions` is tested directly rather than through the rendered dropdown: Menu is a
 * floating-ui-anchored Headless UI menu, and this environment's stubbed ResizeObserver cannot
 * settle its positioning (see ProfileMenu.test.tsx, which hits the same limitation). The trigger
 * itself — what it renders as, and when it renders nothing — is still exercised end to end.
 */

const SPACES: SpaceSummary[] = [
  { key: 'ENG', name: 'Engineering' },
  { key: 'IT', name: 'IT Support' },
]

describe('buildSpaceActions', () => {
  it('offers "All spaces" first, then one entry per space', () => {
    const actions = buildSpaceActions(SPACES, null, vi.fn())

    expect(actions.map(a => a.label)).toEqual(['All spaces', 'Engineering', 'IT Support']);
  })

  it('selecting a space calls onChange with its key', () => {
    const onChange = vi.fn()
    const actions = buildSpaceActions(SPACES, null, onChange)

    actions.find(a => a.label === 'Engineering')?.onSelect()

    expect(onChange).toHaveBeenCalledWith('ENG')
  })

  it('selecting "All spaces" calls onChange with null', () => {
    const onChange = vi.fn()
    const actions = buildSpaceActions(SPACES, 'ENG', onChange)

    actions.find(a => a.label === 'All spaces')?.onSelect()

    expect(onChange).toHaveBeenCalledWith(null)
  })
})

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function renderSelector(value: string | null) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <SpaceSelector value={value} onChange={vi.fn()} />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(SPACES)))
})

describe('SpaceSelector trigger', () => {
  it('shows "All spaces" when nothing is selected', async () => {
    renderSelector(null)

    expect(await screen.findByRole('button', { name: /all spaces/i })).toBeInTheDocument()
  })

  it('shows the selected space\'s name', async () => {
    renderSelector('IT')

    expect(await screen.findByRole('button', { name: /it support/i })).toBeInTheDocument()
  })

  it('renders nothing while no space has been ingested yet', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json([])))
    const { container } = renderSelector(null)

    await waitFor(() => expect(container).toBeEmptyDOMElement())
  })

  it('renders nothing when the space list fails to load', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ detail: 'unavailable' }, 500)))
    const { container } = renderSelector(null)

    await waitFor(() => expect(container).toBeEmptyDOMElement())
  })
})
