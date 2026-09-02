/**
 * Fails the build when the initial JavaScript payload grows past its budget.
 *
 * The number that matters is what a first-time visitor must download before the app renders:
 * the entry chunk plus everything it statically imports. Lazy routes and the command palette are
 * excluded because they are fetched on demand — counting them would penalise exactly the
 * splitting that keeps the initial load small.
 *
 * Budget from the delivery plan: 250 KB gzipped.
 */
import { gzipSync } from 'node:zlib'
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { join } from 'node:path'

const DIST = 'dist'
const BUDGET_BYTES = 250 * 1024

if (!existsSync(DIST)) {
  console.error(`No ${DIST}/ directory — run "npm run build" first.`)
  process.exit(1)
}

const html = readFileSync(join(DIST, 'index.html'), 'utf8')

// Chunks the document loads up front: the entry <script> and anything <link rel=modulepreload>
// declares. Vite emits a modulepreload for each statically imported chunk, which is precisely
// the "before first render" set.
const eager = new Set(
  [...html.matchAll(/(?:src|href)="\/?(assets\/[^"]+\.js)"/g)].map(match => match[1]),
)

if (eager.size === 0) {
  console.error('Found no entry chunks in dist/index.html — has the build output changed shape?')
  process.exit(1)
}

let total = 0
const rows = []

for (const file of [...eager].sort()) {
  const bytes = gzipSync(readFileSync(join(DIST, file))).length
  total += bytes
  rows.push([file, bytes])
}

const lazyCount = readdirSync(join(DIST, 'assets')).filter(
  name => name.endsWith('.js') && !eager.has(`assets/${name}`),
).length

const kb = bytes => `${(bytes / 1024).toFixed(1)} KB`

console.log('Initial JavaScript (gzipped):')
for (const [file, bytes] of rows) console.log(`  ${kb(bytes).padStart(9)}  ${file}`)
console.log(`  ${kb(total).padStart(9)}  total`)
console.log(`\n${lazyCount} further chunk(s) load on demand.`)

if (total > BUDGET_BYTES) {
  console.error(
    `\nOver budget: ${kb(total)} > ${kb(BUDGET_BYTES)}.` +
    '\nEither lazy-load the new dependency or raise the budget deliberately.',
  )
  process.exit(1)
}

console.log(`\nWithin budget (${kb(BUDGET_BYTES)}).`)
