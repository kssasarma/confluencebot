/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * The libraries the first screen genuinely needs.
 *
 * Listed explicitly rather than matching all of `node_modules`, because a blanket rule pulls
 * lazily-used libraries — cmdk, loaded only when the command palette is opened — into the eager
 * chunk and silently undoes the split at the call site.
 */
const CORE_VENDOR =
  /[\\/](react|react-dom|scheduler|react-router|react-router-dom|@tanstack[\\/][^/\\]+|framer-motion|motion-dom|motion-utils|@headlessui[\\/][^/\\]+|@floating-ui[\\/][^/\\]+|@react-aria[\\/][^/\\]+|@tanstack|lucide-react|clsx|tailwind-merge|class-variance-authority|use-sync-external-store)[\\/]/

/** The markdown pipeline and everything only it pulls in. */
const MARKDOWN_STACK =
  /[\\/](react-markdown|remark-[^/\\]+|rehype-[^/\\]+|micromark[^/\\]*|mdast-[^/\\]+|hast-[^/\\]+|unist-[^/\\]+|unified|lowlight|highlight\.js|vfile[^/\\]*|bail|trough|devlop|zwitch|trim-lines|ccount|escape-string-regexp|character-entities[^/\\]*|decode-named-character-reference|property-information|space-separated-tokens|comma-separated-tokens|html-void-elements|stringify-entities|markdown-table)[\\/]/

export default defineConfig({
  plugins: [react()],

  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },

  build: {
    // Sourcemaps in production so a stack trace from a user's browser is readable. They are a
    // separate download the browser only fetches when devtools are open.
    sourcemap: true,
    rollupOptions: {
      output: {
        /**
         * Two named chunks, chosen so that the edges between them only ever point one way —
         * packages that import each other must stay together, or Rollup reports a circular chunk.
         *
         *  - `markdown`: the parser and highlighting grammars. The largest dependency, needed
         *    only once an answer exists, and imported by nothing else in `node_modules`.
         *  - `vendor`: the libraries the first paint depends on, which change on a slower cadence
         *    than the app and are worth keeping cached across deploys.
         */
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (MARKDOWN_STACK.test(id)) return 'markdown'
          if (CORE_VENDOR.test(id)) return 'vendor'
          // Everything else is placed by Rollup, which means a library reached only through a
          // dynamic import lands in that route's chunk instead of in the eager one.
          return undefined
        },
      },
    },
  },

  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: true,
    restoreMocks: true,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/vite-env.d.ts', 'src/main.tsx'],
    },
  },
})
