import { apiJson } from './http'
import type { SpaceSummary } from '../types'

/** Every Confluence space with at least one ingested page, sorted by name. */
export const fetchSpaces = (): Promise<SpaceSummary[]> => apiJson<SpaceSummary[]>('/spaces')
