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
    expect(rows[1]).toHaveClass('logv__line--err')
    expect(rows[0]).not.toHaveClass('logv__line--err')
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

  it('highlights matches case-insensitively, counts them and steps with Enter and Shift+Enter', () => {
    vi.useFakeTimers()
    try {
      renderPage()
      send({ seq: 1, type: 'LINE', stream: 'STDOUT', line: 'Error one' })
      send({ seq: 2, type: 'LINE', stream: 'STDOUT', line: 'fine' })
      send({ seq: 3, type: 'LINE', stream: 'STDOUT', line: 'an error and another ERROR' })
      const box = screen.getByRole('searchbox', { name: 'Search logs' })
      fireEvent.change(box, { target: { value: 'error' } })
      act(() => void vi.advanceTimersByTime(500))
      const marks = () => Array.from(screen.getByRole('log').querySelectorAll('mark'))
      expect(marks()).toHaveLength(3)
      expect(screen.getByText('1 of 3')).toBeInTheDocument()
      expect(marks()[0]).toHaveAttribute('data-active', 'true')
      fireEvent.keyDown(box, { key: 'Enter' })
      expect(screen.getByText('2 of 3')).toBeInTheDocument()
      expect(marks()[1]).toHaveAttribute('data-active', 'true')
      fireEvent.keyDown(box, { key: 'Enter', shiftKey: true })
      fireEvent.keyDown(box, { key: 'Enter', shiftKey: true })
      expect(screen.getByText('3 of 3')).toBeInTheDocument()
      fireEvent.change(box, { target: { value: 'zzz' } })
      act(() => void vi.advanceTimersByTime(500))
      expect(screen.getByText('No matches')).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('filters to stderr only and offers to clear the filter', () => {
    renderPage()
    send({ seq: 1, type: 'LINE', stream: 'STDOUT', line: 'out' })
    send({ seq: 2, type: 'LINE', stream: 'STDERR', line: 'err' })
    fireEvent.click(screen.getByLabelText('stderr only'))
    expect(screen.getByRole('log')).not.toHaveTextContent('out')
    expect(screen.getByRole('log')).toHaveTextContent('err')
    expect(screen.getByText('Showing 1 of 2 lines')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(screen.getByRole('log')).toHaveTextContent('out')
    expect(screen.queryByRole('button', { name: 'Clear filters' })).toBeNull()
  })

  it('links the download to the endpoint with the current tail and timestamps and hints at the cap', () => {
    renderPage()
    expect(screen.getByRole('link', { name: /Download/ })).toHaveAttribute('href', '/api/hosts/h1/containers/c1/logs/download?tail=200&timestamps=false')
    fireEvent.change(screen.getByLabelText('Show last'), { target: { value: 'all' } })
    fireEvent.click(screen.getByLabelText('Timestamps'))
    expect(screen.getByRole('link', { name: /Download/ })).toHaveAttribute('href', '/api/hosts/h1/containers/c1/logs/download?tail=all&timestamps=true')
    expect(screen.getByText(/capped/i)).toBeInTheDocument()
  })

  it('says when the oldest lines were dropped from the buffer', () => {
    renderPage()
    act(() => {
      for (let i = 1; i <= 5002; i++) handlers.onMessage(JSON.stringify({ seq: i, type: 'LINE', stream: 'STDOUT', line: `l${i}` }))
    })
    expect(screen.getByText(/oldest lines were dropped/i)).toBeInTheDocument()
  })

  it('explains a refused subscription instead of reconnecting forever', () => {
    renderPage()
    act(() => handlers.onError?.('Access denied'))
    expect(screen.getByRole('alert')).toHaveTextContent(/not allowed|permission/i)
    expect(unsubscribe).toHaveBeenCalled()
  })

  it('unsubscribes on unmount', () => {
    const { unmount } = renderPage()
    unmount()
    expect(unsubscribe).toHaveBeenCalled()
  })
})
