import { Button } from '../../shared/ui/index.ts'
import { useTranslation } from 'react-i18next'
import './SettingsPage.css'

/** Previous/next controls for server-paged lists; `page` is 1-based. */
export function Pager({ page, pages, onPage }: { page: number; pages: number; onPage: (page: number) => void }) {
  const { t } = useTranslation('settings')
  return (
    <nav className="settings__pager" aria-label={t('Pager.pagination')}>
      <Button size="sm" disabled={page <= 1} onClick={() => onPage(page - 1)}>
        {t('Pager.previous')}
      </Button>
      <span className="settings__muted">{t('Pager.pagePageOfPages', { page, pages })}</span>
      <Button size="sm" disabled={page >= pages} onClick={() => onPage(page + 1)}>
        {t('Pager.next')}
      </Button>
    </nav>
  )
}
