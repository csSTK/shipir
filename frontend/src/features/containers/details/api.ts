import { useQuery } from '@tanstack/react-query'
import { ApiError, apiFetch } from '../../../shared/api/client.ts'
import { describeResourceError } from '../../hosts/format.ts'

export interface ContainerMount {
  type: string
  name: string | null
  source: string | null
  destination: string
  readOnly: boolean
}

export interface ContainerNetwork {
  name: string
  ipAddress: string | null
  gateway: string | null
  macAddress: string | null
  aliases: string[]
}

export interface ContainerPort {
  containerPort: number
  protocol: string
  hostIp: string | null
  hostPort: number | null
}

export interface HealthLogEntry {
  start: string
  end: string
  exitCode: number
  output: string
}

export interface ContainerHealth {
  status: string
  failingStreak: number
  recentLogs: HealthLogEntry[]
}

/** `GET /api/hosts/{h}/containers/{cid}`; env values are null for VIEWER. */
export interface ContainerDetails {
  id: string
  name: string
  image: string
  state: string
  created: string
  startedAt: string | null
  restartCount: number
  restartPolicy: { name: string; maxRetries: number }
  command: string[]
  env: { key: string; value: string | null }[]
  mounts: ContainerMount[]
  labels: Record<string, string>
  networks: ContainerNetwork[]
  ports: ContainerPort[]
  health: ContainerHealth | null
  composeProject: string | null
  composeService: string | null
}

export function useContainerDetails(hostId: string, containerId: string) {
  return useQuery({
    queryKey: ['hosts', hostId, 'containers', containerId],
    queryFn: () => apiFetch<ContainerDetails>(`/hosts/${hostId}/containers/${encodeURIComponent(containerId)}`),
  })
}

export function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404 && error.code === 'RESOURCE_NOT_FOUND'
}

export function describeDetailsError(error: unknown): string {
  return describeResourceError(error)
}
