import { useEffect, useState } from 'react'
import { subscribeToTopic } from './stomp.ts'

/** Samples kept per container (about two minutes at one frame per two seconds). */
export const MAX_SAMPLES = 60

/** One reading of a running container; net/blk are cumulative byte counters, cpu is a percentage. */
export interface StatSample {
  cpu: number
  memUsed: number
  memLimit: number
  netRx: number
  netTx: number
  blkRead: number
  blkWrite: number
}

interface StatsFrame {
  seq?: number
  samples?: (StatSample & { containerId: string })[]
  code?: string | null
}

export interface ContainerStats {
  /** Last {@link MAX_SAMPLES} samples per container id; absent while a container is not running. */
  series: Record<string, StatSample[]>
  /** Error code of the last frame (e.g. UNREACHABLE), null while healthy. */
  code: string | null
}

const EMPTY: ContainerStats = { series: {}, code: null }

/**
 * Live stats of all visible running containers of one host: subscribes to `/topic/hosts/{hostId}/stats`,
 * keeps a bounded history per container client-side and drops the history of containers missing from a
 * frame (stopped). Unsubscribes on unmount.
 */
export function useContainerStats(hostId: string | undefined): ContainerStats {
  const [stats, setStats] = useState<ContainerStats>(EMPTY)

  useEffect(() => {
    if (!hostId) return
    const unsubscribe = subscribeToTopic(`/topic/hosts/${hostId}/stats`, {
      onState: () => {},
      onMessage: (body) => {
        let frame: StatsFrame
        try {
          frame = JSON.parse(body) as StatsFrame
        } catch {
          return
        }
        const samples = frame.samples ?? []
        setStats((prev) => {
          const series: Record<string, StatSample[]> = {}
          for (const { containerId, ...sample } of samples) {
            series[containerId] = [...(prev.series[containerId] ?? []), sample].slice(-MAX_SAMPLES)
          }
          return { series, code: frame.code ?? null }
        })
      },
    })
    return () => {
      unsubscribe()
      setStats(EMPTY)
    }
  }, [hostId])

  return stats
}
