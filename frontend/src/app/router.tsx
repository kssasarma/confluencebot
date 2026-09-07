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

export const router = createBrowserRouter(
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
