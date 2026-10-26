import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { HostOverview } from '../../features/hosts/index.ts'
import { apiFetch } from '../../shared/api/client.ts'
import { EnvironmentSwitcher } from './EnvironmentSwitcher.tsx'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

const env = (id: string, name: string, tags: string[]): HostOverview => ({
  id,
  name,
  connectionType: 'TCP',
  endpoint: `tcp://${id}.example:2376`,
  status: 'UP',
  error: null,
  dockerVersion: '27.0.0',
  cpus: 4,
  memoryBytes: 8 * 1024 ** 3,
  containers: { running: 0, stopped: 0, healthy: 0, unhealthy: 0 },
  images: 0,
  volumes: 0,
  tags,
})

function renderSwitcher(envs: HostOverview[], path = '/environments/berlin/images') {
  vi.mocked(apiFetch).mockResolvedValue(envs)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/environments/:id/*" element={<EnvironmentSwitcher />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Menu children in order: 'sep' for a separator, otherwise the item label. */
async function menuSequence(): Promise<string[]> {
  fireEvent.keyDown(await screen.findByRole('button', { name: /Environment: / }), { key: 'Enter' })
  const menu = await screen.findByRole('menu')
  return Array.from(menu.children).map((child) =>
    child.getAttribute('role') === 'separator' ? 'sep' : (child.textContent ?? ''),
  )
}

describe('EnvironmentSwitcher grouping', () => {
  it('groups environments by their first tag, untagged last, with a separator between groups', async () => {
    renderSwitcher([
      env('berlin', 'Berlin', ['eu']),
      env('staging', 'Staging', []),
      env('tokyo', 'Tokyo', ['apac']),
      env('local', 'Local', ['eu', 'prod']),
    ])

    expect(await menuSequence()).toEqual(['Tokyo', 'sep', 'Berlin', 'Local', 'sep', 'Staging'])
  })

  it('shows no separator when every environment shares the same group', async () => {
    renderSwitcher([env('berlin', 'Berlin', ['eu']), env('local', 'Local', ['eu'])])

    expect(await menuSequence()).toEqual(['Berlin', 'Local'])
  })
})
