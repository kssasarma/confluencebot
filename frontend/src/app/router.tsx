import { lazy } from 'react'
import { Navigate, Outlet, createBrowserRouter } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { ChatProvider } from '../context/ChatContext'
import AppShell from './AppShell'
import NewChatRedirect from '../routes/NewChatRedirect'
import ChatRoute from '../routes/ChatRoute'
import SettingsRoute from '../routes/SettingsRoute'
import NotFoundRoute from '../routes/NotFoundRoute'

// Admin is ~400 lines that only administrators can act on. Loading it on demand keeps it out of
// the bundle every other user downloads.
const AdminRoute = lazy(() => import('../routes/AdminRoute'))

/**
 * Only administrators past this point.
 *
 * A client-side guard is a navigation convenience, not a security boundary — the API enforces the
 * role on every request. Its job here is to avoid rendering a screen whose every call would 403.
 */
function RequireAdmin() {
  const { isAdmin } = useAuth()
  return isAdmin ? <Outlet /> : <Navigate to="/" replace />
}

/** The conversation state has to outlive navigation between conversations. */
function ChatLayout() {
  return (
    <ChatProvider>
      <AppShell />
    </ChatProvider>
  )
}

export const router = createBrowserRouter(
  [
    {
      element: <ChatLayout />,
      children: [
        { index: true, element: <NewChatRedirect /> },
        { path: 'chat/:chatId', element: <ChatRoute /> },
        { path: 'settings', element: <SettingsRoute /> },
        {
          element: <RequireAdmin />,
          children: [{ path: 'admin', element: <AdminRoute /> }],
        },
        { path: '*', element: <NotFoundRoute /> },
      ],
    },
  ],
  // Lets the app be served from a sub-path (e.g. GitLab Pages project sites) without any
  // hardcoded prefix — `BASE_URL` is whatever `vite build --base=...` was given at build time,
  // and defaults to '/' for root deployments (Docker/nginx).
  { basename: import.meta.env.BASE_URL },
)
