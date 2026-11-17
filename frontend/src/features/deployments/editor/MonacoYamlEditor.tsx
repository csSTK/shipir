import Editor from '@monaco-editor/react'
import { configureMonacoYaml } from 'monaco-yaml'
import { useSyncExternalStore } from 'react'
import composeSchema from './compose-spec.json'
import { useTranslation } from 'react-i18next'
import { editorFontSize } from './editorFontSize.ts'
import { COMPOSE_FILE_MATCH } from './composeFileMatch.ts'
import { monacoForYaml } from './monacoEnvironment.ts'

const SCHEMA_URI = 'inmemory://shipir/compose-spec.json'
let configured = false
function configureOnce() {
  if (configured) return
  configured = true
  const { $schema: _s, $id: _i, ...schema } = composeSchema as Record<string, unknown>
  configureMonacoYaml(monacoForYaml, {
    enableSchemaRequest: false,
    validate: true,
    completion: true,
    hover: true,
    schemas: [{ uri: SCHEMA_URI, fileMatch: COMPOSE_FILE_MATCH, schema }],
  })
}

const darkQuery = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null
function subscribeDark(cb: () => void) {
  darkQuery?.addEventListener('change', cb)
  return () => darkQuery?.removeEventListener('change', cb)
}

export interface MonacoYamlEditorProps {
  value: string
  onChange: (value: string) => void
  readOnly?: boolean
  label: string
  height: string
  /** Monaco language id; default `yaml` (with the Compose schema). */
  language?: string
  /** Model path; default `compose.yaml`. */
  path?: string
}

export default function MonacoYamlEditor({ value, onChange, readOnly, label, height, language = 'yaml', path = 'compose.yaml' }: MonacoYamlEditorProps) {
  const { t } = useTranslation('deployments')
  const dark = useSyncExternalStore(subscribeDark, () => darkQuery?.matches ?? false)
  const reducedMotion = typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  return (
    <Editor
      height={height}
      language={language}
      path={path}
      theme={dark ? 'vs-dark' : 'light'}
      value={value}
      beforeMount={configureOnce}
      onChange={(v) => onChange(v ?? '')}
      loading={<div className="ed-yaml__loading">{t('MonacoYamlEditor.loadingEditor')}</div>}
      options={{
        readOnly,
        ariaLabel: label,
        minimap: { enabled: false },
        scrollBeyondLastLine: false,
        fontSize: editorFontSize(),
        tabSize: 2,
        insertSpaces: true,
        automaticLayout: true,
        wordWrap: 'on',
        smoothScrolling: !reducedMotion,
        cursorSmoothCaretAnimation: reducedMotion ? 'off' : 'on',
        padding: { top: 10, bottom: 10 },
      }}
    />
  )
}
