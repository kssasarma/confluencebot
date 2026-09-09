import { Navigate, createBrowserRouter } from 'react-router-dom'
import { ChatProvider } from '../context/ChatContext'
import AppShell from './AppShell'
import ChatRoute from '../routes/ChatRoute'
import NotFoundRoute from '../routes/NotFoundRoute'

/** The conversation state has to outlive navigation between conversations. */
function ChatLayout() {
  return (
    <ChatProvider>
      <AppShell />
    </ChatProvider>
  )
}

/**
 * Built on demand rather than as a module-level constant.
 *
 * `createBrowserRouter` freezes its initial match against whatever URL the page has *right now* —
 * and `App.tsx` only mounts the router once sign-in has resolved. Build it eagerly at import time
 * and, for an SSO landing, that "right now" is still `/sso/callback`: the address bar gets rewritten
 * to `/` afterwards, but a plain history rewrite does not tell an already-built router anything, so
 * it goes on showing whatever it matched against the stale path instead of the index redirect below.
 * Deferring construction to the moment it is actually needed means the URL has already settled.
 */
export function createAppRouter() {
  return createBrowserRouter(
    [
      {
        element: <ChatLayout />,
        children: [
          { index: true, element: <Navigate to="/chat" replace /> },
          // `/chat` is the stateless welcome screen: no chat id, no session, nothing created until
          // the reader sends a first message. `/chat/:chatId` is one conversation, addressed by URL.
          { path: 'chat', element: <ChatRoute /> },
          { path: 'chat/:chatId', element: <ChatRoute /> },
          { path: '*', element: <NotFoundRoute /> },
        ],
      },
    ],
    // Lets the app be served from a sub-path (e.g. a project site on a static host) without any
    // hardcoded prefix — `BASE_URL` is resolved from the VITE_BASE_PATH env var at build time (see
    // vite.config.ts), and defaults to '/' for root deployments (Docker/nginx).
    { basename: import.meta.env.BASE_URL },
  )
}
