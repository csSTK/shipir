import { Lock, Hammer } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { Button, EmptyState, PageHeader } from '../../shared/ui/index.ts'
import { useTranslation } from 'react-i18next'

/** Simple stand-ins until the dedicated pages land. */
export function PlaceholderPage({ title }: { title: string }) {
  const { t } = useTranslation('app')
  return (
    <section>
      <PageHeader title={title} />
      <EmptyState icon={Hammer} title={t('PlaceholderPages.comingSoon')} />
    </section>
  )
}

export function NotAllowedPage() {
  const { t } = useTranslation('app')
  const navigate = useNavigate()
  return (
    <section>
      <PageHeader title={t('PlaceholderPages.notAllowed')} />
      <div role="alert">
        <EmptyState
          icon={Lock}
          title={t('PlaceholderPages.restricted')}
          description={t('PlaceholderPages.youAreNotAllowedTo')}
          action={
            <Button variant="secondary" onClick={() => navigate('/')}>
              {t('PlaceholderPages.backToEnvironments')}
            </Button>
          }
        />
      </div>
    </section>
  )
}
