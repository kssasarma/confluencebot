import { Link } from 'react-router-dom'
import { Compass } from 'lucide-react'
import EmptyState from '../components/ui/EmptyState'
import { useDocumentTitle } from '../hooks/useDocumentTitle'

export default function NotFoundRoute() {
  useDocumentTitle('Not found')

  return (
    <div className="flex h-full items-center justify-center">
      <EmptyState
        icon={<Compass size={18} />}
        title="This page does not exist"
        description="The link may be out of date, or the conversation may have been deleted."
        action={
          <Link
            to="/"
            className="inline-flex h-10 items-center rounded-lg bg-primary px-4 text-sm font-medium text-primary-foreground hover:bg-primary-hover"
          >
            Back to chat
          </Link>
        }
      />
    </div>
  )
}
