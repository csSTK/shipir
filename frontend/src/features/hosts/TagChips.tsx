import { Badge } from '../../shared/ui/index.ts'
import { useTranslation } from 'react-i18next'
import './TagChips.css'

/** Read-only tag chips (every role). Renders nothing for an empty list. */
export function TagChips({ tags }: { tags: string[] }) {
  const { t } = useTranslation('hosts')
  if (tags.length === 0) return null
  return (
    <ul className="tag-chips" aria-label={t('TagChips.tags')}>
      {tags.map((tag) => (
        <li key={tag}>
          <Badge tone="accent" dot={false}>
            {tag}
          </Badge>
        </li>
      ))}
    </ul>
  )
}

/** Toggle chips for the overview filter; `aria-pressed` marks the selected tags. */
export function TagFilter({ tags, selected, onToggle }: { tags: string[]; selected: string[]; onToggle: (tag: string) => void }) {
  const { t } = useTranslation('hosts')
  return (
    <div className="tag-filter" role="group" aria-label={t('TagChips.filterByTag')}>
      {tags.map((tag) => {
        const pressed = selected.includes(tag)
        return (
          <button key={tag} type="button" className="tag-filter__chip" aria-pressed={pressed} onClick={() => onToggle(tag)}>
            {tag}
          </button>
        )
      })}
    </div>
  )
}
