/** Form state of the Compose wizard and its mapping to the `POST /api/compose/generate` spec. */

import i18n from 'i18next'

export const RESTART_POLICIES = ['no', 'always', 'on-failure', 'unless-stopped'] as const

export interface PortRow {
  host: string
  container: string
  protocol: 'tcp' | 'udp'
}
export interface EnvRow {
  key: string
  /** Literal value, or the `.env` key to reference when `ref` is set. */
  value: string
  ref: boolean
}
export interface MountRow {
  source: string
  target: string
  readOnly: boolean
}
export const PROXY_TYPES = ['TRAEFIK', 'CADDY'] as const
export type ProxyType = (typeof PROXY_TYPES)[number]

/** Public domain of a service behind a reverse proxy; unused while `host` is empty. */
export interface DomainForm {
  host: string
  /** Container port the proxy forwards to. */
  port: string
  proxy: ProxyType
  /** Network the proxy is attached to; empty = none. */
  network: string
  /** Traefik only: HTTPS entrypoint name; empty = Shipir's template default (`websecure`). */
  entrypoint: string
  /** Traefik only: ACME cert resolver name; empty = template default (`letsencrypt`). */
  certResolver: string
}
export interface ServiceForm {
  /** Stable React key, never sent. */
  uid: number
  name: string
  image: string
  /** One argument per whitespace-separated word; quotes group words. */
  command: string
  restart: string
  ports: PortRow[]
  env: EnvRow[]
  volumes: MountRow[]
  networks: string[]
  dependsOn: string[]
  domain: DomainForm
}
export interface ResourceForm {
  name: string
  driver: string
  external: boolean
}
export interface WizardState {
  services: ServiceForm[]
  networks: ResourceForm[]
  volumes: ResourceForm[]
}

export interface ComposeSpecPayload {
  services: {
    name: string
    image: string
    command: string[] | null
    restart: string | null
    ports: { host: number | null; container: number; protocol: string }[]
    environment: { key: string; value: string | null; ref: string | null }[]
    volumes: { source: string; target: string; readOnly: boolean }[]
    networks: string[]
    labels: null
    domain: { host: string; port: number; proxy: ProxyType; network: string | null; entrypoint?: string; certResolver?: string } | null
    dependsOn: string[]
  }[]
  networks: { name: string; driver: string | null; external: boolean }[]
  volumes: { name: string; driver: string | null; external: boolean }[]
}

