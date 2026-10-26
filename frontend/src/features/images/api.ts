import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch, readCookie } from '../../shared/api/client.ts'
import { HOSTS_OVERVIEW_KEY } from '../hosts/api.ts'
import { useDeleteResourceMutation } from '../hosts/deleteResource.ts'
import { MAX_BUILD_CONTEXT_LABEL } from './buildRules.ts'
import i18n from 'i18next'

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
      return i18n.t('images:api.thisIsNotAValid')
    if (error.code === 'HOST_NOT_FOUND') return i18n.t('images:api.thisEnvironmentNoLongerExists')
    if (error.status === 403) return i18n.t('images:api.youAreNotAllowedTo')
  }
  return i18n.t('images:api.couldNotStartThePull')
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
  get BUILD_IN_PROGRESS() { return i18n.t('images:api.aBuildIsAlreadyRunning') },
  get SIZE_LIMIT_EXCEEDED() { return i18n.t('images:api.theBuildContextIsLarger', { MAX_BUILD_CONTEXT_LABEL }) },
  get INVALID_ARCHIVE() { return i18n.t('images:api.theFileIsNotA') },
  get INVALID_BUILD_SOURCE() { return i18n.t('images:api.provideEitherAnUploadedContext') },
  get INVALID_IMAGE_REFERENCE() { return i18n.t('images:api.thisIsNotAValid2') },
  get INVALID_GIT_REF() { return i18n.t('images:api.thisIsNotAValid3') },
  get INVALID_GIT_CREDENTIAL() { return i18n.t('images:api.theSelectedGitCredentialIs') },
  get INVALID_DOCKERFILE_PATH() { return i18n.t('images:api.theDockerfilePathMustBe') },
  get INVALID_BUILD_ARG() { return i18n.t('images:api.aBuildArgumentIsNot') },
  get HOST_NOT_FOUND() { return i18n.t('images:api.thisEnvironmentNoLongerExists') },
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
    if (error.status === 403) return i18n.t('images:api.onlyAdministratorsCanBuildImages')
  }
  return i18n.t('images:api.couldNotStartTheBuild')
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
    if (error.code === 'INVALID_IMAGE_REFERENCE') return i18n.t('images:api.thisIsNotAValid4')
    if (error.code === 'RESOURCE_NOT_FOUND') return i18n.t('images:api.thisImageNoLongerExists')
    if (error.status === 403) return i18n.t('images:api.youAreNotAllowedTo2')
  }
  return i18n.t('images:api.couldNotTagTheImage')
}

export function describePushError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'INVALID_IMAGE_REFERENCE') return i18n.t('images:api.thisIsNotAValid5')
    if (error.status === 403) return i18n.t('images:api.youAreNotAllowedTo3')
  }
  return i18n.t('images:api.couldNotStartThePush')
}
