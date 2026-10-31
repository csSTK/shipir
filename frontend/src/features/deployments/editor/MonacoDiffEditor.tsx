import { DiffEditor } from '@monaco-editor/react'
import { useSyncExternalStore } from 'react'
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
}

/** Read-only Monaco diff view. */
export default function MonacoDiffEditor({ original, modified, label, height, sideBySide }: MonacoDiffEditorProps) {
  const { t } = useTranslation('deployments')
  const dark = useSyncExternalStore(subscribeDark, () => darkQuery?.matches ?? false)
  const reducedMotion = typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  return (
    <DiffEditor
      height={height}
      language="yaml"
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
