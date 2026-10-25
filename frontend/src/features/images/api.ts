import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch, readCookie } from '../../shared/api/client.ts'
import { HOSTS_OVERVIEW_KEY } from '../hosts/api.ts'
import { useDeleteResourceMutation } from '../hosts/deleteResource.ts'
import { MAX_BUILD_CONTEXT_LABEL } from './buildRules.ts'

/** One image of `GET /api/hosts/{id}/images`; `repoTags` is empty for dangling images. */
export interface Image {
  id: string
  repoTags: string[]
  size: number | null
  created: string
  inUse: boolean
  repoDigests?: string[]
  /** A newer digest exists in the registry for one of the tags. */
  updateAvailable?: boolean
}

export function useImages(hostId: string) {
  return useQuery({
    queryKey: ['hosts', hostId, 'images'],
    queryFn: () => apiFetch<Image[]>(`/hosts/${hostId}/images`),
  })
}

/** Argument is the id. */
export function useDeleteImage(hostId: string) {
  return useDeleteResourceMutation(hostId, 'images', (ref: string) => ({
    ref,
  }))
}

/** `POST /hosts/{id}/images/pull` answers 202; progress streams on `/topic/deployments/{runId}`. */
export function usePullImage(hostId: string) {
  return useMutation({
    mutationFn: (reference: string) =>
      apiFetch<{ runId: string }>(`/hosts/${hostId}/images/pull`, {
        method: 'POST',
        body: { reference },
      }),
  })
}

/** Refreshes everything a finished pull or update can change. */
export function useRefreshAfterPull(hostId: string) {
  const queryClient = useQueryClient()
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ['hosts', hostId] }),
      queryClient.invalidateQueries({ queryKey: HOSTS_OVERVIEW_KEY }),
    ])
}

export function describePullError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'INVALID_IMAGE_REFERENCE')
      return 'This is not a valid image reference. Example: nginx:1.27 or ghcr.io/org/app:latest.'
    if (error.code === 'HOST_NOT_FOUND') return 'This environment no longer exists.'
    if (error.status === 403) return 'You are not allowed to pull images here.'
  }
  return 'Could not start the pull. Try again.'
}

/** Normalises a container's image string to a tag reference (`nginx` -> `nginx:latest`). */
export function withTag(image: string): string {
  const lastSlash = image.lastIndexOf('/')
  return image.indexOf(':', lastSlash + 1) === -1 && !image.includes('@') ? `${image}:latest` : image
}

export interface BuildInput {
  source: 'upload' | 'git'
  file: File | null
  gitUrl: string
  gitRef: string
  gitCredentialId: string
  tag: string
  dockerfile: string
  /** `KEY=VALUE` lines. */
  buildArgs: string[]
  noCache: boolean
}

const BUILD_ERRORS: Record<string, string> = {
  BUILD_IN_PROGRESS: 'A build is already running on this environment. Wait for it to finish, then try again.',
  SIZE_LIMIT_EXCEEDED: `The build context is larger than ${MAX_BUILD_CONTEXT_LABEL}. Remove files with a .dockerignore or use a Git repository.`,
  INVALID_ARCHIVE: 'The file is not a valid tar archive (.tar or .tar.gz without unsafe paths or links).',
  INVALID_BUILD_SOURCE: 'Provide either an uploaded context or a Git repository with a branch, tag or commit.',
  INVALID_IMAGE_REFERENCE: 'This is not a valid image reference. Example: myapp:1.0 or ghcr.io/org/app:latest.',
  INVALID_GIT_REF: 'This is not a valid Git branch, tag or commit.',
  INVALID_GIT_CREDENTIAL: 'The selected Git credential is not valid.',
  INVALID_DOCKERFILE_PATH: 'The Dockerfile path must be a relative path inside the context.',
  INVALID_BUILD_ARG: 'A build argument is not valid. Use KEY=VALUE with letters, digits and underscores in the key.',
  HOST_NOT_FOUND: 'This environment no longer exists.',
}

async function toApiError(response: Response): Promise<ApiError> {
  let code: string | undefined
  try {
    const parsed: unknown = JSON.parse(await response.text())
    if (parsed && typeof parsed === 'object' && 'code' in parsed && typeof parsed.code === 'string') code = parsed.code
  } catch {
    // not JSON: keep the generic error
  }
  return new ApiError(response.status, response.statusText, code)
}

/** Multipart POST; not `apiFetch` (JSON only). The browser sets the boundary; CSRF header as in `restoreVolume`. */
export async function startBuild(hostId: string, input: BuildInput): Promise<{ runId: string }> {
  const form = new FormData()
  if (input.source === 'upload' && input.file) {
    form.append('context', input.file)
  } else {
    form.append('gitUrl', input.gitUrl.trim())
    form.append('gitRef', input.gitRef.trim())
    if (input.gitCredentialId) form.append('gitCredentialId', input.gitCredentialId)
  }
  form.append('tag', input.tag.trim())
  form.append('dockerfile', input.dockerfile.trim() || 'Dockerfile')
  for (const arg of input.buildArgs) form.append('buildArg', arg)
  form.append('noCache', String(input.noCache))
  const csrf = readCookie('XSRF-TOKEN')
  const response = await fetch(`/api/hosts/${hostId}/images/build`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
      ...(csrf && { 'X-XSRF-TOKEN': csrf }),
    },
    body: form,
  })
  if (!response.ok) throw await toApiError(response)
  return (await response.json()) as { runId: string }
}

export function describeBuildError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code && BUILD_ERRORS[error.code]) return BUILD_ERRORS[error.code]
    if (error.status === 413) return BUILD_ERRORS.SIZE_LIMIT_EXCEEDED
    if (error.status === 403) return 'Only administrators can build images.'
  }
  return 'Could not start the build. Try again.'
}

export function useBuildImage(hostId: string) {
  return useMutation({
    mutationFn: (input: BuildInput) => startBuild(hostId, input),
  })
}

/** `POST /hosts/{id}/images/{imageId}/tag` answers 204. */
export function useTagImage(hostId: string) {
  const refresh = useRefreshAfterPull(hostId)
  return useMutation({
    mutationFn: (v: { imageId: string; repository: string; tag: string }) =>
      apiFetch<void>(`/hosts/${hostId}/images/${encodeURIComponent(v.imageId)}/tag`, {
        method: 'POST',
        body: { repository: v.repository, tag: v.tag },
      }),
    onSuccess: () => refresh(),
  })
}

/** `POST /hosts/{id}/images/push` answers 202; output streams on `/topic/deployments/{runId}`. */
export function usePushImage(hostId: string) {
  return useMutation({
    mutationFn: (reference: string) =>
      apiFetch<{ runId: string }>(`/hosts/${hostId}/images/push`, {
        method: 'POST',
        body: { reference },
      }),
  })
}

export function describeTagError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'INVALID_IMAGE_REFERENCE') return 'This is not a valid repository or tag.'
    if (error.code === 'RESOURCE_NOT_FOUND') return 'This image no longer exists.'
    if (error.status === 403) return 'You are not allowed to tag images here.'
  }
  return 'Could not tag the image. Try again.'
}

export function describePushError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'INVALID_IMAGE_REFERENCE') return 'This is not a valid image reference to push.'
    if (error.status === 403) return 'You are not allowed to push images here.'
  }
  return 'Could not start the push. Try again.'
}
