import { useQuery } from '@tanstack/react-query'
import { fetchSuggestions } from '../services/spaceService'
import { queryKeys } from '../services/queryKeys'

/**
 * The welcome screen's example questions for the space currently selected — generated when that
 * space was last ingested, not hard-coded. `spaceKey` of `null` asks for a cross-space sample.
 */
export function useSuggestions(spaceKey: string | null) {
  const query = useQuery({
    queryKey: queryKeys.suggestions(spaceKey),
    queryFn: () => fetchSuggestions(spaceKey),
    staleTime: 5 * 60_000,
  })

  return {
    suggestions: query.data ?? [],
    isLoading: query.isLoading,
  }
}
