import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LogLine } from './logStream.ts'

const findMatchesSpy = vi.hoisted(() => vi.fn())
vi.mock('./logSearch.ts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./logSearch.ts')>()
  findMatchesSpy.mockImplementation(actual.findMatches)
  return {
    ...actual,
    MatchCache: class extends actual.MatchCache {
      constructor() {
        super(findMatchesSpy)
      }
    },
  }
})

const rowRenders = vi.hoisted(() => ({ count: 0 }))
vi.mock('./Highlighted.tsx', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./Highlighted.tsx')>()
  return {
    Highlighted: (props: Parameters<typeof actual.Highlighted>[0]) => {
      rowRenders.count++
      return actual.Highlighted(props)
    },
  }
})

import { LogView } from './LogView.tsx'

const mk = (n: number, from = 0): LogLine[] =>
  Array.from({ length: n }, (_, i) => ({ seq: from + i, stream: 'STDOUT' as const, text: `entry ${from + i} ${(from + i) % 100 === 0 ? 'needle' : ''}` }))

beforeEach(() => {
  vi.useFakeTimers()
  findMatchesSpy.mockClear()
  rowRenders.count = 0
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

function view(lines: LogLine[]) {
  const props = { label: 'log', emptyText: 'none' }
  const r = render(<LogView lines={lines} {...props} />)
  return (next: LogLine[]) => r.rerender(<LogView lines={next} {...props} />)
}
const search = (q: string) => {
  fireEvent.change(screen.getByLabelText('Search logs'), { target: { value: q } })
  act(() => void vi.advanceTimersByTime(300))
}

describe('LogView with a full 5000-line buffer', () => {
  it('searches only the new line when a line arrives with a search active', () => {
    const lines = mk(5000)
    const rerender = view(lines)
    search('needle')
    expect(findMatchesSpy).toHaveBeenCalledTimes(5000)
    expect(screen.getByText('1 of 50')).toBeInTheDocument()

    findMatchesSpy.mockClear()
    rerender([...lines.slice(1), ...mk(1, 5001)]) // buffer stays at 5000: one in, one out
    expect(findMatchesSpy).toHaveBeenCalledTimes(1)
    expect(screen.getByText('1 of 49')).toBeInTheDocument() // seq 0 dropped out
  })

  it('re-renders only the rows whose match is active when stepping through matches', () => {
    view(mk(5000))
    search('needle')
    expect(document.querySelectorAll('mark[data-active="true"]')).toHaveLength(1)
    findMatchesSpy.mockClear()
    rowRenders.count = 0
    fireEvent.click(screen.getByLabelText('Next match'))
    expect(findMatchesSpy).not.toHaveBeenCalled()
    expect(rowRenders.count).toBeLessThanOrEqual(2) // the previously and the newly active row
    expect(screen.getByText('2 of 50')).toBeInTheDocument()
    const active = document.querySelector('mark[data-active="true"]')
    expect(active?.closest('.logv__line')).toHaveTextContent('entry 100')
  })

  it('renders only the new row when a line arrives', () => {
    const lines = mk(5000)
    const rerender = view(lines)
    search('needle')
    rowRenders.count = 0
    rerender([...lines, ...mk(1, 5001)])
    expect(rowRenders.count).toBeLessThanOrEqual(1)
    expect(screen.getByRole('log').children).toHaveLength(5001)
  })

  it('searches the whole buffer again for a new query', () => {
    view(mk(5000))
    search('needle')
    findMatchesSpy.mockClear()
    search('entry 4')
    expect(findMatchesSpy).toHaveBeenCalledTimes(5000)
  })
})
