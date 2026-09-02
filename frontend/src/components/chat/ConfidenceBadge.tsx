import { CircleDot, SignalHigh, SignalLow, SignalMedium } from 'lucide-react'
import Badge from '../ui/Badge'
import Tooltip from '../ui/Tooltip'

interface ConfidenceBadgeProps {
  /** 0–1. */
  confidence: number
}

/**
 * How well the question matched the indexed documentation.
 *
 * ── On the wording ─────────────────────────────────────────────────────────
 * The obvious label for this number is "High confidence", and it is the wrong one. The number
 * measures *retrieval quality* — how well the question matched the pages in the index — and says
 * nothing about whether the answer built from those pages is correct. An answer can be
 * confidently retrieved and still wrong.
 *
 * "High confidence" on a hallucination is not a cosmetic problem: it is the interface vouching
 * for something it has not checked, on an internal documentation assistant whose answers people
 * will act on. So the label says what was actually measured — "Strong source match" — and the
 * tooltip states the limit outright. Same badge, same colours, same code; it just stops making a
 * claim the system cannot support.
 */

const BANDS = [
  {
    floor: 0.75,
    label: 'Strong source match',
    tone: 'success' as const,
    icon: SignalHigh,
    detail: 'Your question closely matched several indexed pages.',
  },
  {
    floor: 0.5,
    label: 'Partial source match',
    tone: 'warning' as const,
    icon: SignalMedium,
    detail: 'Some indexed pages were relevant, but the match was not strong.',
  },
  {
    floor: 0,
    label: 'Weak source match',
    tone: 'danger' as const,
    icon: SignalLow,
    detail: 'Little in the documentation matched your question. Treat this answer with caution.',
  },
]

export default function ConfidenceBadge({ confidence }: ConfidenceBadgeProps) {
  const band = BANDS.find(candidate => confidence >= candidate.floor) ?? BANDS[BANDS.length - 1]
  const Icon = band.icon
  const percentage = Math.round(confidence * 100)

  return (
    <Tooltip
      content={
        <>
          <strong className="block font-semibold">{band.label} · {percentage}%</strong>
          <span className="mt-1 block">{band.detail}</span>
          <span className="mt-1.5 block text-muted-foreground">
            This measures how well your question matched the indexed Confluence pages. It does not
            verify that the answer is correct.
          </span>
        </>
      }
    >
      <Badge tone={band.tone} icon={<Icon size={11} />}>
        {band.label}
      </Badge>
    </Tooltip>
  )
}

/** Shown when an answer was produced without any retrieval score — an older transcript. */
export function UnknownConfidenceBadge() {
  return (
    <Tooltip content="This answer predates match scoring.">
      <Badge tone="neutral" icon={<CircleDot size={11} />}>Match unknown</Badge>
    </Tooltip>
  )
}
