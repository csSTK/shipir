import { Badge } from '../../shared/ui/index.ts'
import './TagChips.css'

/** Read-only tag chips (every role). Renders nothing for an empty list. */
export function TagChips({ tags }: { tags: string[] }) {
  if (tags.length === 0) return null
  return (
    <ul className="tag-chips" aria-label="Tags">
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
  return (
    <div className="tag-filter" role="group" aria-label="Filter by tag">
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
