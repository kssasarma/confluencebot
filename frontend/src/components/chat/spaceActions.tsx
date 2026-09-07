import { Check } from 'lucide-react'
import type { MenuAction } from '../ui/Menu'
import type { SpaceSummary } from '../../types'

const CHECK_PLACEHOLDER = <span className="inline-block w-3.5" aria-hidden="true" />

/**
 * "All spaces" plus one entry per ingested space, the selected one marked with a check.
 *
 * Factored out of the component so it is a plain function a test can call directly, rather than
 * something only observable by opening a floating-ui-anchored dropdown (see `profileActions.tsx`
 * for the same reasoning).
 */
export function buildSpaceActions(
  spaces: SpaceSummary[], value: string | null, onChange: (spaceKey: string | null) => void,
): MenuAction[] {
  return [
    {
      label: 'All spaces',
      icon: value === null ? <Check size={14} /> : CHECK_PLACEHOLDER,
      onSelect: () => onChange(null),
    },
    ...spaces.map((space): MenuAction => ({
      label: space.name,
      icon: value === space.key ? <Check size={14} /> : CHECK_PLACEHOLDER,
      onSelect: () => onChange(space.key),
    })),
  ]
}
