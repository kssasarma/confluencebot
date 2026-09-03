import {
  createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode,
} from 'react'

export type Theme = 'light' | 'dark' | 'system'

interface ThemeContextValue {
  /** What the user chose, which may be "follow the system". */
  theme: Theme
  /** What is actually being rendered right now. */
  resolvedTheme: 'light' | 'dark'
  setTheme: (theme: Theme) => void
}

const ThemeContext = createContext<ThemeContextValue | null>(null)

const STORAGE_KEY = 'cb_theme'
const DARK_QUERY = '(prefers-color-scheme: dark)'

function readStoredTheme(): Theme {
  try {
    // JSON rather than the bare string, matching the inline script in index.html that applies the
    // class before the first paint. The two must agree or the page flashes the wrong theme.
    const stored = localStorage.getItem(STORAGE_KEY)
    const parsed = stored ? (JSON.parse(stored) as unknown) : null
    return parsed === 'light' || parsed === 'dark' || parsed === 'system' ? parsed : 'system'
  } catch {
    return 'system'
  }
}

function prefersDark(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia(DARK_QUERY).matches
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>(readStoredTheme)
  const [systemIsDark, setSystemIsDark] = useState(prefersDark)

  // "System" has to keep following the system: a reader who switches their OS to dark at sunset
  // expects the open tab to follow, not to need a reload.
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return
    const query = window.matchMedia(DARK_QUERY)
    const onChange = (event: MediaQueryListEvent) => setSystemIsDark(event.matches)

    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [])

  const resolvedTheme: 'light' | 'dark' =
    theme === 'system' ? (systemIsDark ? 'dark' : 'light') : theme

  useEffect(() => {
    document.documentElement.classList.toggle('dark', resolvedTheme === 'dark')
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(theme))
    } catch {
      /* Storage is blocked; the choice still applies for this session. */
    }
  }, [resolvedTheme, theme])

  const setTheme = useCallback((next: Theme) => setThemeState(next), [])

  const value = useMemo(
    () => ({ theme, resolvedTheme, setTheme }),
    [theme, resolvedTheme, setTheme],
  )

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}

export function useTheme(): ThemeContextValue {
  const context = useContext(ThemeContext)
  if (!context) throw new Error('useTheme must be used inside ThemeProvider')
  return context
}
