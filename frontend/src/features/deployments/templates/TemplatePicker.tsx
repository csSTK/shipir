import { useId, useState } from 'react'
import { describeDeploymentError } from '../deploymentErrors.ts'
import { useTemplates, type AppTemplate } from './api.ts'
import './templates.css'
import { Button } from '../../../shared/ui/index.ts'

/** Searchable grid of bundled app templates; choosing one hands it to `onSelect`. */
export function TemplatePicker({ onSelect, selectedId }: { onSelect: (template: AppTemplate) => void; selectedId?: string | null }) {
  const searchId = useId()
  const { data, isPending, error, refetch } = useTemplates()
  const [query, setQuery] = useState('')
  const q = query.trim().toLowerCase()
  const shown = (data ?? []).filter((t) => q === '' || t.name.toLowerCase().includes(q) || t.description.toLowerCase().includes(q))

  return (
    <div className="tp">
      <label className="sk-field" htmlFor={searchId}>
        <span>Search templates</span>
        <input id={searchId} type="search" value={query} onChange={(e) => setQuery(e.target.value)} autoComplete="off" spellCheck={false} />
      </label>
      {isPending && (
        <p className="tp__hint" role="status">
          Loading templates…
        </p>
      )}
      {error && (
        <div role="alert" className="sk-error">
          <p>{describeDeploymentError(error, 'Could not load the templates.')}</p>
          <Button variant="secondary" onClick={() => void refetch()}>
            Retry
          </Button>
        </div>
      )}
      {data && shown.length === 0 && <p className="tp__hint">{data.length === 0 ? 'No templates available.' : 'No template matches your search.'}</p>}
      {shown.length > 0 && (
        <ul className="tp__grid" aria-label="Templates">
          {shown.map((t) => (
            <li key={t.id}>
              <button type="button" className="tp__card" aria-pressed={selectedId === t.id} onClick={() => onSelect(t)}>
                <span className="tp__logo" aria-hidden="true">
                  {t.name.charAt(0).toUpperCase()}
                </span>
                <span className="tp__name">{t.name}</span>
                <span className="tp__desc">{t.description}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
