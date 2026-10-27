import { skipToken, useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import type { ViewContext, ViewPage } from './api.ts'

export type { ViewContext, ViewPage } from './api.ts'

// Same shapes the server validates (backend ViewContext): anything else would get a 400, so it is left out.
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const CONTAINER_ID = /^[0-9a-f]{12,64}$/

const SUB_PAGES: Record<string, ViewPage> = {
  deployments: 'DEPLOYMENT',
  containers: 'CONTAINER',
  images: 'IMAGES',
  networks: 'NETWORKS',
  volumes: 'VOLUMES',
}

/** Maps a route to the page the user is looking at; null for pages the assistant gets no context for. */
export function parseViewContext(pathname: string): ViewContext | null {
  const parts = pathname.split('/').filter(Boolean)
  if (parts.length === 0) return { page: 'OVERVIEW' }
  if (parts[0] === 'settings') return { page: 'SETTINGS' }
  if (parts[0] !== 'environments' || parts.length < 2) return null

  const hostId = UUID.test(parts[1]) ? parts[1] : undefined
  const withHost = (ctx: ViewContext): ViewContext => (hostId ? { ...ctx, hostId } : ctx)
  if (parts.length === 2) return withHost({ page: 'HOST' })

  const page = SUB_PAGES[parts[2]]
  if (!page) return null
  const ctx = withHost({ page })
  const entity = parts[3]
  if (entity && page === 'DEPLOYMENT' && UUID.test(entity)) ctx.deploymentId = entity
  if (entity && page === 'CONTAINER' && CONTAINER_ID.test(entity)) ctx.containerId = entity
  return ctx
}

/** Reads a query other pages cached; `skipToken` means this never fetches. */
function useCached<T>(key: readonly unknown[]): T | undefined {
  return useQuery<T>({ queryKey: key, queryFn: skipToken }).data
}

interface Named {
  id: string
  name: string
}

/**
 * The view context of the current route plus a label for the composer chip. Names come from queries other pages
 * already cached (no extra requests); without a cached name the page name is shown.
 */
export function useViewContext(): { context: ViewContext | null; label: string | null } {
  const { t } = useTranslation('ai-chat')
  const { pathname } = useLocation()
  const context = useMemo(() => parseViewContext(pathname), [pathname])
  const hostId = context?.hostId ?? ''

  const hosts = useCached<Named[]>(['hosts'])
  const deployments = useCached<Named[]>(['hosts', hostId, 'managed-deployments'])
  const containers = useCached<Named[]>(['hosts', hostId, 'containers'])
  const containerDetails = useCached<Named>(['hosts', hostId, 'containers', context?.containerId ?? ''])

  if (!context) return { context: null, label: null }
  const name =
    (context.containerId &&
      (containerDetails?.name ?? containers?.find((c) => c.id.startsWith(context.containerId!))?.name)) ||
    (context.deploymentId && deployments?.find((d) => d.id === context.deploymentId)?.name) ||
    (context.page === 'HOST' && context.hostId && hosts?.find((h) => h.id === context.hostId)?.name) ||
    undefined
  return { context, label: name || t(`ViewContext.page.${context.page}`) }
}
