import { ApiError } from '../../shared/api/client.ts'
import i18n from 'i18next'

/** Mirrors `HostTagPolicy` on the server: at most 10 distinct tags, each 1-24 characters. */
export const TAG_MAX = 10
export const TAG_PATTERN = /^[a-z0-9][a-z0-9-]{0,23}$/
const TAG_RULE = () => i18n.t('hosts:tags.use1To24Lowercase')

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
  if (!TAG_PATTERN.test(tag)) return { tags, problem: TAG_RULE() }
  if (tags.includes(tag)) return { tags, problem: i18n.t('hosts:tags.tagIsAlreadyAdded', { tag }) }
  if (tags.length >= TAG_MAX) return { tags, problem: i18n.t('hosts:tags.anEnvironmentCanHaveAt', { TAG_MAX }) }
  return { tags: [...tags, tag], problem: null }
}

/** Server codes from `PUT /hosts/{id}/tags` mapped to inline messages. */
export function describeTagError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'TOO_MANY_TAGS') return i18n.t('hosts:tags.anEnvironmentCanHaveAt', { TAG_MAX })
    if (error.code === 'INVALID_TAG') return TAG_RULE()
    if (error.code === 'TAGS_CONFLICT') return i18n.t('hosts:tags.theTagsChangedMeanwhileTry')
  }
  return i18n.t('hosts:tags.couldNotSaveTheTags')
}

/** Rows from an older API or a mock may lack the field; treat it as no tags. */
export function envTags(env: { tags?: string[] }): string[] {
  return env.tags ?? []
}
