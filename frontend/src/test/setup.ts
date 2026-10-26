import '@testing-library/jest-dom/vitest'
import { createElement } from 'react'
import { vi } from 'vitest'
// Tests always run in English: initialises i18next with lng=en and the bundled English texts.
import '../shared/i18n/index.ts'

// Monaco does not run in jsdom (and is slow to load): tests get a plain textarea with the same contract.
vi.mock('../features/deployments/editor/MonacoYamlEditor.tsx', () => ({
  default: ({ value, onChange, readOnly, label }: { value: string; onChange: (v: string) => void; readOnly?: boolean; label: string }) =>
    createElement('textarea', {
      'aria-label': label,
      value,
      readOnly,
      onChange: (e: { target: { value: string } }) => onChange(e.target.value),
    }),
}))

// Same for the diff view: two read-only panes keep the contents assertable.
vi.mock('../features/deployments/editor/MonacoDiffEditor.tsx', () => ({
  default: ({ original, modified, label, sideBySide }: { original: string; modified: string; label: string; sideBySide: boolean }) =>
    createElement(
      'div',
      { role: 'group', 'aria-label': label, 'data-mode': sideBySide ? 'side-by-side' : 'inline' },
      createElement('pre', { 'data-testid': 'diff-original' }, original),
      createElement('pre', { 'data-testid': 'diff-modified' }, modified),
    ),
}))
