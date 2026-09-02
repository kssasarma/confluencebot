import type { ReactNode } from 'react'
import { APP_TITLE } from '../../config/env'

export default function AuthLayout({ title, subtitle, children }: {
  title: string; subtitle?: string; children: ReactNode
}) {
  return (
    <div className="min-h-screen flex items-center justify-center bg-background px-4">
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-12 h-12 rounded-xl bg-primary/10 mb-4">
            <svg className="w-6 h-6 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z" />
            </svg>
          </div>
          <h1 className="text-2xl font-semibold text-foreground">{APP_TITLE}</h1>
          <p className="text-sm text-muted-foreground mt-1">{title}</p>
          {subtitle && <p className="text-xs text-muted-foreground mt-1">{subtitle}</p>}
        </div>
        <div className="bg-surface border border-border rounded-2xl p-6 shadow-sm">
          {children}
        </div>
      </div>
    </div>
  )
}
