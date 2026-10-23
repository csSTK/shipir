import Editor, { loader } from '@monaco-editor/react'
import * as monaco from 'monaco-editor'
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'
import { configureMonacoYaml } from 'monaco-yaml'
import YamlWorker from 'monaco-yaml/yaml.worker?worker'
import { useSyncExternalStore } from 'react'
import composeSchema from './compose-spec.json'

// Bundle Monaco from node_modules: no CDN loader, workers come from Vite.
self.MonacoEnvironment = {
  getWorker: (_id, label) => (label === 'yaml' ? new YamlWorker() : new EditorWorker()),
}
loader.config({ monaco })

const SCHEMA_URI = 'inmemory://shipir/compose-spec.json'
let configured = false
function configureOnce() {
  if (configured) return
  configured = true
  const { $schema: _s, $id: _i, ...schema } = composeSchema as Record<string, unknown>
  configureMonacoYaml(monaco, {
    enableSchemaRequest: false,
    validate: true,
    completion: true,
    hover: true,
    schemas: [{ uri: SCHEMA_URI, fileMatch: ['*'], schema }],
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
}

export default function MonacoYamlEditor({ value, onChange, readOnly, label, height }: MonacoYamlEditorProps) {
  const dark = useSyncExternalStore(subscribeDark, () => darkQuery?.matches ?? false)
  const reducedMotion = typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  return (
    <Editor
      height={height}
      language="yaml"
      path="compose.yaml"
      theme={dark ? 'vs-dark' : 'light'}
      value={value}
      beforeMount={configureOnce}
      onChange={(v) => onChange(v ?? '')}
      loading={<div className="ed-yaml__loading">Loading editor…</div>}
      options={{
        readOnly,
        ariaLabel: label,
        minimap: { enabled: false },
        scrollBeyondLastLine: false,
        fontSize: 13,
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
