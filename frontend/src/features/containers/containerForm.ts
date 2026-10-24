import { validateEnv } from '../deployments/editor/envFile.ts'
import type { EnvEntry } from '../deployments/api.ts'
import { CONTAINER_NAME_PATTERN, splitCommand, validatePort, type ContainerSpec, type RestartPolicy } from './api.ts'

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

export interface FormState {
  name: string
  image: string
  command: string
  env: EnvEntry[]
  ports: PortRow[]
  mounts: MountRow[]
  network: string
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
  network: '',
  restartPolicy: 'no',
  privileged: false,
  hostNetwork: false,
  start: true,
}

/** Field problems keyed by a stable id; empty = valid. Mirrors the server rules. */
export function validateContainerForm(f: FormState, isAdmin: boolean): Record<string, string> {
  const e: Record<string, string> = {}
  if (f.name && !CONTAINER_NAME_PATTERN.test(f.name)) e.name = 'Use letters, digits, _ . - and start with a letter or digit (max 128).'
  if (!f.image.trim()) e.image = 'Enter an image, for example nginx:1.27.'
  if (validateEnv(f.env.filter((r) => r.key !== '' || r.value !== '')).size > 0) e.env = 'Fix the variable names.'
  const hostMode = f.hostNetwork || f.network.trim() === 'host'
  f.ports.forEach((p, i) => {
    const hp = validatePort(p.hostPort, true)
    const cp = validatePort(p.containerPort, false)
    if (hp) e[`port${i}host`] = hp
    if (cp) e[`port${i}container`] = cp
  })
  f.mounts.forEach((m, i) => {
    if (!m.source.trim()) e[`mount${i}source`] = 'Required.'
    if (!m.target.startsWith('/')) e[`mount${i}target`] = 'Use an absolute path.'
  })
  if (hostMode && f.ports.length > 0) e.ports = 'Host networking cannot publish ports.'
  if (f.hostNetwork && f.network.trim()) e.network = 'Host networking cannot be combined with a network.'
  if (!isAdmin && f.network.trim() === 'host') e.network = 'Only administrators may use the host network.'
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
    ...(f.network.trim() ? { network: f.network.trim() } : {}),
  }
  if (isAdmin) {
    if (f.privileged) spec.privileged = true
    if (f.hostNetwork) spec.hostNetwork = true
  }
  return spec
}

