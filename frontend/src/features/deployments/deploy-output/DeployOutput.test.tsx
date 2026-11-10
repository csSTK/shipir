import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TopicHandlers } from '../../../shared/realtime/stomp.ts'

const subscribe = vi.fn()
vi.mock('../../../shared/realtime/stomp.ts', () => ({ subscribeToTopic: (...a: unknown[]) => subscribe(...a) }))

import { DeployOutput } from './DeployOutput.tsx'

let handlers: TopicHandlers
const unsubscribe = vi.fn()
const send = (frame: object) => act(() => handlers.onMessage(JSON.stringify(frame)))

afterEach(cleanup)
beforeEach(() => {
  subscribe.mockReset()
  unsubscribe.mockReset()
  subscribe.mockImplementation((_d: string, h: TopicHandlers) => {
    handlers = h
    return unsubscribe
  })
})

describe('DeployOutput', () => {
  it('subscribes to the deployment topic and appends lines in order', () => {
    render(<DeployOutput title="Deployment" runId="dep-1" />)
    expect(subscribe).toHaveBeenCalledWith('/topic/deployments/dep-1', expect.anything())
    send({ seq: 1, type: 'LINE', line: 'Pulling web' })
    send({ seq: 2, type: 'LINE', line: 'Started web' })
    const rows = screen.getByRole('log').children
    expect(rows[0]).toHaveTextContent('Pulling web')
    expect(rows[1]).toHaveTextContent('Started web')
  })

  it('ignores replayed frames after a reconnect', () => {
    render(<DeployOutput title="Deployment" runId="dep-1" />)
    send({ seq: 1, type: 'LINE', line: 'one' })
    send({ seq: 2, type: 'LINE', line: 'two' })
    act(() => handlers.onState('reconnecting'))
    expect(screen.getByRole('status')).toHaveTextContent('reconnecting')
    act(() => handlers.onState('connected'))
    send({ seq: 1, type: 'LINE', line: 'one' })
    send({ seq: 2, type: 'LINE', line: 'two' })
    send({ seq: 3, type: 'LINE', line: 'three' })
    expect(screen.getByRole('log').children).toHaveLength(3)
  })

  it.each([
    ['DONE', 'Succeeded'],
    ['FAILED', 'Failed'],
  ])('shows the final %s status, notifies and stops listening', (status, label) => {
    const onFinished = vi.fn()
    render(<DeployOutput title="Deployment" runId="dep-1" onFinished={onFinished} />)
    send({ seq: 1, type: 'STATUS', status })
    expect(screen.getByRole('status')).toHaveTextContent(label)
    expect(onFinished).toHaveBeenCalledWith(status)
    expect(unsubscribe).toHaveBeenCalled()
  })

  it('unsubscribes on unmount', () => {
    const { unmount } = render(<DeployOutput title="Deployment" runId="dep-1" />)
    unmount()
    expect(unsubscribe).toHaveBeenCalled()
  })

  it('pauses autoscroll when scrolled up and offers Jump to latest', () => {
    render(<DeployOutput title="Deployment" runId="dep-1" />)
    const log = screen.getByRole('log')
    Object.defineProperty(log, 'scrollHeight', { configurable: true, value: 1000 })
    Object.defineProperty(log, 'clientHeight', { configurable: true, value: 200 })
    expect(screen.queryByRole('button', { name: 'Jump to latest' })).toBeNull()
    log.scrollTop = 100
    fireEvent.scroll(log)
    const jump = screen.getByRole('button', { name: 'Jump to latest' })
    send({ seq: 1, type: 'LINE', line: 'new' })
    expect(log.scrollTop).toBe(100) // not yanked down while reading
    fireEvent.click(jump)
    expect(log.scrollTop).toBe(1000)
    expect(screen.queryByRole('button', { name: 'Jump to latest' })).toBeNull()
  })

  it('resets lines and result when the runId changes', () => {
    const { rerender } = render(<DeployOutput title="Deployment" runId="dep-1" />)
    send({ seq: 1, type: 'LINE', line: 'run one' })
    send({ seq: 2, type: 'STATUS', status: 'DONE' })
    expect(screen.getByRole('status')).toHaveTextContent('Succeeded')
    rerender(<DeployOutput title="Deployment" runId="dep-2" />)
    expect(subscribe).toHaveBeenLastCalledWith('/topic/deployments/dep-2', expect.anything())
    expect(screen.queryByText('run one')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).not.toHaveTextContent('Succeeded')
    send({ seq: 1, type: 'STATUS', status: 'FAILED' })
    expect(screen.getByRole('status')).toHaveTextContent('Failed')
  })
})
