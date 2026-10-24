import { lazy, Suspense } from 'react'
import './editor.css'

// Monaco is large: load it only when a diff is shown.
const MonacoDiffEditor = lazy(() => import('./MonacoDiffEditor.tsx'))

export interface YamlDiffEditorProps {
  original: string
  modified: string
  /** Accessible name of the diff. */
  label: string
  /** Side by side (wide screens) or inline (narrow). */
  sideBySide?: boolean
  height?: string
}

/** Read-only YAML diff: Monaco DiffEditor, loaded on demand. */
export function YamlDiffEditor({ original, modified, label, sideBySide = true, height = '280px' }: YamlDiffEditorProps) {
  return (
    <div className="ed-yaml__frame" style={{ height }}>
      <Suspense fallback={<div className="ed-yaml__loading" role="status">Loading diff…</div>}>
        <MonacoDiffEditor original={original} modified={modified} label={label} height={height} sideBySide={sideBySide} />
      </Suspense>
    </div>
  )
}
