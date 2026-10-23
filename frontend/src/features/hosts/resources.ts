/** Resource sections of an environment: one entry per sidebar link, dashboard tile and route. */
export const ENV_RESOURCES = [
  { slug: 'deployments', label: 'Deployments' },
  { slug: 'containers', label: 'Containers' },
  { slug: 'images', label: 'Images' },
  { slug: 'networks', label: 'Networks' },
  { slug: 'volumes', label: 'Volumes' },
] as const

export type EnvResourceSlug = (typeof ENV_RESOURCES)[number]['slug']
