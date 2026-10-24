import { useReducedMotion } from 'motion/react'

/** Spring presets from the Shipir visual language (apple-design skill). */
export const springSnappy = { type: 'spring', stiffness: 520, damping: 38, mass: 1 } as const
export const springSmooth = { type: 'spring', stiffness: 300, damping: 30, mass: 1 } as const
export const springGentle = { type: 'spring', stiffness: 180, damping: 24, mass: 1 } as const

const fade = { type: 'tween', duration: 0.12, ease: 'linear' } as const

/**
 * Enter/exit animation props: a spring scale+fade, or a plain cross-fade under prefers-reduced-motion.
 * Springs keep velocity when interrupted (motion retargets from the current value).
 */
export function usePopMotion(spring: typeof springSnappy | typeof springSmooth, fromScale = 0.94) {
  const reduced = useReducedMotion()
  if (reduced) {
    return {
      initial: { opacity: 0 },
      animate: { opacity: 1 },
      exit: { opacity: 0 },
      transition: fade,
    }
  }
  return {
    initial: { opacity: 0, scale: fromScale },
    animate: { opacity: 1, scale: 1 },
    exit: { opacity: 0, scale: fromScale },
    transition: spring,
  }
}

export function useFadeMotion() {
  return { initial: { opacity: 0 }, animate: { opacity: 1 }, exit: { opacity: 0 }, transition: fade }
}
