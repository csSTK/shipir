import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TopicHandlers } from '../../../shared/realtime/stomp.ts'

const subscribe = vi.fn()
vi.mock('../../../shared/realtime/stomp.ts', () => ({ subscribeToTopic: (...a: unknown[]) => subscribe(...a) }))
vi.mock('../api.ts', () => ({ useContainers: () => ({ data: [{ id: 'c1', name: 'web' }] }) }))

import { ContainerLogsPage } from './ContainerLogsPage.tsx'

let handlers: TopicHandlers
const unsubscribe = vi.fn()
const send = (frame: object) => act(() => handlers.onMessage(JSON.stringify(frame)))

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={['/environments/h1/containers/c1/logs']}>
        <Routes>
          <Route path="/environments/:id/containers/:cid/logs" element={<ContainerLogsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(cleanup)
beforeEach(() => {
  subscribe.mockReset()
  unsubscribe.mockReset()
  subscribe.mockImplementation((_d: string, h: TopicHandlers) => {
    handlers = h
    return unsubscribe
  })
})

describe('ContainerLogsPage', () => {
  it('subscribes with default options and streams lines, stderr tinted, deduplicated by seq', () => {
    renderPage()
    expect(subscribe).toHaveBeenCalledWith('/topic/hosts/h1/containers/c1/logs', expect.anything(), {
      headers: { tail: '200', timestamps: 'false' },
    })
    expect(screen.getByText('Logs: web')).toBeInTheDocument()
    send({ seq: 1, type: 'LINE', stream: 'STDOUT', line: 'hello' })
    send({ seq: 2, type: 'LINE', stream: 'STDERR', line: 'boom' })
    send({ seq: 2, type: 'LINE', stream: 'STDERR', line: 'boom' })
    const rows = screen.getByRole('log').children
    expect(rows).toHaveLength(2)
    expect(rows[1]).toHaveClass('clogs__line--err')
    expect(rows[0]).not.toHaveClass('clogs__line--err')
  })

  it('resubscribes with new options and clears the lines', () => {
    renderPage()
    send({ seq: 1, type: 'LINE', stream: 'STDOUT', line: 'old' })
    fireEvent.change(screen.getByLabelText('Show last'), { target: { value: 'all' } })
    expect(unsubscribe).toHaveBeenCalledTimes(1)
    expect(subscribe).toHaveBeenLastCalledWith(expect.any(String), expect.anything(), { headers: { tail: 'all', timestamps: 'false' } })
    expect(screen.queryByText('old')).toBeNull()
    fireEvent.click(screen.getByLabelText('Timestamps'))
    expect(subscribe).toHaveBeenLastCalledWith(expect.any(String), expect.anything(), { headers: { tail: 'all', timestamps: 'true' } })
    send({ seq: 1, type: 'LINE', stream: 'STDOUT', line: 'new', timestamp: '2026-10-23T10:00:00Z' })
    expect(screen.getByRole('log')).toHaveTextContent('2026-10-23T10:00:00Znew')
  })

  it('shows the ended state and stops listening', () => {
    renderPage()
    send({ seq: 1, type: 'STATUS', status: 'ENDED' })
    expect(screen.getByRole('status')).toHaveTextContent('Ended')
    expect(unsubscribe).toHaveBeenCalled()
  })

  it('shows an error code from the server', () => {
    renderPage()
    send({ seq: 1, type: 'STATUS', status: 'ERROR', line: 'NOT_FOUND' })
    expect(screen.getByRole('alert')).toHaveTextContent('no longer exists')
  })

  it('pauses autoscroll when scrolled up and offers Jump to latest', () => {
    renderPage()
    const log = screen.getByRole('log')
    Object.defineProperty(log, 'scrollHeight', { configurable: true, value: 1000 })
    Object.defineProperty(log, 'clientHeight', { configurable: true, value: 200 })
    log.scrollTop = 100
    fireEvent.scroll(log)
    const jump = screen.getByRole('button', { name: 'Jump to latest' })
    send({ seq: 1, type: 'LINE', stream: 'STDOUT', line: 'x' })
    expect(log.scrollTop).toBe(100)
    fireEvent.click(jump)
    expect(log.scrollTop).toBe(1000)
    expect(screen.queryByRole('button', { name: 'Jump to latest' })).toBeNull()
  })

  it('unsubscribes on unmount', () => {
    const { unmount } = renderPage()
    unmount()
    expect(unsubscribe).toHaveBeenCalled()
  })
})
