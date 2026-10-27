import { QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { queryClient } from '../../app/queryClient.ts'
import { apiFetch } from '../../shared/api/client.ts'
import { i18n } from '../../shared/i18n/index.ts'
import { NotificationsPage } from './NotificationsPage.tsx'
import { NOTIFICATION_TYPES, NOTIFICATION_TYPE_LABELS, useUnreadCount, type NotificationType } from './api.ts'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

type N = {
  id: string
  type: NotificationType
  hostId: string | null
  hostName: string
  deploymentId: string | null
  deploymentName: string | null
  containerId: string | null
  containerName: string | null
  message: string
  read: boolean
  createdAt: string
}
const base = { deploymentId: null, deploymentName: null, containerId: null, containerName: null }
const down: N = {
  ...base, id: 'n1', type: 'HOST_DOWN', hostId: 'h1', hostName: 'Staging',
  message: 'Staging is unreachable', read: false, createdAt: new Date().toISOString(),
}
const up: N = {
  ...base, id: 'n2', type: 'HOST_UP', hostId: null, hostName: 'Old box',
  message: 'Old box is back', read: true, createdAt: new Date(Date.now() - 3_600_000).toISOString(),
}
const deployFailed: N = {
  ...base, id: 'n3', type: 'DEPLOYMENT_FAILED', hostId: 'h1', hostName: 'Staging',
  deploymentId: 'd1', deploymentName: 'web', message: 'web failed to deploy', read: false,
  createdAt: new Date().toISOString(),
}
const crashed: N = {
  ...base, id: 'n4', type: 'CONTAINER_CRASHED', hostId: 'h1', hostName: 'Staging',
  containerId: 'c1', containerName: 'nginx', message: 'nginx crashed', read: false,
  createdAt: new Date().toISOString(),
}
const deletedDeployment: N = {
  ...base, id: 'n5', type: 'DEPLOYMENT_FAILED', hostId: 'h1', hostName: 'Staging',
  deploymentName: 'old-api', message: 'old-api failed to deploy', read: true,
  createdAt: new Date().toISOString(),
}
const gitFailed: N = {
  ...base, id: 'n6', type: 'GIT_AUTO_UPDATE_FAILED', hostId: 'h1', hostName: 'Staging',
  deploymentId: 'd1', deploymentName: 'web', message: 'web auto-update failed', read: true,
  createdAt: new Date().toISOString(),
}

const imageApplied: N = {
  ...base, id: 'n7', type: 'IMAGE_UPDATE_APPLIED', hostId: 'h1', hostName: 'Staging',
  deploymentId: 'd1', deploymentName: 'web', message: 'web images updated', read: false,
  createdAt: new Date().toISOString(),
}
const imageFailed: N = {
  ...base, id: 'n8', type: 'IMAGE_UPDATE_FAILED', hostId: 'h1', hostName: 'Staging',
  deploymentId: 'd1', deploymentName: 'web', message: 'web image update failed', read: false,
  createdAt: new Date().toISOString(),
}
const uptimeDown: N = {
  ...base, id: 'n9', type: 'UPTIME_DOWN', hostId: 'h1', hostName: 'Staging',
  deploymentId: 'd1', deploymentName: 'web', message: 'Uptime check "home" is down', read: false,
  createdAt: new Date().toISOString(),
}
const uptimeUp: N = {
  ...base, id: 'n10', type: 'UPTIME_UP', hostId: 'h1', hostName: 'Staging',
  deploymentId: 'd1', deploymentName: 'web', message: 'Uptime check "home" is back up', read: false,
  createdAt: new Date().toISOString(),
}

let items: N[]
let calls: string[]

beforeEach(() => {
  queryClient.clear()
  items = [down, up]
  calls = []
  apiFetchMock.mockImplementation(async (path, options) => {
    const method = options?.method ?? 'GET'
    calls.push(`${method} ${path}`)
    if (method === 'POST' && path === '/notifications/read-all') {
      items = items.map((n) => ({ ...n, read: true }))
      return undefined as never
    }
    if (method === 'POST' && path.endsWith('/read')) {
      const id = path.split('/')[2]
      items = items.map((n) => (n.id === id ? { ...n, read: true } : n))
      return undefined as never
    }
    if (path === '/notifications/unread-count') {
      return { count: items.filter((n) => !n.read).length } as never
    }
    if (path.startsWith('/notifications')) {
      const unreadOnly = path.includes('unreadOnly=true')
      const content = unreadOnly ? items.filter((n) => !n.read) : items
      return { content, totalElements: content.length } as never
    }
    throw new Error(`unexpected ${method} ${path}`)
  })
})
afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

function Badge() {
  const { data } = useUnreadCount()
  return <p data-testid="badge">{data?.count ?? '-'}</p>
}

function renderPage() {
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Badge />
        <NotificationsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('NotificationsPage', () => {
  it('renders the image update types with labels instead of crashing', async () => {
    items = [imageApplied, imageFailed]
    renderPage()
    expect(await screen.findByText('Image update applied to "web".')).toBeInTheDocument()
    expect(screen.getByText('Image update of "web" failed.')).toBeInTheDocument()
    expect(screen.getByText('Image update applied')).toBeInTheDocument()
    expect(screen.getByText('Image update failed')).toBeInTheDocument()
  })

  it('renders the uptime types with labels instead of crashing', async () => {
    items = [uptimeDown, uptimeUp]
    renderPage()
    expect(await screen.findByText('Uptime check of "web" is down.')).toBeInTheDocument()
    expect(screen.getByText('Uptime check of "web" is back up.')).toBeInTheDocument()
    expect(screen.getByText('Uptime check down')).toBeInTheDocument()
    expect(screen.getByText('Uptime check up')).toBeInTheDocument()
  })

  it('has a label for every notification type', () => {
    for (const type of NOTIFICATION_TYPES) expect(NOTIFICATION_TYPE_LABELS[type]).toBeTruthy()
    expect(NOTIFICATION_TYPES).toContain('IMAGE_UPDATE_APPLIED')
    expect(NOTIFICATION_TYPES).toContain('IMAGE_UPDATE_FAILED')
    expect(NOTIFICATION_TYPES).toContain('UPTIME_DOWN')
    expect(NOTIFICATION_TYPES).toContain('UPTIME_UP')
  })

  it('lists notifications, linking only hosts that still exist', async () => {
    renderPage()
    expect(await screen.findByText('Environment "Staging" is down.')).toBeInTheDocument()
    expect(screen.getByText('Environment "Old box" is back up.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Staging' })).toHaveAttribute('href', '/environments/h1')
    expect(screen.queryByRole('link', { name: 'Old box' })).toBeNull()
    expect(screen.getByText('Host down')).toBeInTheDocument()
    expect(screen.getByText('Host up')).toBeInTheDocument()
  })

  it('labels every notification type', async () => {
    items = [down, up, deployFailed, crashed, gitFailed]
    renderPage()
    expect(await screen.findByText('Deployment failed')).toBeInTheDocument()
    expect(screen.getByText('Container crashed')).toBeInTheDocument()
    expect(screen.getByText('Git auto-update failed')).toBeInTheDocument()
  })

  it('links deployment and container notifications to their target', async () => {
    items = [deployFailed, crashed]
    renderPage()
    expect(await screen.findByRole('link', { name: 'web' })).toHaveAttribute(
      'href',
      '/environments/h1/deployments/d1',
    )
    expect(screen.getByRole('link', { name: 'nginx' })).toHaveAttribute(
      'href',
      '/environments/h1/containers/c1',
    )
  })

  it('shows a deleted deployment by its name without a link', async () => {
    items = [deletedDeployment]
    renderPage()
    expect(await screen.findByText('old-api')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'old-api' })).toBeNull()
    expect(screen.getByRole('link', { name: 'Staging' })).toHaveAttribute('href', '/environments/h1')
  })

  it('filters to unread', async () => {
    renderPage()
    await screen.findByText('Environment "Old box" is back up.')
    fireEvent.click(screen.getByRole('checkbox', { name: 'Unread only' }))
    await waitFor(() => expect(screen.queryByText('Environment "Old box" is back up.')).toBeNull())
    expect(await screen.findByText('Environment "Staging" is down.')).toBeInTheDocument()
  })

  it('marks one as read and lowers the badge', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByTestId('badge')).toHaveTextContent('1'))
    fireEvent.click(await screen.findByRole('button', { name: 'Mark as read' }))
    await waitFor(() => expect(screen.getByTestId('badge')).toHaveTextContent('0'))
    expect(calls).toContain('POST /notifications/n1/read')
    expect(screen.queryByRole('button', { name: 'Mark as read' })).toBeNull()
  })

  it('marks all as read', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByTestId('badge')).toHaveTextContent('1'))
    fireEvent.click(await screen.findByRole('button', { name: 'Mark all as read' }))
    await waitFor(() => expect(screen.getByTestId('badge')).toHaveTextContent('0'))
    expect(calls).toContain('POST /notifications/read-all')
  })

  it('shows the empty state', async () => {
    items = []
    renderPage()
    expect(await screen.findByText('No notifications yet.')).toBeInTheDocument()
  })

  it('shows an error state', async () => {
    apiFetchMock.mockRejectedValue(new Error('boom'))
    renderPage()
    expect(await screen.findByRole('alert', {}, { timeout: 5000 })).toHaveTextContent('Could not load notifications.')
  }, 10_000)

  it('builds the message text in German from type and names, not from the stored English message', async () => {
    items = [down, up, deployFailed, crashed, gitFailed, imageApplied, imageFailed, uptimeDown, uptimeUp]
    await i18n.changeLanguage('de')
    try {
      renderPage()
      expect(await screen.findByText('Umgebung "Staging" ist nicht erreichbar.')).toBeInTheDocument()
      expect(screen.getByText('Umgebung "Old box" ist wieder erreichbar.')).toBeInTheDocument()
      expect(screen.getByText('Deployment "web" ist fehlgeschlagen.')).toBeInTheDocument()
      expect(screen.getByText('Container "nginx" ist abgestürzt.')).toBeInTheDocument()
      expect(screen.getByText('Git-Auto-Update von "web" ist fehlgeschlagen.')).toBeInTheDocument()
      expect(screen.getByText('Image-Update für "web" wurde angewendet.')).toBeInTheDocument()
      expect(screen.getByText('Image-Update von "web" ist fehlgeschlagen.')).toBeInTheDocument()
      expect(screen.getByText('Uptime-Check von "web" meldet einen Ausfall.')).toBeInTheDocument()
      expect(screen.getByText('Uptime-Check von "web" ist wieder erfolgreich.')).toBeInTheDocument()
      expect(screen.queryByText('Staging is unreachable')).toBeNull()
    } finally {
      await i18n.changeLanguage('en')
    }
  })
})
