import { Lock, Hammer } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { Button, EmptyState, PageHeader } from '../../shared/ui/index.ts'

/** Simple stand-ins until the dedicated pages land. */
export function PlaceholderPage({ title }: { title: string }) {
  return (
    <section>
      <PageHeader title={title} />
      <EmptyState icon={Hammer} title="Coming soon" />
    </section>
  )
}

export function NotAllowedPage() {
  const navigate = useNavigate()
  return (
    <section>
      <PageHeader title="Not allowed" />
      <div role="alert">
        <EmptyState
          icon={Lock}
          title="Restricted"
          description="You are not allowed to view this page."
          action={
            <Button variant="secondary" onClick={() => navigate('/')}>
              Back to environments
            </Button>
          }
        />
      </div>
    </section>
  )
}
