import type { ContainerDetails } from './details/api.ts'

/** Inspect data shared by the edit/duplicate tests. */
export const sample: ContainerDetails = {
  id: 'c1',
  name: 'web',
  image: 'nginx:1',
  state: 'running',
  created: '2026-10-01T10:00:00Z',
  startedAt: '2026-10-01T10:00:05Z',
  restartCount: 0,
  restartPolicy: { name: 'unless-stopped', maxRetries: 0 },
  command: ['/docker-entrypoint.sh', 'nginx', '-g', 'daemon off;'],
  cmd: ['nginx', '-g', 'daemon off;'],
  privileged: false,
  hostNetwork: false,
  env: [{ key: 'MODE', value: 'prod' }],
  mounts: [
    { type: 'volume', name: 'data', source: '/var/lib/docker/volumes/data/_data', destination: '/data', readOnly: false },
    { type: 'bind', name: null, source: '/srv/conf', destination: '/conf', readOnly: true },
    { type: 'bind', name: null, source: '', destination: '/run/tmp', readOnly: false },
  ],
  labels: {},
  networks: [{ name: 'shop_net', ipAddress: null, gateway: null, macAddress: null, aliases: [] }],
  ports: [
    { containerPort: 80, protocol: 'tcp', hostIp: '0.0.0.0', hostPort: 8080 },
    { containerPort: 80, protocol: 'tcp', hostIp: '::', hostPort: 8080 },
    { containerPort: 443, protocol: 'tcp', hostIp: null, hostPort: null },
  ],
  health: null,
  composeProject: null,
  composeService: null,
}
