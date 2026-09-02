import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['dist', 'coverage', 'node_modules'] },

  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2023,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],

      // Destructuring a key out in order to drop it is the idiomatic way to remove one entry from
      // a record without mutating it; the discarded binding is deliberate, so it is exempted by
      // name rather than by disabling the rule at each site.
      '@typescript-eslint/no-unused-vars': ['error', {
        argsIgnorePattern: '^_',
        varsIgnorePattern: '^_',
        caughtErrors: 'none',
        ignoreRestSiblings: true,
      }],

      // An empty catch is how this codebase used to swallow failures silently. Requiring a
      // comment forces the author to say why nothing is done — which is a real answer for
      // clipboard and storage, and never a real answer for a save.
      'no-empty': ['error', { allowEmptyCatch: false }],
    },
  },

  {
    // Providers are colocated with the hook that reads them — `ToastProvider` and `useToast` in
    // one file is the pattern every consumer expects, and splitting them to satisfy a Fast
    // Refresh heuristic would make the API worse to use in exchange for a marginally faster
    // dev-server reload. The router likewise exports its route table alongside its guards.
    files: [
      'src/context/**/*.tsx',
      'src/app/router.tsx',
      'src/components/ui/Toast.tsx',
      'src/components/ui/ConfirmDialog.tsx',
      'src/components/chat/LazyMarkdown.tsx',
    ],
    rules: { 'react-refresh/only-export-components': 'off' },
  },

  {
    files: ['**/*.test.{ts,tsx}', 'src/test/**'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
      'react-refresh/only-export-components': 'off',
    },
  },

  {
    files: ['scripts/**/*.mjs', '*.config.{js,ts}'],
    languageOptions: { globals: globals.node },
  },
)
