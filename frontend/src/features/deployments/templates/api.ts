import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '../../../shared/api/client.ts'
import type { FreePortSuggestion, TemplateSpecInput } from '../wizard/index.ts'

export interface TemplateEnvHint {
  key: string
  description: string
  secret: boolean
  /** Prefilled value; absent when the user has to choose one. */
  defaultValue?: string | null
  /** True when the installer fills in a random value (passwords, keys). */
  generate?: boolean
}

/** A bundled app template (`GET /api/templates`); `spec` is the bare Compose spec the wizard sends. */
export interface AppTemplate {
  id: string
  name: string
  description: string
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

/** Free host ports for `preferred` on a host (`GET /api/hosts/{id}/ports/free`). */
export async function fetchFreePorts(hostId: string, preferred: number[]): Promise<FreePortSuggestion[]> {
  const res = await apiFetch<{ suggestions: FreePortSuggestion[] }>(`/hosts/${encodeURIComponent(hostId)}/ports/free?preferred=${preferred.join(',')}`)
  return res.suggestions
}
