import { Button } from '../../shared/ui/index.ts'
import './SettingsPage.css'

/** Previous/next controls for server-paged lists; `page` is 1-based. */
export function Pager({ page, pages, onPage }: { page: number; pages: number; onPage: (page: number) => void }) {
  return (
    <nav className="settings__pager" aria-label="Pagination">
      <Button size="sm" disabled={page <= 1} onClick={() => onPage(page - 1)}>
        Previous
      </Button>
      <span className="settings__muted">
        Page {page} of {pages}
      </span>
      <Button size="sm" disabled={page >= pages} onClick={() => onPage(page + 1)}>
        Next
      </Button>
    </nav>
  )
}
