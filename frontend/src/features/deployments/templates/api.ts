import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '../../../shared/api/client.ts'
import type { TemplateSpecInput } from '../wizard/index.ts'

export interface TemplateEnvHint {
  key: string
  description: string
  secret: boolean
}

/** A bundled app template (`GET /api/templates`); `spec` is the bare Compose spec the wizard sends. */
export interface AppTemplate {
  id: string
  name: string
  description: string
  /** Slug of the app, not an image URL. */
  logo: string
  env: TemplateEnvHint[]
  spec: TemplateSpecInput
}

export const TEMPLATES_KEY = ['templates'] as const

/** The catalog is bundled with the backend and static while the app runs. */
export function useTemplates() {
  return useQuery({
    queryKey: TEMPLATES_KEY,
    queryFn: () => apiFetch<AppTemplate[]>('/templates'),
    staleTime: Infinity,
  })
}
