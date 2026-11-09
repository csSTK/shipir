/**
 * Reads a Compose file into the wizard form state, or says why it cannot.
 *
 * Lossless or nothing: every key, form and value the file uses must be something the wizard can represent. Anything
 * else (unknown keys, `build`, `deploy`, anchors, long-syntax variants, ...) makes the whole file `ok: false` with a
 * human-readable reason, so an import never silently drops configuration.
 */

import i18n from 'i18next'
import { isAlias, isMap, isScalar, isSeq, parseDocument, visit, type Document } from 'yaml'
import { RESTART_POLICIES, buildSpec, specToState, splitCommand, validateWizard, type TemplateSpecInput, type WizardState } from './model.ts'

export type FromYamlResult = { ok: true; state: WizardState } | { ok: false; reason: string }

type Obj = Record<string, unknown>
type Spec = NonNullable<TemplateSpecInput['services']>[number]

/** Thrown for the first unsupported construct; caught in `fromYaml`, never leaves this module. */
class Unsupported extends Error {}
const fail = (message: string): never => {
  throw new Unsupported(message)
}

/** Failure reason from `deployments:fromYaml.<key>`. */
const tr = (key: string, opts?: Record<string, unknown>): string => i18n.t(`deployments:fromYaml.${key}`, opts)
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)
const SERVICE_KEYS = ['image', 'command', 'restart', 'ports', 'environment', 'volumes', 'networks', 'depends_on']
const KNOWN_UNSUPPORTED = ['build', 'deploy', 'healthcheck', 'extends', 'labels', 'container_name']

function checkKeys(where: string, obj: Obj, allowed: string[]) {
  for (const key of Object.keys(obj)) {
    if (!allowed.includes(key)) fail(tr('keyNotSupported', { where, key }))
  }
}

/** `$$` is a literal dollar; any other `$` is variable interpolation, which the wizard cannot keep. */
function unescapeDollar(where: string, text: string): string {
  if (/\$(?!\$)/.test(text.replace(/\$\$/g, ''))) fail(tr('interpolation', { where }))
  return text.replace(/\$\$/g, '$')
}

function str(where: string, v: unknown): string {
  if (typeof v !== 'string') return fail(tr('expectedText', { where }))
  return unescapeDollar(where, v)
}

function strList(where: string, v: unknown): string[] {
  if (!Array.isArray(v)) return fail(tr('expectedList', { where }))
  return v.map((item) => str(where, item))
}

function parsePort(doc: Document, serviceName: string, index: number, where: string, v: unknown): NonNullable<Spec['ports']>[number] {
  const text = typeof v === 'number' ? String(v) : typeof v === 'string' ? v : fail(tr('portShortOnly', { where }))
  // YAML rewrites numbers (080 -> 80, 8e1 -> 80); keep only what was written, as parseEnv does.
  if (typeof v === 'number') {
    const node = doc.getIn(['services', serviceName, 'ports', index], true)
    const written = isScalar(node) && node.source ? node.source : text
    if (!isScalar(node) || written !== text) return fail(tr('portNumberNotExact', { where, value: written }))
  }
  const m = /^(?:(\d+):)?(\d+)(?:\/(tcp|udp))?$/.exec(text)
  if (!m) return fail(tr('portUnsupported', { where, value: text }))
  return { host: m[1] === undefined ? null : Number(m[1]), container: Number(m[2]), protocol: m[3] ?? 'tcp' }
}

function parseVolume(where: string, v: unknown): NonNullable<Spec['volumes']>[number] {
  if (typeof v !== 'string') return fail(tr('volumeShortOnly', { where }))
  const parts = unescapeDollar(where, v).split(':')
  if (parts.length < 2 || parts.length > 3) return fail(tr('volumeUnsupported', { where, value: v }))
  const mode = parts[2]
  if (mode !== undefined && mode !== 'ro' && mode !== 'rw') return fail(tr('volumeOption', { where, value: mode }))
  return { source: parts[0], target: parts[1], readOnly: mode === 'ro' }
}

