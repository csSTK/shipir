import { DiffEditor } from '@monaco-editor/react'
import type { editor } from 'monaco-editor'
import { useEffect, useRef, useSyncExternalStore } from 'react'
import { useTranslation } from 'react-i18next'
import { editorFontSize } from './editorFontSize.ts'
import './monacoEnvironment.ts'


const darkQuery = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null
function subscribeDark(cb: () => void) {
  darkQuery?.addEventListener('change', cb)
  return () => darkQuery?.removeEventListener('change', cb)
}

export interface MonacoDiffEditorProps {
  original: string
  modified: string
  label: string
  height: string
  /** Side by side when true, inline otherwise. */
  sideBySide: boolean
  /** Monaco language id for highlighting; default `yaml`. */
  language?: string
}

/** Read-only Monaco diff view. */
export default function MonacoDiffEditor({ original, modified, label, height, sideBySide, language = 'yaml' }: MonacoDiffEditorProps) {
  const { t } = useTranslation('deployments')
  const dark = useSyncExternalStore(subscribeDark, () => darkQuery?.matches ?? false)
  const reducedMotion = typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  // @monaco-editor/react disposes the two text models before it disposes the diff widget, and Monaco throws "TextModel
  // got disposed before DiffEditorWidget model got reset" as an uncaught error. So the wrapper keeps the models and
  // this component disposes them once the widget is gone (shipr-k31n.60).
  const models = useRef<editor.IDiffEditorModel | null>(null)
  useEffect(
    () => () => {
      const m = models.current
      models.current = null
      if (m) queueMicrotask(() => { m.original.dispose(); m.modified.dispose() })
    },
    [],
  )
  return (
    <DiffEditor
      keepCurrentOriginalModel
      keepCurrentModifiedModel
      onMount={(diffEditor) => { models.current = diffEditor.getModel() }}
      height={height}
      language={language}
      theme={dark ? 'vs-dark' : 'light'}
      original={original}
      modified={modified}
      loading={<div className="ed-yaml__loading">{t('MonacoDiffEditor.loadingDiff')}</div>}
      options={{
        readOnly: true,
        originalEditable: false,
        ariaLabel: label,
        renderSideBySide: sideBySide,
        useInlineViewWhenSpaceIsLimited: true,
        minimap: { enabled: false },
        scrollBeyondLastLine: false,
        fontSize: editorFontSize(),
        automaticLayout: true,
        wordWrap: 'on',
        renderOverviewRuler: false,
        smoothScrolling: !reducedMotion,
        padding: { top: 10, bottom: 10 },
      }}
    />
  )
}
