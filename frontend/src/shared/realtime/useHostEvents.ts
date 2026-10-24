import { useEffect } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import { subscribeToTopic } from './stomp.ts'

/** Overview lives in features/hosts/api.ts; duplicated key avoids a shared -> features import. */
const OVERVIEW_KEY = ['hosts', 'overview'] as const

/** Burst of docker events (e.g. a compose up) collapses into one refetch per list. */
export const EVENT_THROTTLE_MS = 400

type ResourceType = 'CONTAINER' | 'IMAGE' | 'NETWORK' | 'VOLUME'

interface HostEvent {
  type: ResourceType
}

interface HostEventFrame {
  seq?: number
  type: 'EVENT' | 'STATUS'
  events?: HostEvent[]
  omitted?: number
  status?: 'CONNECTED' | 'DISCONNECTED'
  code?: string
}

const LISTS: Record<ResourceType, string[]> = {
  CONTAINER: ['containers', 'deployments'],
  IMAGE: ['images'],
  NETWORK: ['networks'],
  VOLUME: ['volumes'],
}

/** Number of hosts with a live event stream; pollers read it to back off. */
let liveCount = 0
function setLive(delta: number) {
  liveCount += delta
}
export function isEventStreamLive(): boolean {
  return liveCount > 0
}
function invalidate(queryClient: QueryClient, hostId: string, all: boolean, lists: Set<string>) {
  if (all) {
    void queryClient.invalidateQueries({ queryKey: ['hosts', hostId] })
  } else {
    lists.forEach((list) => void queryClient.invalidateQueries({ queryKey: ['hosts', hostId, list] }))
  }
  void queryClient.invalidateQueries({ queryKey: OVERVIEW_KEY })
}

/**
 * Live updates for one environment: subscribes to `/topic/hosts/{hostId}/events` and invalidates only the
 * React Query lists the events touch (throttled). Everything is invalidated on a (re)connect, because
 * events in between were missed, and when the server dropped events (`omitted`). Unsubscribes on unmount.
 */
export function useHostEvents(hostId: string | undefined): void {
  const queryClient = useQueryClient()

  useEffect(() => {
    if (!hostId) return
    let live = false
    let timer: ReturnType<typeof setTimeout> | undefined
    let pendingAll = false
    const pendingLists = new Set<string>()

    const flush = () => {
      timer = undefined
      invalidate(queryClient, hostId, pendingAll, pendingLists)
      pendingAll = false
      pendingLists.clear()
    }
    const schedule = () => {
      timer ??= setTimeout(flush, EVENT_THROTTLE_MS)
    }
    const markLive = (value: boolean) => {
      if (value === live) return
      live = value
      setLive(value ? 1 : -1)
    }

    const unsubscribe = subscribeToTopic(`/topic/hosts/${hostId}/events`, {
      onState: (state) => {
        if (state !== 'connected') markLive(false)
      },
      onMessage: (body) => {
        let frame: HostEventFrame
        try {
          frame = JSON.parse(body) as HostEventFrame
        } catch {
          return
        }
        if (frame.type === 'STATUS') {
          if (frame.status === 'CONNECTED') {
            markLive(true)
            // First connect or reconnect: the UI may be stale, refetch now rather than throttled.
            pendingAll = true
            schedule()
          } else if (frame.status === 'DISCONNECTED') {
            markLive(false)
          }
          return
        }
        if (frame.type === 'EVENT') {
          if ((frame.omitted ?? 0) > 0) pendingAll = true
          for (const event of frame.events ?? []) LISTS[event.type]?.forEach((list) => pendingLists.add(list))
          schedule()
        }
      },
    })

    return () => {
      unsubscribe()
      if (timer) clearTimeout(timer)
      markLive(false)
    }
  }, [hostId, queryClient])
}