function parseEnv(doc: Document, serviceName: string, where: string, v: unknown, envKeys: readonly string[]): NonNullable<Spec['environment']> {
  let pairs: [string, unknown][]
  if (Array.isArray(v)) {
    pairs = v.map((item) => {
      if (typeof item !== 'string' || !item.includes('=')) return fail(tr('envNeedsValue', { where, value: String(item) }))
      const i = (item as string).indexOf('=')
      return [(item as string).slice(0, i), (item as string).slice(i + 1)]
    })
  } else if (isObj(v)) {
    pairs = Object.entries(v)
  } else {
    return fail(tr('expectedMapOrList', { where }))
  }
  return pairs.map(([key, raw]) => {
    if (raw === null || raw === undefined) return fail(tr('envNoValue', { where, key }))
    if (typeof raw !== 'string' && typeof raw !== 'number' && typeof raw !== 'boolean') return fail(tr('envPlainValue', { where, key }))
    const text = String(raw)
    // YAML rewrites numbers (1.0 -> 1, 08080 -> 8080, 0x10 -> 16, huge integers lose digits); keep only what was written.
    if (typeof raw === 'number') {
      const node = doc.getIn(['services', serviceName, 'environment', key], true)
      if (!isScalar(node) || node.source !== text) return fail(tr('envNumberNotExact', { where, key }))
    }
    const ref = /^\$\{([A-Za-z_][A-Za-z0-9_]*)\}$/.exec(text)
    if (ref) {
      if (!envKeys.includes(ref[1])) return fail(tr('envRefUndefined', { where, key, ref: ref[1] }))
      return { key, value: null, ref: ref[1] }
    }
    return { key, value: unescapeDollar(`${where}.${key}`, text), ref: null }
  })
}

/** Compose splits a string command shell-style; an odd number of unescaped quotes of one kind means it is unterminated. */
function hasUnbalancedQuotes(text: string): boolean {
  let quote: string | null = null
  for (const ch of text) {
    if (quote) {
      if (ch === quote) quote = null
    } else if (ch === '"' || ch === "'") quote = ch
  }
  return quote !== null
}

