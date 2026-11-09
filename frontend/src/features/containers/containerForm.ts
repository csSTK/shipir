import { validateEnv } from '../deployments/editor/envFile.ts'
import type { ContainerDetails } from './details/api.ts'
import type { EnvEntry } from '../deployments/api.ts'
import { CONTAINER_NAME_PATTERN, joinCommand, splitCommand, validatePort, type ContainerSpec, type RestartPolicy } from './api.ts'
import i18n from 'i18next'

export interface PortRow {
  hostPort: string
  containerPort: string
  protocol: 'tcp' | 'udp'
}
export interface MountRow {
  type: 'VOLUME' | 'BIND'
  source: string
  target: string
  readOnly: boolean
}

/** One attached network; `aliases` is a comma-separated list of extra DNS names on it. */
export interface NetworkRow {
  name: string
  aliases: string
}

export interface FormState {
  name: string
  image: string
  command: string
  env: EnvEntry[]
  ports: PortRow[]
  mounts: MountRow[]
  networks: NetworkRow[]
  restartPolicy: RestartPolicy
  privileged: boolean
  hostNetwork: boolean
  start: boolean
}

export const INITIAL_CONTAINER_FORM: FormState = {
  name: '',
  image: '',
  command: '',
  env: [],
  ports: [],
  mounts: [],
  networks: [],
  restartPolicy: 'no',
  privileged: false,
  hostNetwork: false,
  start: true,
}

/** Field problems keyed by a stable id; empty = valid. Mirrors the server rules. */
export function validateContainerForm(f: FormState, isAdmin: boolean, requireName = false): Record<string, string> {
  const e: Record<string, string> = {}
  if (requireName && !f.name.trim()) e.name = i18n.t('containers:containerForm.enterANameForThe')
  if (f.name && !CONTAINER_NAME_PATTERN.test(f.name)) e.name = i18n.t('containers:containerForm.useLettersDigitsAndStart')
  if (!f.image.trim()) e.image = i18n.t('containers:containerForm.enterAnImageForExample')
  if (validateEnv(f.env.filter((r) => r.key !== '' || r.value !== '')).size > 0) e.env = i18n.t('containers:containerForm.fixTheVariableNames')
  const names = f.networks.map((n) => n.name.trim())
  const hostMode = f.hostNetwork || names.includes('host')
  f.ports.forEach((p, i) => {
    const hp = validatePort(p.hostPort, true)
    const cp = validatePort(p.containerPort, false)
    if (hp) e[`port${i}host`] = hp
    if (cp) e[`port${i}container`] = cp
  })
  f.mounts.forEach((m, i) => {
    if (!m.source.trim()) e[`mount${i}source`] = i18n.t('containers:containerForm.required')
    if (!m.target.startsWith('/')) e[`mount${i}target`] = i18n.t('containers:containerForm.useAnAbsolutePath')
  })
  if (hostMode && f.ports.length > 0) e.ports = i18n.t('containers:containerForm.hostNetworkingCannotPublishPorts')
  if (names.some((n) => !n)) e.network = i18n.t('containers:containerForm.enterANetworkName')
  else if (new Set(names).size < names.length) e.network = i18n.t('containers:containerForm.eachNetworkOnlyOnce')
  else if ((f.hostNetwork && names.length > 0) || (names.includes('host') && names.length > 1)) e.network = i18n.t('containers:containerForm.hostNetworkingCannotBeCombined')
  else if (!isAdmin && names.includes('host')) e.network = i18n.t('containers:containerForm.onlyAdministratorsMayUseThe')
  return e
}

export function buildContainerSpec(f: FormState, isAdmin: boolean): ContainerSpec {
  const env = Object.fromEntries(f.env.filter((r) => r.key.trim()).map((r) => [r.key.trim(), r.value]))
  const command = splitCommand(f.command)
  const spec: ContainerSpec = {
    image: f.image.trim(),
    restartPolicy: f.restartPolicy,
    start: f.start,
    ...(f.name.trim() ? { name: f.name.trim() } : {}),
    ...(command.length ? { command } : {}),
    ...(Object.keys(env).length ? { env } : {}),
    ...(f.ports.length
      ? { ports: f.ports.map((p) => ({ hostPort: p.hostPort.trim() ? Number(p.hostPort) : null, containerPort: Number(p.containerPort), protocol: p.protocol })) }
      : {}),
    ...(f.mounts.length ? { mounts: f.mounts.map((m) => ({ ...m, source: m.source.trim() })) } : {}),
    ...(f.networks.length
      ? { networks: f.networks.map((n) => ({ name: n.name.trim(), aliases: n.aliases.split(',').map((a) => a.trim()).filter(Boolean) })) }
      : {}),
  }
  if (isAdmin) {
    if (f.privileged) spec.privileged = true
    if (f.hostNetwork) spec.hostNetwork = true
  }
  return spec
}


