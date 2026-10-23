import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '../../shared/api/client.ts'

export type StackStatus = 'RUNNING' | 'PARTIAL' | 'STOPPED'

/** One Compose project of `GET /api/hosts/{id}/stacks`. */
export interface StackSummary {
  name: string
  containerCount: number
  runningCount: number
  status: StackStatus
  workingDir: string | null
}

export function useStacks(hostId: string) {
  return useQuery({
    queryKey: ['hosts', hostId, 'stacks'],
    queryFn: () => apiFetch<StackSummary[]>(`/hosts/${hostId}/stacks`),
  })
}
