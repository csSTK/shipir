import { useState } from 'react'
import { describeDeploymentError } from '../deploymentErrors.ts'
import { useTemplates, type AppTemplate } from './api.ts'
import './templates.css'
import { Button, TextField } from '../../../shared/ui/index.ts'
import { useCurrentUser } from '../../auth/index.ts'
import { useTranslation } from 'react-i18next'

/** Searchable grid of bundled app templates; each card offers Install (`onInstall`) and Customize (`onSelect`), both hidden for VIEWER. */
export function TemplatePicker({ onSelect, onInstall, selectedId }: { onSelect: (template: AppTemplate) => void; onInstall?: (template: AppTemplate) => void; selectedId?: string | null }) {
  const { t: tr } = useTranslation('deployments')
  const { data, isPending, error, refetch } = useTemplates()
  const { data: user } = useCurrentUser()
  // A VIEWER is read-only: no install, no wizard. Hidden until the role is known.
  const canAct = !!user && user.role !== 'VIEWER'
  const [query, setQuery] = useState('')
  const q = query.trim().toLowerCase()
  const shown = (data ?? []).filter((t) => q === '' || t.name.toLowerCase().includes(q) || t.description.toLowerCase().includes(q))

  return (
    <div className="tp">
      <TextField label={tr('TemplatePicker.searchTemplates')} type="search" value={query} onChange={(e) => setQuery(e.target.value)} autoComplete="off" spellCheck={false} />
      {isPending && (
        <p className="tp__hint" role="status">
          {tr('TemplatePicker.loadingTemplates')}
        </p>
      )}
      {error && (
        <div role="alert" className="ui-error">
          <p>{describeDeploymentError(error, tr('TemplatePicker.couldNotLoadTheTemplates'))}</p>
          <Button variant="secondary" onClick={() => void refetch()}>
            {tr('TemplatePicker.retry')}
          </Button>
        </div>
      )}
      {data && shown.length === 0 && <p className="tp__hint">{data.length === 0 ? tr('TemplatePicker.noTemplatesAvailable') : tr('TemplatePicker.noTemplateMatchesYourSearch')}</p>}
      {shown.length > 0 && (
        <ul className="tp__grid" aria-label={tr('TemplatePicker.templates')}>
          {shown.map((t) => (
            <li key={t.id} className="tp__card" data-selected={selectedId === t.id}>
              <span className="tp__logo" aria-hidden="true">
                {t.name.charAt(0).toUpperCase()}
              </span>
              <span className="tp__name" id={`tp-name-${t.id}`}>{t.name}</span>
              <span className="tp__desc">{t.description}</span>
              {canAct && (
                <div className="tp__actions">
                  {onInstall && (
                    <Button variant="primary" size="sm" onClick={() => onInstall(t)} aria-describedby={`tp-name-${t.id}`}>
                      {tr('InstallTemplateDialog.install')}
                    </Button>
                  )}
                  <Button variant="secondary" size="sm" onClick={() => onSelect(t)} aria-pressed={selectedId === t.id} aria-describedby={`tp-name-${t.id}`}>
                    {tr('TemplatePicker.customize')}
                  </Button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
