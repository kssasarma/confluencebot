/**
 * Copies dist/index.html to dist/404.html.
 *
 * This is a client-side-routed SPA: every route past `/` (e.g. `/chat/abc`) only exists in the
 * browser, not as a file on the host. GitLab Pages (and similar static hosts) serve `404.html`
 * for any path that doesn't match a real file, so shipping a copy of the entry document under
 * that name lets deep links and refreshes resolve to the app instead of a bare 404 page.
 */
import { copyFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'

const DIST = 'dist'
const source = join(DIST, 'index.html')
const dest = join(DIST, '404.html')

if (!existsSync(source)) {
  console.error(`No ${source} — run "npm run build" first.`)
  process.exit(1)
}

copyFileSync(source, dest)
console.log(`Copied ${source} -> ${dest}`)
