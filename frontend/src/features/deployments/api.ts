import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../shared/api/client.ts'
import type { ComposeSpecPayload, ProxyType } from './wizard/model.ts'

export type DeploymentStatus = 'RUNNING' | 'PARTIAL' | 'STOPPED'

/** One Compose project of `GET /api/hosts/{id}/deployments`. */
export interface DeploymentSummary {
  name: string
  containerCount: number
  runningCount: number
  status: DeploymentStatus
  workingDir: string | null
  /** True when Shipir manages this project; optional for older servers. */
  managed?: boolean
  managedDeploymentId?: string | null
  /** A newer image exists in a registry for one of the project's images. */
  updateAvailable?: boolean
  /** The managed proxy deployment: ADMIN only, shown as managed via Domains. Optional for older servers. */
  systemManaged?: boolean
}

export function useDeployments(hostId: string) {
  return useQuery({
    queryKey: ['hosts', hostId, 'deployments'],
    queryFn: () => apiFetch<DeploymentSummary[]>(`/hosts/${hostId}/deployments`),
  })
}

export type ManagedDeploymentStatus = 'STOPPED' | 'RUNNING' | 'ERROR'
export type DeploymentSource = 'FILE' | 'GIT'
export type VersionSource = 'MANUAL' | 'GIT' | 'ROLLBACK'

/** `GET /api/hosts/{id}/managed-deployments` item. */
export interface ManagedDeployment {
  id: string
  name: string
  status: ManagedDeploymentStatus
  source: DeploymentSource
  lastDeployedAt: string | null
  createdAt: string
  /** The managed proxy deployment: ADMIN only, shown as managed via Domains. Optional for older servers. */
  systemManaged?: boolean
}

/** Git source of a deployment (`null` for FILE deployments); never contains a token. */
export interface GitInfo {
  repoUrl: string
  ref: string
  composePath: string
  credentialId: string | null
  lastCommitSha: string | null
  autoUpdateEnabled: boolean
  autoUpdateIntervalSeconds: number | null
  lastPolledAt: string | null
  /** Error code of the last failed auto-update check. */
  lastPollError: string | null
}

/** Scheduled image auto-update of a FILE deployment (`null` for Git deployments). Window times are `HH:mm` UTC. */
export interface ImageAutoUpdateInfo {
  enabled: boolean
  windowStart: string | null
  windowEnd: string | null
  lastAttemptAt: string | null
  /**
   * `APPLIED`; `FAILED` (pull failed, nothing changed); `ROLLED_BACK`; `FAILED_NOT_ROLLED_BACK`; null before the
   * first attempt.
   */
  lastResult: string | null
}

export interface ManagedDeploymentDetail extends ManagedDeployment {
  yaml: string
  git?: GitInfo | null
  imageAutoUpdate?: ImageAutoUpdateInfo | null
  /** Variable names only; values are never part of this response. */
  envKeys: string[]
  /** Active Compose profiles. */
  profiles: string[]
}

export interface EnvEntry {
  key: string
  value: string
}

export interface DeploymentVersion {
  id: string
  source: VersionSource
  commitSha: string | null
  createdBy: string | null
  createdAt: string
  current: boolean
  /** False marks the editable draft. */
  deployed: boolean
}

/** `GET .../versions/{versionId}`: YAML plus env key flags (never values). */
export interface DeploymentVersionDetail extends DeploymentVersion {
  yaml: string
  /** Newest deployed version: what the diff compares against; null when nothing was deployed. */
  baselineVersionId: string | null
  /** `changed` is relative to the baseline. */
  env: { key: string; changed: boolean }[]
  removedKeys: string[]
}

/** Deploy and rollback answer 202; output streams on `/topic/deployments/{runId}`. */
export interface DeploymentRun {
  runId: string
  versionId: string
}

export interface CreateDeploymentInput {
  name: string
  yaml: string
  env: EnvEntry[]
}

export interface CreateGitDeploymentInput {
  source: 'GIT'
  name: string
  repoUrl: string
  ref: string
  composePath: string
  credentialId?: string
  env: EnvEntry[]
}

const managedBase = (hostId: string) => `/hosts/${hostId}/managed-deployments`
const managedKey = (hostId: string) => ['hosts', hostId, 'managed-deployments'] as const

export function useManagedDeployments(hostId: string) {
  return useQuery({ queryKey: managedKey(hostId), queryFn: () => apiFetch<ManagedDeployment[]>(managedBase(hostId)) })
}

export function useManagedDeployment(hostId: string, deploymentId: string) {
  return useQuery({
    queryKey: [...managedKey(hostId), deploymentId],
    queryFn: () => apiFetch<ManagedDeploymentDetail>(`${managedBase(hostId)}/${deploymentId}`),
  })
}

