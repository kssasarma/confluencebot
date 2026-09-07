import { useQuery } from '@tanstack/react-query'
import { fetchSpaces } from '../services/spaceService'
import { queryKeys } from '../services/queryKeys'

/**
 * The Confluence spaces available to scope chat search to.
 *
 * The list only changes when a new space is ingested for the first time, so it is fetched once
 * per session rather than refetched on every render of the selector.
 */
export function useSpaces() {
  const query = useQuery({
    queryKey: queryKeys.spaces,
    queryFn: fetchSpaces,
    staleTime: 5 * 60_000,
  })

  return {
    spaces: query.data ?? [],
    isLoading: query.isLoading,
  }
}
