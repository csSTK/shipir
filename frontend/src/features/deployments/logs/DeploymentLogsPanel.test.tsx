import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TopicHandlers } from '../../../shared/realtime/stomp.ts'

const subscribe = vi.fn()
vi.mock('../../../shared/realtime/stomp.ts', () => ({ subscribeToTopic: (...a: unknown[]) => subscribe(...a) }))
vi.mock('../../containers/api.ts', () => ({
  useContainers: () => ({
    data: [
      { id: 'c1', name: 'shop-web-1', composeProject: 'shop', composeService: 'web' },
      { id: 'c2', name: 'shop-db-1', composeProject: 'shop', composeService: 'db' },
      { id: 'c3', name: 'other-1', composeProject: 'other', composeService: 'x' },
    ],
  }),
}))

import { DeploymentLogsPanel } from './DeploymentLogsPanel.tsx'

let handlers: TopicHandlers
const unsubscribe = vi.fn()
const send = (frame: object) => act(() => handlers.onMessage(JSON.stringify(frame)))
const line = (seq: number, service: string, text: string, stream = 'STDOUT') =>
  send({ seq, type: 'LINE', service, container: `shop-${service}-1`, containerId: service === 'web' ? 'c1' : 'c2', stream, line: text })

function open() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <DeploymentLogsPanel hostId="h1" project="shop" />
    </QueryClientProvider>,
  )
  fireEvent.click(screen.getByRole('button', { name: 'Show logs' }))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})
beforeEach(() => {
  // Frames are applied once per animation frame; run it synchronously so tests see each message at once.
  vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => (cb(0), 0))
  subscribe.mockReset()
  unsubscribe.mockReset()
  subscribe.mockImplementation((_d: string, h: TopicHandlers) => {
    handlers = h
    return unsubscribe
  })
})

describe('DeploymentLogsPanel', () => {
  it('does not subscribe until the logs are shown, then subscribes to the project topic', () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <DeploymentLogsPanel hostId="h1" project="shop" />
      </QueryClientProvider>,
    )
    expect(subscribe).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Show logs' }))
    expect(subscribe).toHaveBeenCalledWith('/topic/hosts/h1/deployments/shop/logs', expect.anything(), {
      headers: { tail: '100', timestamps: 'false' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Hide logs' }))
    expect(unsubscribe).toHaveBeenCalled()
  })

  it('shows lines with their service name and a colour per service', () => {
    open()
    line(1, 'web', 'GET /')
    line(2, 'db', 'ready')
    const rows = screen.getByRole('log').children
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('webGET /')
    expect(rows[1]).toHaveTextContent('dbready')
    const web = within(rows[0] as HTMLElement).getByText('web')
    const db = within(rows[1] as HTMLElement).getByText('db')
    expect(web.className).not.toEqual(db.className)
    expect(web.className).toMatch(/logv__svc--\d/)
  })

  it('toggles a service off and on through the legend', () => {
    open()
    line(1, 'web', 'GET /')
    line(2, 'db', 'ready')
    const web = screen.getByRole('button', { name: 'web' })
    expect(web).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(web)
    expect(web).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByRole('log')).not.toHaveTextContent('GET /')
    expect(screen.getByRole('log')).toHaveTextContent('ready')
    fireEvent.click(web)
    expect(screen.getByRole('log')).toHaveTextContent('GET /')
  })

  it('shows project-level status banners and keeps the stream open', () => {
    open()
    send({ seq: 1, type: 'STATUS', status: 'ERROR', line: 'CONTAINER_LIMIT' })
    expect(screen.getByRole('status', { name: 'Log notices' })).toHaveTextContent('too many containers')
    send({ seq: 2, type: 'STATUS', status: 'ERROR', line: 'UNAVAILABLE' })
    expect(screen.getByRole('status', { name: 'Log notices' })).toHaveTextContent('not reachable')
    expect(unsubscribe).not.toHaveBeenCalled()
  })

  it('marks a service whose container ended', () => {
    open()
    line(1, 'web', 'x')
    send({ seq: 2, type: 'STATUS', status: 'ENDED', service: 'web', container: 'shop-web-1', containerId: 'c1' })
    expect(screen.getByRole('button', { name: /^web/ })).toHaveTextContent('ended')
  })

  it('filters by search text with highlighted matches', () => {
    vi.useFakeTimers()
    try {
      open()
      line(1, 'web', 'Hello World')
      line(2, 'db', 'nothing')
      fireEvent.change(screen.getByRole('searchbox', { name: 'Search logs' }), { target: { value: 'WORLD' } })
      act(() => void vi.advanceTimersByTime(500))
      expect(screen.getByRole('log').querySelectorAll('mark')).toHaveLength(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('downloads one container chosen in the select with the current options', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('x', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    URL.createObjectURL = vi.fn(() => 'blob:x')
    URL.revokeObjectURL = vi.fn()
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    open()
    fireEvent.change(screen.getByLabelText('Download container'), { target: { value: 'c2' } })
    fireEvent.change(screen.getByLabelText('Show last'), { target: { value: 'all' } })
    fireEvent.click(screen.getByLabelText('Timestamps'))
    expect(screen.getByText(/capped/i)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Download/ }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/hosts/h1/containers/c2/logs/download?tail=all&timestamps=true', expect.anything()))
  })

  it('offers only the containers of this project for download', () => {
    open()
    const options = within(screen.getByLabelText('Download container')).getAllByRole('option').map((o) => o.textContent)
    expect(options).toEqual(['web (shop-web-1)', 'db (shop-db-1)'])
  })

  it('explains a refused subscription', () => {
    open()
    act(() => handlers.onError?.('Access denied'))
    expect(screen.getByRole('alert')).toHaveTextContent(/not allowed|permission/i)
  })

  it('keeps a bounded buffer and says so', () => {
    open()
    act(() => {
      for (let i = 1; i <= 5003; i++) {
        handlers.onMessage(JSON.stringify({ seq: i, type: 'LINE', service: 'web', container: 'shop-web-1', containerId: 'c1', stream: 'STDOUT', line: `l${i}` }))
      }
    })
    expect(screen.getByRole('log').children).toHaveLength(5000)
    expect(screen.getByText(/oldest lines were dropped/i)).toBeInTheDocument()
  })
})