function parseCommand(where: string, v: unknown): string[] {
  if (typeof v === 'string' && (v.includes('\\') || hasUnbalancedQuotes(v))) fail(tr('commandShellSyntax', { where }))
  const args = typeof v === 'string' ? splitCommand(unescapeDollar(where, v)) : strList(where, v)
  if (args.length === 0) fail(tr('commandEmpty', { where }))
  // The form stores the command as one line; it must split back to the same arguments.
  const quote = (a: string) => (a !== '' && !/[\s'"]/.test(a) ? a : a.includes('"') ? `'${a}'` : `"${a}"`)
  if (JSON.stringify(splitCommand(args.map(quote).join(' '))) !== JSON.stringify(args)) fail(tr('commandOneLine', { where }))
  return args
}

function parseService(doc: Document, name: string, raw: unknown, envKeys: readonly string[]): Spec {
  const where = `services.${name}`
  if (!isObj(raw)) return fail(tr('expectedMap', { where }))
  for (const key of KNOWN_UNSUPPORTED) {
    if (key in raw) fail(tr('keyNotSupported', { where, key }))
  }
  checkKeys(where, raw, SERVICE_KEYS)
  if (typeof raw.image !== 'string' || raw.image.trim() === '') fail(tr('imageRequired', { where }))
  const s: Spec = { name, image: str(`${where}.image`, raw.image) }
  if ('command' in raw) s.command = parseCommand(`${where}.command`, raw.command)
  if ('restart' in raw) {
    if (typeof raw.restart !== 'string' || !(RESTART_POLICIES as readonly string[]).includes(raw.restart)) fail(tr('restartUnsupported', { where, value: String(raw.restart) }))
    s.restart = raw.restart as string
  }
  if ('ports' in raw) {
    if (!Array.isArray(raw.ports)) fail(tr('expectedList', { where: `${where}.ports` }))
    s.ports = (raw.ports as unknown[]).map((p, i) => parsePort(doc, name, i, `${where}.ports`, p))
  }
  if ('environment' in raw) s.environment = parseEnv(doc, name, `${where}.environment`, raw.environment, envKeys)
  if ('volumes' in raw) {
    if (!Array.isArray(raw.volumes)) fail(tr('expectedList', { where: `${where}.volumes` }))
    s.volumes = (raw.volumes as unknown[]).map((x) => parseVolume(`${where}.volumes`, x))
  }
  if ('networks' in raw) {
    if (!Array.isArray(raw.networks)) fail(tr('networksPlainList', { where: `${where}.networks` }))
    s.networks = strList(`${where}.networks`, raw.networks)
  }
  if ('depends_on' in raw) {
    if (!Array.isArray(raw.depends_on)) fail(tr('dependsPlainList', { where: `${where}.depends_on` }))
    s.dependsOn = strList(`${where}.depends_on`, raw.depends_on)
  }
  return s
}

function parseResources(kind: 'networks' | 'volumes', raw: unknown): { name: string; driver: string | null; external: boolean }[] {
  if (raw === null || raw === undefined) return []
  if (!isObj(raw)) return fail(tr('expectedMap', { where: kind }))
  return Object.entries(raw).map(([name, cfg]) => {
    const where = `${kind}.${name}`
    if (cfg === null || cfg === undefined) return { name, driver: null, external: false }
    if (!isObj(cfg)) return fail(tr('expectedMap', { where }))
    checkKeys(where, cfg, ['driver', 'external'])
    if ('external' in cfg && typeof cfg.external !== 'boolean') fail(tr('externalBoolean', { where }))
    if ('driver' in cfg && typeof cfg.driver !== 'string') fail(tr('expectedText', { where: `${where}.driver` }))
    return { name, driver: (cfg.driver as string | undefined) ?? null, external: cfg.external === true }
  })
}

/** Normalised view of a spec: what Compose would see, with defaults filled and no wizard-only detail. */
function canonical(spec: TemplateSpecInput) {
  return JSON.stringify({
    services: (spec.services ?? []).map((s) => ({
      name: s.name,
      image: s.image,
      command: s.command?.length ? s.command : null,
      restart: s.restart ?? null,
      ports: (s.ports ?? []).map((p) => [p.host ?? null, p.container, p.protocol ?? 'tcp']),
      environment: (s.environment ?? []).map((e) => (e.ref ? [e.key, null, e.ref] : [e.key, e.value ?? '', null])),
      volumes: (s.volumes ?? []).map((v) => [v.source, v.target, v.readOnly === true]),
      networks: s.networks ?? [],
      dependsOn: s.dependsOn ?? [],
      domain: s.domain ?? null,
    })),
    networks: (spec.networks ?? []).map((n) => [n.name, n.driver ?? null, n.external === true]),
    volumes: (spec.volumes ?? []).map((v) => [v.name, v.driver ?? null, v.external === true]),
  })
}

function convert(yaml: string, envKeys: readonly string[]): WizardState {
  const doc = parseDocument(yaml, { merge: false, uniqueKeys: true })
  if (doc.errors.length > 0) fail(tr('notValidYaml', { detail: doc.errors[0].message.split('\n')[0] }))
  let hasReuse = false
  visit(doc, {
    Alias() {
      hasReuse = true
    },
    Node(_key, node) {
      if ((isScalar(node) || isMap(node) || isSeq(node)) && node.anchor) hasReuse = true
    },
    Pair(_key, pair) {
      if (isScalar(pair.key) && pair.key.value === '<<') hasReuse = true
    },
  })
  if (hasReuse || isAlias(doc.contents)) fail(tr('reuse'))

  const root: unknown = doc.toJS()
  if (!isObj(root)) return fail(tr('notACompose'))
  checkKeys(tr('topLevel'), root, ['services', 'networks', 'volumes'])
  if (!isObj(root.services) || Object.keys(root.services).length === 0) return fail(tr('noServices'))

  const spec: TemplateSpecInput = {
    services: Object.entries(root.services).map(([name, s]) => parseService(doc, name, s, envKeys)),
    networks: parseResources('networks', root.networks),
    volumes: parseResources('volumes', root.volumes),
  }
  const networks = new Set((spec.networks ?? []).map((n) => n.name))
  const services = new Set((spec.services ?? []).map((s) => s.name))
  for (const s of spec.services ?? []) {
    for (const n of s.networks ?? []) if (!networks.has(n)) fail(tr('networkUndeclared', { where: `services.${s.name}.networks`, name: n }))
    for (const d of s.dependsOn ?? []) if (!services.has(d) || d === s.name) fail(tr('dependsUnknown', { where: `services.${s.name}.depends_on`, name: d }))
  }

  const state = specToState(spec)
  const problems = validateWizard(state)
  const first = problems.general[0] ?? [...problems.fields.entries()][0]
  if (first) {
    const [path, msg] = typeof first === 'string' ? ['', first] : first
    fail(path ? tr('withPath', { where: path, message: msg }) : msg)
  }
  // Lossless check: what the wizard would generate must equal what the file says.
  if (canonical(buildSpec(state) as TemplateSpecInput) !== canonical(spec)) fail(tr('wouldChange'))
  return state
}

export function fromYaml(yaml: string, envKeys: readonly string[]): FromYamlResult {
  try {
    return { ok: true, state: convert(yaml, envKeys) }
  } catch (e) {
    if (e instanceof Unsupported) return { ok: false, reason: e.message }
    return { ok: false, reason: tr('unreadable') }
  }
}
