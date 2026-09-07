import { describe, expect, it } from 'vitest'
import { toggleRole } from './roles'
import type { AdminRole } from '../services/adminService'

describe('toggleRole', () => {
  it('adds a role that is not yet selected', () => {
    expect(toggleRole(['USER'], 'INGESTOR')).toEqual(['USER', 'INGESTOR'])
  })

  it('removes a role that is already selected', () => {
    expect(toggleRole(['USER', 'INGESTOR'], 'INGESTOR')).toEqual(['USER'])
  })

  it('refuses to remove the only remaining role', () => {
    const solo: AdminRole[] = ['ADMIN']
    expect(toggleRole(solo, 'ADMIN')).toBe(solo)
  })
})
