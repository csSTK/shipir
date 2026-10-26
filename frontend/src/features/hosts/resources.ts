import { Boxes, Container, HardDrive, Layers, Network, type LucideIcon } from 'lucide-react'
import i18n from 'i18next'

/** Resource sections of an environment: one entry per sidebar link, dashboard tile and route. */
export const ENV_RESOURCES = [
  { slug: 'deployments', get label() { return i18n.t('hosts:resources.deployments') }, icon: Layers, tone: 'accent' },
  { slug: 'containers', get label() { return i18n.t('hosts:resources.containers') }, icon: Container, tone: 'ok' },
  { slug: 'images', get label() { return i18n.t('hosts:resources.images') }, icon: Boxes, tone: 'info' },
  { slug: 'networks', get label() { return i18n.t('hosts:resources.networks') }, icon: Network, tone: 'warn' },
  { slug: 'volumes', get label() { return i18n.t('hosts:resources.volumes') }, icon: HardDrive, tone: 'muted' },
] as const satisfies readonly { slug: string; label: string; icon: LucideIcon; tone: string }[]

export type EnvResourceSlug = (typeof ENV_RESOURCES)[number]['slug']
