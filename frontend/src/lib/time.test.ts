import { describe, expect, it } from 'vitest'
import { groupByRecency, recencyBucket, relativeTime } from './time'

const NOW = new Date('2026-03-12T10:00:00')

const at = (iso: string) => new Date(iso).toISOString()

describe('recencyBucket', () => {
  it('groups by calendar day rather than elapsed hours', () => {
    // 11pm last night is 11 hours ago but belongs under Yesterday, which is how a reader
    // remembers it.
    expect(recencyBucket(at('2026-03-11T23:00:00'), NOW)).toBe('Yesterday')
    expect(recencyBucket(at('2026-03-12T00:30:00'), NOW)).toBe('Today')
  })

  it('covers each window', () => {
    expect(recencyBucket(at('2026-03-08T10:00:00'), NOW)).toBe('Previous 7 days')
    expect(recencyBucket(at('2026-02-20T10:00:00'), NOW)).toBe('Previous 30 days')
    expect(recencyBucket(at('2025-11-01T10:00:00'), NOW)).toBe('Older')
  })

  it('treats a clock skew into the future as today', () => {
    expect(recencyBucket(at('2026-03-12T23:59:00'), NOW)).toBe('Today')
  })

  it('falls back to Older for a missing or unparseable timestamp', () => {
    expect(recencyBucket(undefined, NOW)).toBe('Older')
    expect(recencyBucket('not a date', NOW)).toBe('Older')
  })
})

describe('groupByRecency', () => {
  it('keeps the incoming order and omits empty buckets', () => {
    const rows = [
      { id: 'a', updatedAt: at('2026-03-12T09:00:00') },
      { id: 'b', updatedAt: at('2026-03-12T08:00:00') },
      { id: 'c', updatedAt: at('2026-02-25T08:00:00') },
    ]

    const groups = groupByRecency(rows, row => row.updatedAt, NOW)

    expect(groups.map(group => group.label)).toEqual(['Today', 'Previous 30 days'])
    expect(groups[0].items.map(row => row.id)).toEqual(['a', 'b'])
  })
})

describe('relativeTime', () => {
  it('returns an empty string rather than "Invalid Date"', () => {
    expect(relativeTime(undefined)).toBe('')
    expect(relativeTime('nonsense')).toBe('')
  })

  it('reads as an age', () => {
    const tenMinutesAgo = new Date(Date.now() - 10 * 60_000).toISOString()
    expect(relativeTime(tenMinutesAgo)).toBe('10m ago')
  })
})
