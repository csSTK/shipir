import { Boxes, Container, HardDrive, Layers, Network, type LucideIcon } from 'lucide-react'

/** Resource sections of an environment: one entry per sidebar link, dashboard tile and route. */
export const ENV_RESOURCES = [
  { slug: 'deployments', label: 'Deployments', icon: Layers, tone: 'accent' },
  { slug: 'containers', label: 'Containers', icon: Container, tone: 'ok' },
  { slug: 'images', label: 'Images', icon: Boxes, tone: 'info' },
  { slug: 'networks', label: 'Networks', icon: Network, tone: 'warn' },
  { slug: 'volumes', label: 'Volumes', icon: HardDrive, tone: 'muted' },
] as const satisfies readonly { slug: string; label: string; icon: LucideIcon; tone: string }[]

export type EnvResourceSlug = (typeof ENV_RESOURCES)[number]['slug']
