/** Form state of the Compose wizard and its mapping to the `POST /api/compose/generate` spec. */

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
    dependsOn: string[]
  }[]
  networks: { name: string; driver: string | null; external: boolean }[]
  volumes: { name: string; driver: string | null; external: boolean }[]
}

const NAME = /^[a-z0-9][a-z0-9_-]{0,62}$/
const DRIVER = /^[a-z0-9][a-z0-9_.-]{0,62}$/
const ENV_KEY = /^[A-Za-z_][A-Za-z0-9_]*$/
const IMAGE = /^[^\s'"\\]+$/

let nextUid = 1
export function emptyService(): ServiceForm {
  return { uid: nextUid++, name: '', image: '', command: '', restart: '', ports: [], env: [], volumes: [], networks: [], dependsOn: [] }
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
  if (v === '') return required ? 'Enter a port.' : null
  if (!/^\d+$/.test(v) || Number(v) < 1 || Number(v) > 65535) return 'Use a port from 1 to 65535.'
  return null
}

export interface WizardProblems {
  /** Keyed by a path like `services.0.name`, `services.0.ports.1.container`. */
  fields: Map<string, string>
  /** Problems without a single field (e.g. no services). */
  general: string[]
}

export function validateWizard(state: WizardState): WizardProblems {
  const fields = new Map<string, string>()
  const general: string[] = []
  const set = (key: string, msg: string) => {
    if (!fields.has(key)) fields.set(key, msg)
  }
  const nameMsg = 'Use lowercase letters, digits, hyphens and underscores.'
  const declaredVolumes = new Set(state.volumes.map((v) => v.name.trim()))
  const names = new Set<string>()

  if (state.services.length === 0) general.push('Add at least one service.')

  state.services.forEach((s, i) => {
    const p = `services.${i}`
    const name = s.name.trim()
    if (!NAME.test(name)) set(`${p}.name`, nameMsg)
    else if (names.has(name)) set(`${p}.name`, 'Service names must be unique.')
    names.add(name)
    if (!IMAGE.test(s.image.trim())) set(`${p}.image`, 'Enter an image such as nginx:1.27.')
    s.ports.forEach((r, j) => {
      const c = validatePort(r.container, true)
      if (c) set(`${p}.ports.${j}.container`, c)
      const h = validatePort(r.host, false)
      if (h) set(`${p}.ports.${j}.host`, h)
    })
    const keys = new Set<string>()
    s.env.forEach((r, j) => {
      const key = r.key.trim()
      if (!ENV_KEY.test(key)) set(`${p}.env.${j}.key`, 'Use letters, digits and underscores; do not start with a digit.')
      else if (keys.has(key)) set(`${p}.env.${j}.key`, 'This name is used more than once.')
      keys.add(key)
      if (r.ref && !ENV_KEY.test(r.value.trim())) set(`${p}.env.${j}.value`, 'Enter the name of a deployment variable.')
    })
    s.volumes.forEach((r, j) => {
      const source = r.source.trim()
      const target = r.target.trim()
      if (source === '') set(`${p}.volumes.${j}.source`, 'Enter a volume name or a path like ./data.')
      else if (source.startsWith('/') || source.split('/').includes('..')) set(`${p}.volumes.${j}.source`, 'Use a path inside the deployment folder, like ./data.')
      else if (!isBindSource(source) && !declaredVolumes.has(source)) set(`${p}.volumes.${j}.source`, 'Declare this volume under Volumes first.')
      if (!target.startsWith('/')) set(`${p}.volumes.${j}.target`, 'Enter an absolute path inside the container.')
    })
  })

  for (const [kind, list] of [['networks', state.networks], ['volumes', state.volumes]] as const) {
    const seen = new Set<string>()
    list.forEach((r, i) => {
      const name = r.name.trim()
      if (!NAME.test(name)) set(`${kind}.${i}.name`, nameMsg)
      else if (seen.has(name)) set(`${kind}.${i}.name`, 'Names must be unique.')
      seen.add(name)
      const driver = r.driver.trim()
      if (driver !== '' && !DRIVER.test(driver)) set(`${kind}.${i}.driver`, 'Enter a driver name such as bridge.')
      else if (driver !== '' && r.external) set(`${kind}.${i}.driver`, 'External resources have no driver.')
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
