import { renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const reduced = vi.hoisted(() => ({ value: false }))
vi.mock('motion/react', () => ({ useReducedMotion: () => reduced.value }))

import { springSmooth, useSheetMotion } from './motion.ts'

describe('useSheetMotion', () => {
  beforeEach(() => {
    reduced.value = false
  })

  it('pops from the corner with a spring on wide screens', () => {
    const { result } = renderHook(() => useSheetMotion(false, springSmooth))
    expect(result.current.initial).toMatchObject({ opacity: 0, scale: expect.any(Number), y: expect.any(Number) })
    expect(result.current.animate).toEqual({ opacity: 1, scale: 1, y: 0 })
    expect(result.current.exit).toEqual(result.current.initial)
    expect(result.current.transition).toBe(springSmooth)
  })

  it('slides in from the bottom edge as a sheet on narrow screens', () => {
    const { result } = renderHook(() => useSheetMotion(true, springSmooth))
    expect(result.current.initial).toEqual({ opacity: 0, y: '100%' })
    expect(result.current.animate).toEqual({ opacity: 1, y: 0 })
    expect(result.current.transition).toBe(springSmooth)
  })

  it('only cross-fades under reduced motion, in both layouts', () => {
    reduced.value = true
    for (const narrow of [false, true]) {
      const { result } = renderHook(() => useSheetMotion(narrow, springSmooth))
      expect(result.current.initial).toEqual({ opacity: 0 })
      expect(result.current.animate).toEqual({ opacity: 1 })
      expect(result.current.transition).toMatchObject({ type: 'tween' })
    }
  })
})
