import Editor from '@monaco-editor/react'
import { configureMonacoYaml } from 'monaco-yaml'
import type { editor as MonacoEditor } from 'monaco-editor'
import { useEffect, useRef, useSyncExternalStore } from 'react'
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
  // @monaco-editor/react rewrites the whole model whenever its controlled `value` differs from the model. That
  // echo arrives after React re-renders, by which time the user may have typed more, and would erase those
  // characters. So the editor is not controlled: `defaultValue` only seeds new models, and this effect applies
  // the parent's value itself, skipping the echoes of our own onChange.
  const editorRef = useRef<MonacoEditor.IStandaloneCodeEditor | null>(null)
  const pending = useRef<string[]>([]) // values we emitted and the parent has not echoed back yet
  const applying = useRef(false)
  useEffect(() => {
    const i = pending.current.indexOf(value)
    if (i >= 0) {
      pending.current.splice(0, i + 1) // our own echo (possibly stale): ignore
      return
    }
    pending.current = [] // an external value wins over everything typed so far
    const editor = editorRef.current
    if (!editor || editor.getValue() === value) return
    applying.current = true
    try {
      if (readOnly) editor.setValue(value)
      else {
        // An edit (not setValue) so Ctrl+Z can undo a reset.
        editor.executeEdits('', [{ range: editor.getModel()!.getFullModelRange(), text: value, forceMoveMarkers: true }])
        editor.pushUndoStop()
      }
    } finally {
      applying.current = false
    }
  }, [value, readOnly])
  return (
    <Editor
      height={height}
      language={language}
      path={path}
      theme={dark ? 'vs-dark' : 'light'}
      defaultValue={value}
      onMount={(editor) => {
        editorRef.current = editor
      }}
      beforeMount={configureOnce}
      onChange={(v) => {
        if (applying.current) return
        pending.current.push(v ?? '')
        if (pending.current.length > 100) pending.current.shift()
        onChange(v ?? '')
      }}
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
