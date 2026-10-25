import { ApiError } from '../../shared/api/client.ts'

/** Mirrors `HostTagPolicy` on the server: at most 10 distinct tags, each 1-24 characters. */
export const TAG_MAX = 10
export const TAG_PATTERN = /^[a-z0-9][a-z0-9-]{0,23}$/
const TAG_RULE = 'Use 1 to 24 lowercase letters, digits or hyphens, starting with a letter or digit.'

export interface AddTagResult {
  tags: string[]
  /** Inline message for the rejected draft; null when the draft was added or was empty. */
  problem: string | null
}

/** The server normalises the same way: trim, then lowercase. */
export function normalizeTag(raw: string): string {
  return raw.trim().toLowerCase()
}

/** Adds one draft to the list. An empty draft changes nothing and is not a problem. */
export function addTag(tags: string[], raw: string): AddTagResult {
  const tag = normalizeTag(raw)
  if (tag === '') return { tags, problem: null }
  if (!TAG_PATTERN.test(tag)) return { tags, problem: TAG_RULE }
  if (tags.includes(tag)) return { tags, problem: `“${tag}” is already added.` }
  if (tags.length >= TAG_MAX) return { tags, problem: `An environment can have at most ${TAG_MAX} tags.` }
  return { tags: [...tags, tag], problem: null }
}

/** Server codes from `PUT /hosts/{id}/tags` mapped to inline messages. */
export function describeTagError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'TOO_MANY_TAGS') return `An environment can have at most ${TAG_MAX} tags.`
    if (error.code === 'INVALID_TAG') return TAG_RULE
    if (error.code === 'TAGS_CONFLICT') return 'The tags changed meanwhile. Try again.'
  }
  return 'Could not save the tags. Try again.'
}

/** Rows from an older API or a mock may lack the field; treat it as no tags. */
export function envTags(env: { tags?: string[] }): string[] {
  return env.tags ?? []
}