const NAME = /^[a-z0-9][a-z0-9_-]{0,62}$/
const DRIVER = /^[a-z0-9][a-z0-9_.-]{0,62}$/
const ENV_KEY = /^[A-Za-z_][A-Za-z0-9_]*$/
const DOMAIN_HOST = /^(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$/
const TRAEFIK_NAME = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,62}$/
const DOCKER_NETWORK = /^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$/
const IMAGE = /^[^\s'"\\]+$/

export function emptyDomain(): DomainForm {
  return { host: '', port: '', proxy: 'TRAEFIK', network: '', entrypoint: '', certResolver: '' }
}

let nextUid = 1
export function emptyService(): ServiceForm {
  return { uid: nextUid++, name: '', image: '', command: '', restart: '', ports: [], env: [], volumes: [], networks: [], dependsOn: [], domain: emptyDomain() }
}
export function initialState(): WizardState {
  return { services: [emptyService()], networks: [], volumes: [] }
}

/** Splits a command line into exec-form arguments; `"a b"` and `'a b'` stay one argument. */
export function splitCommand(text: string): string[] {
  const args: string[] = []
  let current = ''
  let quote: string | null = null
  let started = false
  for (const ch of text) {
    if (quote) {
      if (ch === quote) quote = null
      else current += ch
    } else if (ch === '"' || ch === "'") {
      quote = ch
      started = true
    } else if (/\s/.test(ch)) {
      if (started) args.push(current)
      current = ''
      started = false
    } else {
      current += ch
      started = true
    }
  }
  if (started) args.push(current)
  return args
}

/** Plain name (declared volume) vs. bind path, same rule as the backend. */
export function isBindSource(source: string): boolean {
  return source.includes('/') || source.startsWith('.')
}

export function validatePort(value: string, required: boolean): string | null {
  const v = value.trim()
  if (v === '') return required ? i18n.t('deployments:model.enterAPort') : null
  if (!/^\d+$/.test(v) || Number(v) < 1 || Number(v) > 65535) return i18n.t('deployments:model.useAPortFrom1')
  return null
}

export interface WizardProblems {
  /** Keyed by a path like `services.0.name`, `services.0.ports.1.container`. */
  fields: Map<string, string>
  /** Problems without a single field (e.g. no services). */
  general: string[]
}

/** Traefik router and service name derived from a domain: dots become hyphens. Mirrors the backend's `routerId`. */
export function routerName(host: string): string {
  return host.replace(/\./g, '-')
}

export function validateWizard(state: WizardState): WizardProblems {
  const fields = new Map<string, string>()
  const general: string[] = []
  const set = (key: string, msg: string) => {
    if (!fields.has(key)) fields.set(key, msg)
  }
  const nameMsg = i18n.t('deployments:model.useLowercaseLettersDigitsHyphens')
  const declaredVolumes = new Set(state.volumes.map((v) => v.name.trim()))
  const names = new Set<string>()
  const hosts = new Set<string>()
  // Traefik names its router after the host; the backend (ComposeGeneratorService.routerId) does the same.
  const traefikRouters = new Map<string, string>()

  if (state.services.length === 0) general.push(i18n.t('deployments:model.addAtLeastOneService'))

  state.services.forEach((s, i) => {
    const p = `services.${i}`
    const name = s.name.trim()
    if (!NAME.test(name)) set(`${p}.name`, nameMsg)
    else if (names.has(name)) set(`${p}.name`, i18n.t('deployments:model.serviceNamesMustBeUnique'))
    names.add(name)
    if (!IMAGE.test(s.image.trim())) set(`${p}.image`, i18n.t('deployments:model.enterAnImageSuchAs'))
    const d = s.domain
    const host = d.host.trim()
    if (host !== '') {
      if (!DOMAIN_HOST.test(host)) set(`${p}.domain.host`, i18n.t('deployments:model.enterALowercaseDomainName'))
      else if (hosts.has(host)) set(`${p}.domain.host`, i18n.t('deployments:model.thisDomainIsAlreadyUsed'))
      else if (d.proxy === 'TRAEFIK' && traefikRouters.has(routerName(host))) set(`${p}.domain.host`, i18n.t('deployments:model.traefikRouterNameTaken'))
      hosts.add(host)
      if (d.proxy === 'TRAEFIK' && DOMAIN_HOST.test(host) && !traefikRouters.has(routerName(host))) traefikRouters.set(routerName(host), host)
      const port = validatePort(d.port, true)
      if (port) set(`${p}.domain.port`, port)
      const network = d.network.trim()
      if (network === 'default') set(`${p}.domain.network`, i18n.t('deployments:model.networkDefaultIsReserved'))
      else if (network !== '' && !DOCKER_NETWORK.test(network)) set(`${p}.domain.network`, i18n.t('deployments:model.useLettersDigitsDotsHyphens'))
      for (const key of ['entrypoint', 'certResolver'] as const) {
        const v = d[key].trim()
        if (d.proxy === 'TRAEFIK' && v !== '' && !TRAEFIK_NAME.test(v)) set(`${p}.domain.${key}`, i18n.t('deployments:model.useLettersDigitsDotsHyphens'))
      }
    }
    s.ports.forEach((r, j) => {
      const c = validatePort(r.container, true)
      if (c) set(`${p}.ports.${j}.container`, c)
      const h = validatePort(r.host, false)
      if (h) set(`${p}.ports.${j}.host`, h)
    })
    const keys = new Set<string>()
    s.env.forEach((r, j) => {
      const key = r.key.trim()
      if (!ENV_KEY.test(key)) set(`${p}.env.${j}.key`, i18n.t('deployments:model.useLettersDigitsAndUnderscores'))
      else if (keys.has(key)) set(`${p}.env.${j}.key`, i18n.t('deployments:model.thisNameIsUsedMore'))
      keys.add(key)
      if (r.ref && !ENV_KEY.test(r.value.trim())) set(`${p}.env.${j}.value`, i18n.t('deployments:model.enterTheNameOfA'))
    })
    s.volumes.forEach((r, j) => {
      const source = r.source.trim()
      const target = r.target.trim()
      if (source === '') set(`${p}.volumes.${j}.source`, i18n.t('deployments:model.enterAVolumeNameOr'))
      else if (source.startsWith('/') || source.split('/').includes('..')) set(`${p}.volumes.${j}.source`, i18n.t('deployments:model.useAPathInsideThe'))
      else if (!isBindSource(source) && !declaredVolumes.has(source)) set(`${p}.volumes.${j}.source`, i18n.t('deployments:model.declareThisVolumeUnderVolumes'))
      if (!target.startsWith('/')) set(`${p}.volumes.${j}.target`, i18n.t('deployments:model.enterAnAbsolutePathInside'))
    })
  })

  for (const [kind, list] of [['networks', state.networks], ['volumes', state.volumes]] as const) {
    const seen = new Set<string>()
    list.forEach((r, i) => {
      const name = r.name.trim()
      if (!NAME.test(name)) set(`${kind}.${i}.name`, nameMsg)
      else if (seen.has(name)) set(`${kind}.${i}.name`, i18n.t('deployments:model.namesMustBeUnique'))
      else if (kind === 'networks' && name === 'default' && r.external) set(`${kind}.${i}.name`, i18n.t('deployments:model.externalDefaultNetworkIsReserved'))
      seen.add(name)
      const driver = r.driver.trim()
      if (driver !== '' && !DRIVER.test(driver)) set(`${kind}.${i}.driver`, i18n.t('deployments:model.enterADriverNameSuch'))
      else if (driver !== '' && r.external) set(`${kind}.${i}.driver`, i18n.t('deployments:model.externalResourcesHaveNoDriver'))
    })
  }
  return { fields, general }
}

export function hasProblems(p: WizardProblems): boolean {
  return p.fields.size > 0 || p.general.length > 0
}

function orNull(text: string): string | null {
  const t = text.trim()
  return t === '' ? null : t
}

/** Builds the request spec from a (valid) form state. */
export function buildSpec(state: WizardState): ComposeSpecPayload {
  const networkNames = new Set(state.networks.map((n) => n.name.trim()))
  const serviceNames = new Set(state.services.map((s) => s.name.trim()))
  return {
    services: state.services.map((s) => {
      const command = splitCommand(s.command)
      return {
        name: s.name.trim(),
        image: s.image.trim(),
        command: command.length > 0 ? command : null,
        restart: orNull(s.restart),
        ports: s.ports.map((r) => ({
          host: r.host.trim() === '' ? null : Number(r.host),
          container: Number(r.container),
          protocol: r.protocol,
        })),
        environment: s.env.map((r) =>
          r.ref
            ? { key: r.key.trim(), value: null, ref: r.value.trim() }
            : { key: r.key.trim(), value: r.value, ref: null },
        ),
        volumes: s.volumes.map((r) => ({ source: r.source.trim(), target: r.target.trim(), readOnly: r.readOnly })),
        // Selections of renamed or removed networks/services are dropped.
        networks: s.networks.filter((n) => networkNames.has(n)),
        labels: null,
        domain:
          s.domain.host.trim() === ''
            ? null
            : {
                host: s.domain.host.trim(),
                port: Number(s.domain.port),
                proxy: s.domain.proxy,
                network: orNull(s.domain.network),
                // Traefik-only names; omitted when blank so the backend keeps its defaults.
                ...(s.domain.proxy === 'TRAEFIK' && s.domain.entrypoint.trim() !== '' && { entrypoint: s.domain.entrypoint.trim() }),
                ...(s.domain.proxy === 'TRAEFIK' && s.domain.certResolver.trim() !== '' && { certResolver: s.domain.certResolver.trim() }),
              },
        dependsOn: s.dependsOn.filter((d) => serviceNames.has(d) && d !== s.name.trim()),
      }
    }),
    networks: state.networks.map((n) => ({ name: n.name.trim(), driver: orNull(n.driver), external: n.external })),
    volumes: state.volumes.map((v) => ({ name: v.name.trim(), driver: orNull(v.driver), external: v.external })),
  }
}

/** `.env` keys referenced by the form, in order, without duplicates. */
export function referencedEnvKeys(state: WizardState): string[] {
  const keys: string[] = []
  for (const s of state.services) {
    for (const r of s.env) {
      const k = r.value.trim()
      if (r.ref && k !== '' && !keys.includes(k)) keys.push(k)
    }
  }
  return keys
}

/** Loose shape of a bundled template spec (`GET /api/templates`); every field except names/image may be missing or null. */
export interface TemplateSpecInput {
  services?: {
    name: string
    image: string
    command?: string[] | null
    restart?: string | null
    ports?: { host?: number | null; container: number; protocol?: string | null }[] | null
    environment?: { key: string; value?: string | null; ref?: string | null }[] | null
    volumes?: { source: string; target: string; readOnly?: boolean | null }[] | null
    networks?: string[] | null
    domain?: { host: string; port: number; proxy: string; network?: string | null; entrypoint?: string | null; certResolver?: string | null } | null
    dependsOn?: string[] | null
  }[] | null
  networks?: { name: string; driver?: string | null; external?: boolean | null }[] | null
  volumes?: { name: string; driver?: string | null; external?: boolean | null }[] | null
}

/** Quotes an argument that `splitCommand` would otherwise break apart. */
function quoteArg(arg: string): string {
  if (arg !== '' && !/[\s'"]/.test(arg)) return arg
  return arg.includes('"') ? `'${arg}'` : `"${arg}"`
}

/** Inverse of `buildSpec`: fills the wizard form from a spec (used by templates). */
export function specToState(spec: TemplateSpecInput): WizardState {
  const services = (spec.services ?? []).map((s) => ({
    ...emptyService(),
    name: s.name,
    image: s.image,
    command: (s.command ?? []).map(quoteArg).join(' '),
    restart: s.restart ?? '',
    ports: (s.ports ?? []).map((p) => ({
      host: p.host == null ? '' : String(p.host),
      container: String(p.container),
      protocol: p.protocol === 'udp' ? ('udp' as const) : ('tcp' as const),
    })),
    env: (s.environment ?? []).map((e) =>
      e.ref ? { key: e.key, value: e.ref, ref: true } : { key: e.key, value: e.value ?? '', ref: false },
    ),
    volumes: (s.volumes ?? []).map((v) => ({ source: v.source, target: v.target, readOnly: v.readOnly === true })),
    networks: s.networks ?? [],
    dependsOn: s.dependsOn ?? [],
    domain: s.domain
      ? { host: s.domain.host, port: String(s.domain.port), proxy: s.domain.proxy === 'CADDY' ? ('CADDY' as const) : ('TRAEFIK' as const), network: s.domain.network ?? '', entrypoint: s.domain.entrypoint ?? '', certResolver: s.domain.certResolver ?? '' }
      : emptyDomain(),
  }))
  const resource = (r: { name: string; driver?: string | null; external?: boolean | null }): ResourceForm => ({
    name: r.name,
    driver: r.driver ?? '',
    external: r.external === true,
  })
  return { services: services.length > 0 ? services : [emptyService()], networks: (spec.networks ?? []).map(resource), volumes: (spec.volumes ?? []).map(resource) }
}

export interface FreePortSuggestion {
  preferred: number
  /** Null when no port is left at all. */
  free: number | null
}

/** Distinct numeric host ports the wizard state asks for (blank "any" ports are skipped). */
export function preferredHostPorts(state: WizardState): number[] {
  const ports = state.services.flatMap((s) => s.ports.map((p) => (/^\d+$/.test(p.host.trim()) ? Number(p.host.trim()) : null)))
  return ports.filter((p, i): p is number => p !== null && ports.indexOf(p) === i)
}

/** Swaps each host port that has a different free suggestion; everything else stays. */
export function applyFreePorts(state: WizardState, suggestions: FreePortSuggestion[]): { state: WizardState; replaced: { from: number; to: number }[] } {
  const target = new Map<number, number>()
  for (const s of suggestions) if (s.free !== null && s.free !== s.preferred) target.set(s.preferred, s.free)
  const replaced = [...target].map(([from, to]) => ({ from, to }))
  const services = state.services.map((svc) => ({
    ...svc,
    ports: svc.ports.map((p) => {
      const to = /^\d+$/.test(p.host.trim()) ? target.get(Number(p.host.trim())) : undefined
      return to === undefined ? p : { ...p, host: String(to) }
    }),
  }))
  return { state: { ...state, services }, replaced: replaced.filter((r) => state.services.some((s) => s.ports.some((p) => p.host.trim() === String(r.from)))) }
}
