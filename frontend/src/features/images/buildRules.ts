/** Client-side mirror of the server rules (`ImageReferenceRules`, `ImageBuildService`); the server stays authoritative. */
export const MAX_BUILD_CONTEXT_BYTES = 200 * 1024 * 1024
export const MAX_BUILD_CONTEXT_LABEL = '200 MiB'
export const MAX_BUILD_ARGS = 50

const PATH_PART = '[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*'
const DOMAIN = '[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?(?::[0-9]{1,5})?'
const TAG = '[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}'
const REPOSITORY = `(?:${DOMAIN}/)?${PATH_PART}(?:/${PATH_PART})*`
const REFERENCE_RE = new RegExp(`^${REPOSITORY}(?::${TAG})?$`)
const REPOSITORY_RE = new RegExp(`^${REPOSITORY}$`)
const TAG_RE = new RegExp(`^${TAG}$`)
const BUILD_ARG_KEY_RE = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/

/** A tag for a build: `repository[:tag]`, no digest. */
export function isValidReference(reference: string): boolean {
  return reference.length <= 255 && REFERENCE_RE.test(reference)
}

export function isValidRepository(repository: string): boolean {
  return repository.length <= 200 && REPOSITORY_RE.test(repository)
}

export function isValidTag(tag: string): boolean {
  return TAG_RE.test(tag)
}

export function isValidBuildArgKey(key: string): boolean {
  return BUILD_ARG_KEY_RE.test(key)
}

export function hasContextExtension(name: string): boolean {
  return /\.(tar|tar\.gz|tgz)$/i.test(name)
}

/** Splits `repo:tag` at the tag colon (not a registry port). `repo` alone yields tag `latest`. */
export function splitReference(reference: string): {
  repository: string
  tag: string
} {
  const colon = reference.lastIndexOf(':')
  if (colon === -1 || colon < reference.lastIndexOf('/')) return { repository: reference, tag: 'latest' }
  return {
    repository: reference.slice(0, colon),
    tag: reference.slice(colon + 1),
  }
}
