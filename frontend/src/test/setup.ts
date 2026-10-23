import '@testing-library/jest-dom/vitest'
import { createElement } from 'react'
import { vi } from 'vitest'

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