type DetailMount = ContainerDetails['mounts'][number]

/** Docker names the volumes it creates for an image's VOLUME declarations with a 64-character hex id. */
const ANONYMOUS_VOLUME_NAME = /^[0-9a-f]{64}$/

/** True when a volume name is one of Docker's anonymous (64-hex) volume ids. */
export function isAnonymousVolumeName(name: string): boolean {
  return ANONYMOUS_VOLUME_NAME.test(name)
}

function isAnonymousVolume(m: DetailMount): boolean {
  return m.type === 'volume' && m.name != null && isAnonymousVolumeName(m.name)
}

/** Mount paths of the container's anonymous volumes: the backend carries them over on edit, so they are not form rows. */
export function anonymousVolumeTargets(d: ContainerDetails): string[] {
  return d.mounts.filter(isAnonymousVolume).map((m) => m.destination)
}

/**
 * All attached networks except the host mode. A lone default bridge means "no network chosen". Docker lists the short
 * container id as an alias on every network; it is dropped as it is not a user choice.
 */
function networksFromDetails(d: ContainerDetails): NetworkRow[] {
  const nets = d.networks.filter((n) => n.name !== 'host')
  if (nets.length === 1 && nets[0].name === 'bridge') return []
  return nets.map((n) => ({
    name: n.name,
    aliases: n.aliases.filter((a) => !(a.length >= 12 && d.id.startsWith(a))).join(', '),
  }))
}

export type ContainerFormMode = 'create' | 'edit' | 'duplicate'

/**
 * Inspect env contains the variables the image bakes in. Posting those back would pin the old image's defaults
 * over a newer image's, so only variables absent from the image env or with a different value are kept.
 */
function overridesOfImageEnv(d: ContainerDetails): EnvEntry[] {
  const baked = new Map((d.imageEnv ?? []).map((e) => [e.key, e.value]))
  return d.env
    .filter((e) => !baked.has(e.key) || baked.get(e.key) !== e.value)
    .map((e) => ({ key: e.key, value: e.value ?? '' }))
}

/**
 * Prefill from inspect. The command field takes the configured cmd only (not the entrypoint, which the backend
 * carries over). Anonymous tmpfs mounts (inspect labels them bind without source) are dropped.
 * A duplicate gets no name and no fixed host ports, as both would collide with the original.
 */
export function formFromDetails(d: ContainerDetails, mode: 'edit' | 'duplicate'): FormState {
  const seen = new Set<string>()
  const ports: PortRow[] = []
  for (const p of d.ports) {
    if (p.hostPort == null) continue
    const key = `${p.protocol}/${p.containerPort}/${p.hostPort}`
    if (seen.has(key)) continue
    seen.add(key)
    ports.push({ hostPort: mode === 'edit' ? String(p.hostPort) : '', containerPort: String(p.containerPort), protocol: p.protocol === 'udp' ? 'udp' : 'tcp' })
  }
  const mounts: MountRow[] = d.mounts.flatMap((m): MountRow[] => {
    if (isAnonymousVolume(m)) return []
    if (m.type === 'volume' && m.name) return [{ type: 'VOLUME', source: m.name, target: m.destination, readOnly: m.readOnly }]
    if (m.type === 'bind' && m.source) return [{ type: 'BIND', source: m.source, target: m.destination, readOnly: m.readOnly }]
    return []
  })
  const policy = d.restartPolicy.name
  return {
    ...INITIAL_CONTAINER_FORM,
    name: mode === 'edit' ? d.name : '',
    image: d.image,
    command: joinCommand(d.cmd),
    privileged: d.privileged,
    hostNetwork: d.hostNetwork,
    env: overridesOfImageEnv(d),
    ports,
    mounts,
    networks: networksFromDetails(d),
    restartPolicy: policy === 'always' || policy === 'unless-stopped' || policy === 'on-failure' ? policy : 'no',
    start: mode === 'edit' ? d.state === 'running' : true,
  }
}
