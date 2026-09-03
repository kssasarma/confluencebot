/**
 * Tailwind reads the design tokens; it does not define them.
 *
 * Every colour below is a `hsl(var(--token) / <alpha-value>)` reference, so `bg-primary/10` works
 * and a theme switch is a change of custom properties rather than a re-render. The tokens
 * themselves — and the contrast guarantees on them — live in `src/design/tokens.css`.
 */

import typography from '@tailwindcss/typography'

/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      fontFamily: {
        sans: ['InterVariable', 'Inter', 'system-ui', 'sans-serif'],
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Consolas', 'monospace'],
      },

      fontSize: {
        // 11px is the floor. Below it, metadata stops being legible for anyone reading at arm's
        // length, and the 9–10px labels this replaces were failing that test in both themes.
        '2xs': ['0.6875rem', { lineHeight: '1rem' }],
      },

      colors: {
        background: 'hsl(var(--background) / <alpha-value>)',
        foreground: 'hsl(var(--foreground) / <alpha-value>)',
        surface: {
          DEFAULT: 'hsl(var(--surface) / <alpha-value>)',
          hover: 'hsl(var(--surface-hover) / <alpha-value>)',
        },
        border: 'hsl(var(--border) / <alpha-value>)',
        ring: 'hsl(var(--ring) / <alpha-value>)',
        muted: {
          DEFAULT: 'hsl(var(--muted) / <alpha-value>)',
          foreground: 'hsl(var(--muted-foreground) / <alpha-value>)',
        },
        primary: {
          DEFAULT: 'hsl(var(--primary) / <alpha-value>)',
          foreground: 'hsl(var(--primary-foreground) / <alpha-value>)',
          emphasis: 'hsl(var(--primary-emphasis) / <alpha-value>)',
          soft: 'hsl(var(--primary-soft) / <alpha-value>)',
          hover: 'hsl(var(--primary-hover) / <alpha-value>)',
        },
        accent: {
          DEFAULT: 'hsl(var(--accent) / <alpha-value>)',
          foreground: 'hsl(var(--accent-foreground) / <alpha-value>)',
          emphasis: 'hsl(var(--accent-emphasis) / <alpha-value>)',
          soft: 'hsl(var(--accent-soft) / <alpha-value>)',
        },
        success: {
          DEFAULT: 'hsl(var(--success) / <alpha-value>)',
          foreground: 'hsl(var(--success-foreground) / <alpha-value>)',
          emphasis: 'hsl(var(--success-emphasis) / <alpha-value>)',
          soft: 'hsl(var(--success-soft) / <alpha-value>)',
        },
        warning: {
          DEFAULT: 'hsl(var(--warning) / <alpha-value>)',
          foreground: 'hsl(var(--warning-foreground) / <alpha-value>)',
          emphasis: 'hsl(var(--warning-emphasis) / <alpha-value>)',
          soft: 'hsl(var(--warning-soft) / <alpha-value>)',
        },
        danger: {
          DEFAULT: 'hsl(var(--danger) / <alpha-value>)',
          foreground: 'hsl(var(--danger-foreground) / <alpha-value>)',
          emphasis: 'hsl(var(--danger-emphasis) / <alpha-value>)',
          soft: 'hsl(var(--danger-soft) / <alpha-value>)',
          hover: 'hsl(var(--danger-hover) / <alpha-value>)',
        },
        info: {
          DEFAULT: 'hsl(var(--info) / <alpha-value>)',
          foreground: 'hsl(var(--info-foreground) / <alpha-value>)',
          emphasis: 'hsl(var(--info-emphasis) / <alpha-value>)',
          soft: 'hsl(var(--info-soft) / <alpha-value>)',
        },
      },

      boxShadow: {
        soft: 'var(--shadow-soft)',
        raised: 'var(--shadow-raised)',
        overlay: 'var(--shadow-overlay)',
      },

      transitionTimingFunction: {
        'out-expo': 'var(--ease-out-expo)',
      },

      transitionDuration: {
        fast: 'var(--duration-fast)',
        base: 'var(--duration-base)',
        slow: 'var(--duration-slow)',
      },

      // Named rather than numeric so that a component states its layer intent. Reading
      // `z-modal` tells you what something is; reading `z-50` tells you only what it beat.
      zIndex: {
        sticky: 'var(--z-sticky)',
        dropdown: 'var(--z-dropdown)',
        drawer: 'var(--z-drawer)',
        modal: 'var(--z-modal)',
        popover: 'var(--z-popover)',
        toast: 'var(--z-toast)',
        palette: 'var(--z-palette)',
      },

      keyframes: {
        'fade-in': { from: { opacity: '0' }, to: { opacity: '1' } },
        'fade-in-up': {
          from: { opacity: '0', transform: 'translateY(4px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
        'scale-in': {
          from: { opacity: '0', transform: 'scale(0.97)' },
          to: { opacity: '1', transform: 'scale(1)' },
        },
        'slide-in-right': {
          from: { transform: 'translateX(100%)' },
          to: { transform: 'translateX(0)' },
        },
        shimmer: {
          '100%': { transform: 'translateX(100%)' },
        },
        // A caret that fades rather than blinks: at 60fps a hard blink next to streaming text
        // reads as flicker.
        'pulse-soft': {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0.35' },
        },
      },

      animation: {
        'fade-in': 'fade-in var(--duration-base) var(--ease-out-expo)',
        'fade-in-up': 'fade-in-up var(--duration-base) var(--ease-out-expo)',
        'scale-in': 'scale-in var(--duration-fast) var(--ease-out-expo)',
        'slide-in-right': 'slide-in-right var(--duration-base) var(--ease-out-expo)',
        shimmer: 'shimmer 1.6s infinite',
        'pulse-soft': 'pulse-soft 1.1s ease-in-out infinite',
      },

      typography: ({ theme }) => ({
        DEFAULT: {
          css: {
            '--tw-prose-body': theme('colors.foreground'),
            '--tw-prose-headings': theme('colors.foreground'),
            '--tw-prose-bold': theme('colors.foreground'),
            '--tw-prose-links': 'hsl(var(--primary-emphasis))',
            '--tw-prose-code': theme('colors.foreground'),
            '--tw-prose-quotes': theme('colors.muted.foreground'),
            '--tw-prose-quote-borders': theme('colors.border'),
            '--tw-prose-bullets': theme('colors.muted.foreground'),
            '--tw-prose-counters': theme('colors.muted.foreground'),
            '--tw-prose-hr': theme('colors.border'),
            '--tw-prose-th-borders': theme('colors.border'),
            '--tw-prose-td-borders': theme('colors.border'),
            maxWidth: 'none',
          },
        },
      }),
    },
  },
  plugins: [typography],
}
