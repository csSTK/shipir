import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { GitSourcePanel } from './GitSourcePanel.tsx'
import type { GitInfo } from './api.ts'

afterEach(cleanup)

const base: GitInfo = {
  repoUrl: 'https://example.com/r.git',
  ref: 'main',
  composePath: 'compose.yaml',
  credentialId: null,
  lastCommitSha: null,
  autoUpdateEnabled: false,
  autoUpdateIntervalSeconds: 300,
  lastPolledAt: null,
  lastPollError: null,
}
const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
function panel(git: GitInfo) {
  return (
    <QueryClientProvider client={queryClient}>
      <GitSourcePanel deploymentId="s1" hostId="h1" git={git} canWrite />
    </QueryClientProvider>
  )
}

describe('GitSourcePanel auto-update resync', () => {
  it('shows settings changed elsewhere while the form is untouched', () => {
    const view = render(panel(base))
    view.rerender(panel({ ...base, autoUpdateEnabled: true, autoUpdateIntervalSeconds: 600 }))
    expect(screen.getByRole('switch', { name: 'Auto-update' })).toBeChecked()
    expect(screen.getByRole('spinbutton')).toHaveValue(10)
  })

  it('keeps unsaved edits when the settings refetch with other values', () => {
    const view = render(panel(base))
    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '7' } })
    view.rerender(panel({ ...base, autoUpdateEnabled: true, autoUpdateIntervalSeconds: 600 }))
    expect(screen.getByRole('spinbutton')).toHaveValue(7)
  })
})