export function useDeploymentVersions(hostId: string, deploymentId: string) {
  return useQuery({
    queryKey: [...managedKey(hostId), deploymentId, 'versions'],
    queryFn: () => apiFetch<DeploymentVersion[]>(`${managedBase(hostId)}/${deploymentId}/versions`),
  })
}

export function useDeploymentVersionDetail(hostId: string, deploymentId: string, versionId: string | null) {
  return useQuery({
    queryKey: [...managedKey(hostId), deploymentId, 'versions', versionId],
    queryFn: () => apiFetch<DeploymentVersionDetail>(`${managedBase(hostId)}/${deploymentId}/versions/${versionId}`),
    enabled: versionId !== null,
  })
}

/** Refreshes everything that shows this host's deployments (managed list, details, versions, live list). */
function useInvalidateDeployments(hostId: string) {
  const queryClient = useQueryClient()
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: managedKey(hostId) }),
      queryClient.invalidateQueries({ queryKey: ['hosts', hostId, 'deployments'] }),
    ])
}

export function useCreateDeployment(hostId: string) {
  const invalidate = useInvalidateDeployments(hostId)
  return useMutation({
    mutationFn: (input: CreateDeploymentInput | CreateGitDeploymentInput) =>
      apiFetch<ManagedDeploymentDetail>(managedBase(hostId), { method: 'POST', body: input }),
    onSuccess: () => void invalidate(),
  })
}

export interface UpdateDeploymentInput {
  deploymentId: string
  yaml: string
  /** `null` keeps the stored variables, `[]` clears them. */
  env: EnvEntry[] | null
}

/** Saves a draft version (PUT). Does not deploy. */
export function useUpdateDeployment(hostId: string) {
  const invalidate = useInvalidateDeployments(hostId)
  return useMutation({
    mutationFn: ({ deploymentId, yaml, env }: UpdateDeploymentInput) =>
      apiFetch<ManagedDeploymentDetail>(`${managedBase(hostId)}/${deploymentId}`, { method: 'PUT', body: { yaml, env } }),
    onSuccess: () => void invalidate(),
  })
}

/** Reads the stored variable values (ADMIN/OPERATOR). A mutation, so secrets never enter the query cache. */
export function useLoadDeploymentEnv(hostId: string) {
  return useMutation({
    mutationFn: (deploymentId: string) => apiFetch<{ entries: EnvEntry[] }>(`${managedBase(hostId)}/${deploymentId}/env`),
  })
}

export function useDeleteDeployment(hostId: string) {
  const invalidate = useInvalidateDeployments(hostId)
  return useMutation({
    mutationFn: (deploymentId: string) => apiFetch<void>(`${managedBase(hostId)}/${deploymentId}`, { method: 'DELETE' }),
    onSuccess: () => void invalidate(),
  })
}

export function useDeployDeployment(hostId: string) {
  const invalidate = useInvalidateDeployments(hostId)
  return useMutation({
    mutationFn: (deploymentId: string) =>
      apiFetch<DeploymentRun>(`${managedBase(hostId)}/${deploymentId}/deploy`, { method: 'POST' }),
    onSuccess: () => void invalidate(),
  })
}

export function useStopDeployment(hostId: string) {
  const invalidate = useInvalidateDeployments(hostId)
  return useMutation({
    mutationFn: (deploymentId: string) => apiFetch<ManagedDeployment>(`${managedBase(hostId)}/${deploymentId}/stop`, { method: 'POST' }),
    onSuccess: () => void invalidate(),
  })
}

export function useRollbackDeployment(hostId: string) {
  const invalidate = useInvalidateDeployments(hostId)
  return useMutation({
    mutationFn: ({ deploymentId, versionId }: { deploymentId: string; versionId: string }) =>
      apiFetch<DeploymentRun>(`${managedBase(hostId)}/${deploymentId}/versions/${versionId}/rollback`, { method: 'POST' }),
    onSuccess: () => void invalidate(),
  })
}

/** Turns the wizard spec into Compose YAML (`POST /api/compose/generate`); 400 INVALID_COMPOSE_SPEC lists violations in `details`. */
export function useGenerateCompose(hostId?: string) {
  return useMutation({
    // `hostId` lets the server refuse labels the host's managed proxy would ignore (managed Caddy).
    mutationFn: (spec: ComposeSpecPayload) =>
      apiFetch<{ yaml: string }>('/compose/generate', { method: 'POST', body: { spec, hostId } }),
  })
}

