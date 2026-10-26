import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { HOSTS_OVERVIEW_KEY } from '../hosts/api.ts'
import { describeResourceError } from '../hosts/format.ts'
import { useDeleteResourceMutation } from '../hosts/deleteResource.ts'
import { describeCreateError, useCreateResourceMutation, type CreatedResource } from '../hosts/createResource.ts'
import i18n from 'i18next'

/** One container of `GET /api/hosts/{id}/containers`; compose fields are null outside Compose. */
export interface Container {
  id: string
  name: string
  image: string
  state: string
  status: string
  /** healthy, unhealthy, starting or null without healthcheck. */
  health: string | null
  created: string
  ports: string[]
  composeProject: string | null
  composeService: string | null
}

export function useContainers(hostId: string) {
  return useQuery({
    queryKey: ['hosts', hostId, 'containers'],
    queryFn: () => apiFetch<Container[]>(`/hosts/${hostId}/containers`),
  })
}

/** `force` stops a running container before removing it. */
export function useDeleteContainer(hostId: string) {
  return useDeleteResourceMutation(hostId, 'containers', ({ id, force }: { id: string; force: boolean }) => ({ ref: id, force }))
}

export type ContainerAction = 'start' | 'stop' | 'restart' | 'pause' | 'unpause' | 'kill'

/** Actions offered for a container state: stopped can start, running can stop/restart/pause/kill, paused can unpause. */
export function actionsFor(state: string): ContainerAction[] {
  if (state === 'running') return ['stop', 'restart', 'pause', 'kill']
  if (state === 'paused') return ['unpause']
  if (state === 'exited' || state === 'created') return ['start']
  return []
}

/** POST `/hosts/{id}/containers/{containerId}/{action}`; refreshes containers, deployments and overview counts (also on failure, the state may have changed). */
export function useContainerAction(hostId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, action }: { id: string; action: ContainerAction }) =>
      apiFetch<void>(`/hosts/${hostId}/containers/${encodeURIComponent(id)}/${action}`, { method: 'POST' }),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['hosts', hostId] }),
        queryClient.invalidateQueries({ queryKey: HOSTS_OVERVIEW_KEY }),
      ]),
  })
}

export function describeActionError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'INVALID_CONTAINER_STATE':
        return i18n.t('containers:api.theContainerIsNotIn')
      case 'RESOURCE_NOT_FOUND':
        return i18n.t('containers:api.thisContainerNoLongerExists')
      case 'INVALID_RESOURCE_ID':
        return i18n.t('containers:api.theContainerIdentifierIsInvalid')
    }
    if (error.status === 403) return i18n.t('containers:api.youAreNotAllowedTo')
    if (error.status === 502 || error.code === 'HOST_NOT_FOUND') return describeResourceError(error)
  }
  return i18n.t('containers:api.theActionFailedTryAgain')
}

export interface PortSpec {
  hostPort: number | null
  containerPort: number
  protocol: 'tcp' | 'udp'
}

export interface MountSpec {
  type: 'VOLUME' | 'BIND'
  source: string
  target: string
  readOnly: boolean
}

export type RestartPolicy = 'no' | 'always' | 'unless-stopped' | 'on-failure'

export interface ContainerSpec {
  name?: string
  image: string
  command?: string[]
  env?: Record<string, string>
  ports?: PortSpec[]
  mounts?: MountSpec[]
  network?: string
  restartPolicy?: RestartPolicy
  privileged?: boolean
  hostNetwork?: boolean
  start?: boolean
}

export function useCreateContainer(hostId: string) {
  return useCreateResourceMutation<ContainerSpec>(hostId, 'containers')
}

/** POST `/hosts/{h}/containers/{cid}/{recreate|duplicate}` with the edited spec; refreshes all host lists (a recreate changes the container id). */
export function useContainerSpecMutation(hostId: string, containerId: string, operation: 'recreate' | 'duplicate') {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (spec: ContainerSpec) =>
      apiFetch<CreatedResource>(`/hosts/${hostId}/containers/${encodeURIComponent(containerId)}/${operation}`, { method: 'POST', body: spec }),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['hosts', hostId] }),
        queryClient.invalidateQueries({ queryKey: HOSTS_OVERVIEW_KEY }),
      ]),
  })
}

/** Server error of recreate/duplicate to inline text. */
export function describeEditError(error: unknown, operation: 'recreate' | 'duplicate'): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'CONTAINER_RECREATE_FAILED':
        return i18n.t('containers:api.theContainerCouldNotBe')
      case 'MANAGED_BY_COMPOSE':
        return i18n.t('containers:api.thisContainerIsManagedBy')
      case 'RESOURCE_NOT_FOUND':
        return i18n.t('containers:api.theContainerImageOrNetwork')
    }
  }
  return describeCreateError(error, 'container', 'INVALID_CONTAINER_SPEC', operation)
}

/** Fields of `PATCH /hosts/{h}/containers/{cid}/resources`: null = unchanged, 0 = unlimited. */
export interface ResourcesSpec {
  memoryBytes: number | null
  cpus: number | null
  restartPolicy: RestartPolicy | null
}

export function useUpdateContainerResources(hostId: string, containerId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (spec: ResourcesSpec) =>
      apiFetch<void>(`/hosts/${hostId}/containers/${encodeURIComponent(containerId)}/resources`, { method: 'PATCH', body: spec }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['hosts', hostId] }),
  })
}

export function describeResourcesError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'INVALID_RESOURCES':
        return [i18n.t('containers:api.theLimitsAreInvalid'), ...(error.details ?? [])].join(' ')
      case 'RESOURCES_REJECTED':
        return i18n.t('containers:api.dockerRejectedTheseLimitsFor')
      case 'MANAGED_BY_COMPOSE':
        return i18n.t('containers:api.thisContainerIsManagedBy')
    }
    if (error.status === 403) return i18n.t('containers:api.youAreNotAllowedTo')
  }
  return i18n.t('containers:api.theLimitsCouldNotBe')
}

export const CONTAINER_NAME_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$/

export function validatePort(value: string, optional: boolean): string | null {
  if (!value.trim()) return optional ? null : i18n.t('containers:api.required')
  const n = Number(value)
  return Number.isInteger(n) && n >= 1 && n <= 65535 ? null : i18n.t('containers:api.use165535')
}

/**
 * Splits a command line into arguments. Whitespace separates; `"..."` groups with `\"` and `\\` escapes (any other
 * backslash stays literal); `'...'` groups literally; a quoted empty string is an empty argument. Exact inverse of
 * {@link joinCommand}.
 */
export function splitCommand(text: string): string[] {
  const out: string[] = []
  let current = ''
  let started = false
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]
    if (/\s/.test(ch)) {
      if (started) out.push(current)
      current = ''
      started = false
    } else if (ch === '"') {
      started = true
      for (i++; i < text.length && text[i] !== '"'; i++) {
        if (text[i] === '\\' && (text[i + 1] === '"' || text[i + 1] === '\\')) i++
        current += text[i]
      }
    } else if (ch === "'") {
      started = true
      for (i++; i < text.length && text[i] !== "'"; i++) current += text[i]
    } else {
      started = true
      current += ch
    }
  }
  if (started) out.push(current)
  return out
}

/** Writes arguments so that {@link splitCommand} returns them unchanged: anything but plain words is double-quoted with `\"` and `\\` escaped. */
export function joinCommand(args: string[]): string {
  return args.map((a) => (/^[^\s"'\\]+$/.test(a) ? a : `"${a.replace(/[\\"]/g, '\\$&')}"`)).join(' ')
}
