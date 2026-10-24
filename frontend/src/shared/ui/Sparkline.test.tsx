import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { Sparkline } from './Sparkline.tsx'

afterEach(cleanup)

describe('Sparkline', () => {
  it('draws one point per value, scaled to the max', () => {
    render(<Sparkline values={[0, 50, 100]} max={100} width={100} height={22} label="CPU history" />)
    const points = screen.getByRole('img', { name: 'CPU history' }).querySelector('polyline')?.getAttribute('points')
    expect(points).toBe('0.0,20.0 50.0,11.0 100.0,2.0')
  })

  it('draws no line for a single value', () => {
    render(<Sparkline values={[5]} label="x" />)
    expect(screen.getByRole('img').querySelector('polyline')).toBeNull()
  })
})