/** Pulls newer images and recreates the containers of a FILE deployment (`POST .../pull-update`, 202); output streams like a deploy. */
export function usePullUpdate(hostId: string) {
  const invalidate = useInvalidateDeployments(hostId)
  return useMutation({
    mutationFn: (deploymentId: string) =>
      apiFetch<DeploymentRun>(`${managedBase(hostId)}/${deploymentId}/pull-update`, { method: 'POST' }),
    onSuccess: () => void invalidate(),
  })
}

/** Pulls the repository and redeploys (`POST .../git/redeploy`, 202); output streams like a normal deploy. */
export function useGitRedeploy(hostId: string) {
  const invalidate = useInvalidateDeployments(hostId)
  return useMutation({
    mutationFn: (deploymentId: string) =>
      apiFetch<DeploymentRun>(`${managedBase(hostId)}/${deploymentId}/git/redeploy`, { method: 'POST' }),
    onSuccess: () => void invalidate(),
  })
}

export interface AutoUpdateInput {
  deploymentId: string
  enabled: boolean
  /** Seconds, at least 60. */
  intervalSeconds: number
}

export function useSetAutoUpdate(hostId: string) {
  const invalidate = useInvalidateDeployments(hostId)
  return useMutation({
    mutationFn: ({ deploymentId, enabled, intervalSeconds }: AutoUpdateInput) =>
      apiFetch<ManagedDeploymentDetail>(`${managedBase(hostId)}/${deploymentId}/git/auto-update`, {
        method: 'PUT',
        body: { enabled, intervalSeconds },
      }),
    onSuccess: () => void invalidate(),
  })
}

export interface ImageAutoUpdateInput {
  deploymentId: string
  enabled: boolean
  windowStart: string | null
  windowEnd: string | null
}

export function useSetImageAutoUpdate(hostId: string) {
  const invalidate = useInvalidateDeployments(hostId)
  return useMutation({
    mutationFn: ({ deploymentId, ...body }: ImageAutoUpdateInput) =>
      apiFetch<ManagedDeploymentDetail>(`${managedBase(hostId)}/${deploymentId}/image-auto-update`, { method: 'PUT', body }),
    onSuccess: () => void invalidate(),
  })
}

export interface RemoveUnmanagedInput {
  /** Compose project name, as listed by `GET /hosts/{id}/deployments`. */
  name: string
  /** Also deletes the project's named volumes; off keeps data. */
  removeVolumes: boolean
}

/** Runs `compose down` for an unmanaged project (`DELETE /hosts/{id}/deployments/{name}`, 204). 409 MANAGED_DEPLOYMENT for Shipir-managed ones. */
export function useRemoveUnmanagedDeployment(hostId: string) {
  const invalidate = useInvalidateDeployments(hostId)
  return useMutation({
    mutationFn: ({ name, removeVolumes }: RemoveUnmanagedInput) =>
      apiFetch<void>(`/hosts/${hostId}/deployments/${encodeURIComponent(name)}?removeVolumes=${removeVolumes}`, { method: 'DELETE' }),
    onSuccess: () => void invalidate(),
  })
}

export interface AdoptInput {
  project: string
  /** Omitted = read the files the project was started with. */
  yaml?: string
  env?: EnvEntry[]
}

/** Takes an unmanaged Compose project under Shipir's management (`POST /hosts/{id}/deployments/{project}/adopt`, 201). */
export function useAdoptDeployment(hostId: string) {
  const invalidate = useInvalidateDeployments(hostId)
  return useMutation({
    mutationFn: ({ project, ...body }: AdoptInput) =>
      apiFetch<ManagedDeploymentDetail>(`/hosts/${hostId}/deployments/${encodeURIComponent(project)}/adopt`, { method: 'POST', body }),
    onSuccess: () => void invalidate(),
  })
}

/** `GET /api/hosts/{id}/proxy`: the reverse proxy running on the host; all null when none is detected; `visible` false when the caller does not see the whole host (not the same as no proxy). */
export interface DetectedProxy {
  visible: boolean
  proxy: ProxyType | null
  container: string | null
  network: string | null
}

export function useDetectedProxy(hostId: string, enabled = true) {
  return useQuery({
    queryKey: ['hosts', hostId, 'proxy'],
    queryFn: () => apiFetch<DetectedProxy>(`/hosts/${hostId}/proxy`),
    enabled,
    refetchOnWindowFocus: false,
  })
}

/** Bundled Traefik Compose file (`GET /api/compose/proxy-templates/traefik`), fetched when the user asks for it. */
export function useTraefikTemplate() {
  return useMutation({
    mutationFn: () => apiFetch<{ yaml: string }>('/compose/proxy-templates/traefik'),
  })
}
