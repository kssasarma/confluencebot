import axe, { type AxeResults, type RunOptions } from 'axe-core'
import { expect } from 'vitest'

/**
 * Runs axe-core over a rendered subtree and fails with the violations spelled out.
 *
 * Automated checks catch roughly a third of real accessibility defects — they find a missing
 * accessible name, they cannot tell you that the name is wrong. They are a floor, not a
 * substitute for the keyboard pass the delivery plan calls for each phase.
 *
 * Colour-contrast rules are disabled here rather than left to fail noisily: jsdom has no layout
 * and no computed colours, so axe cannot evaluate them. Contrast is asserted directly against the
 * token stylesheet in `src/design/contrast.test.ts`, which is a stronger check than sampling
 * whatever happens to be on screen.
 */
export async function expectNoAxeViolations(
  container: Element,
  options: RunOptions = {},
): Promise<void> {
  const results: AxeResults = await axe.run(container, {
    rules: {
      'color-contrast': { enabled: false },
      // The tests render fragments rather than whole documents, so page-level structure rules
      // have nothing meaningful to say about them.
      region: { enabled: false },
      'page-has-heading-one': { enabled: false },
      'landmark-one-main': { enabled: false },
    },
    ...options,
  })

  if (results.violations.length === 0) return

  const report = results.violations
    .map(violation => {
      const nodes = violation.nodes.map(node => `      ${node.html}`).join('\n')
      return `  ${violation.id} (${violation.impact}): ${violation.help}\n${nodes}`
    })
    .join('\n\n')

  expect.fail(`${results.violations.length} accessibility violation(s):\n\n${report}`)
}
