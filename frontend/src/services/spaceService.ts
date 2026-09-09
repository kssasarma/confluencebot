import { apiJson } from './http'
import type { SpaceSummary } from '../types'

/** Every Confluence space with at least one ingested page, sorted by name. */
export const fetchSpaces = (): Promise<SpaceSummary[]> => apiJson<SpaceSummary[]>('/spaces')

/**
 * Up to four example questions for the welcome screen, generated from what the space actually
 * ingested (see the backend's SuggestionGenerationService) rather than hard-coded in the client.
 * Omit `spaceKey` (or pass null) for a cross-space sample.
 */
export const fetchSuggestions = (spaceKey: string | null): Promise<string[]> =>
  apiJson<string[]>(`/spaces/suggestions${spaceKey ? `?spaceKey=${encodeURIComponent(spaceKey)}` : ''}`)
