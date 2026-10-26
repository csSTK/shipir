import { lazy, Suspense } from 'react'
import { useTranslation } from 'react-i18next'
import './editor.css'

// Monaco is large: load it only when an editor is shown.
const MonacoYamlEditor = lazy(() => import('./MonacoYamlEditor.tsx'))

export interface YamlEditorProps {
  value: string
  onChange?: (value: string) => void
  /** Accessible name of the editor. */
  label: string
  readOnly?: boolean
  /** Server-side problem (e.g. INVALID_COMPOSE); `details` are listed below the message. */
  error?: { message: string; details?: string[] } | null
  height?: string
}

/** Compose YAML editor: Monaco with local Compose-spec schema validation, server errors inline. */
export function YamlEditor({ value, onChange, label, readOnly = false, error, height = '320px' }: YamlEditorProps) {
  const { t } = useTranslation('deployments')
  return (
    <div className="ed-yaml">
      <div className="ed-yaml__frame" data-invalid={error ? 'true' : undefined} style={{ height }}>
        <Suspense fallback={<div className="ed-yaml__loading" role="status">{t('YamlEditor.loadingEditor')}</div>}>
          <MonacoYamlEditor value={value} onChange={onChange ?? (() => {})} readOnly={readOnly} label={label} height={height} />
        </Suspense>
      </div>
      {error && (
        <div role="alert" className="ed-yaml__error">
          <p>{error.message}</p>
          {error.details && error.details.length > 0 && (
            <ul>
              {error.details.map((d, i) => (
                <li key={i}>
                  <code>{d}</code>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
