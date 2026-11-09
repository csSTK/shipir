import { describe, expect, it } from 'vitest'
import { fromYaml } from './fromYaml.ts'
import { buildSpec, type WizardState } from './model.ts'

/** Same shape the backend generator writes (see ComposeGeneratorServiceTest golden file). */
const GENERATED = `services:
  web:
    image: nginx:1.27
    command:
      - nginx
      - -g
      - daemon off;
    restart: unless-stopped
    ports:
      - 8080:80/tcp
      - 53/udp
    environment:
      APP_ENV: production
      DB_PASSWORD: "\${DB_PASSWORD}"
      PRICE: $$5
    volumes:
      - ./html:/usr/share/nginx/html:ro
      - cache:/cache
    networks:
      - front
    depends_on:
      - db
  db:
    image: postgres:16
    volumes:
      - pgdata:/var/lib/postgresql/data
networks:
  front:
    driver: bridge
  shared:
    external: true
volumes:
  pgdata: {}
  cache:
    driver: local
`

function ok(yaml: string, envKeys: string[] = []): WizardState {
  const r = fromYaml(yaml, envKeys)
  if (!r.ok) throw new Error(`expected ok, got: ${r.reason}`)
  return r.state
}
function reason(yaml: string, envKeys: string[] = []): string {
  const r = fromYaml(yaml, envKeys)
  if (r.ok) throw new Error('expected failure')
  return r.reason
}

describe('fromYaml round trip', () => {
  it('maps generated YAML onto the wizard state', () => {
    const spec = buildSpec(ok(GENERATED, ['DB_PASSWORD']))
    expect(spec.services.map((s) => s.name)).toEqual(['web', 'db'])
    const web = spec.services[0]
    expect(web.command).toEqual(['nginx', '-g', 'daemon off;'])
    expect(web.restart).toBe('unless-stopped')
    expect(web.ports).toEqual([
      { host: 8080, container: 80, protocol: 'tcp' },
      { host: null, container: 53, protocol: 'udp' },
    ])
    expect(web.environment).toEqual([
      { key: 'APP_ENV', value: 'production', ref: null },
      { key: 'DB_PASSWORD', value: null, ref: 'DB_PASSWORD' },
      { key: 'PRICE', value: '$5', ref: null },
    ])
    expect(web.volumes).toEqual([
      { source: './html', target: '/usr/share/nginx/html', readOnly: true },
      { source: 'cache', target: '/cache', readOnly: false },
    ])
    expect(web.networks).toEqual(['front'])
    expect(web.dependsOn).toEqual(['db'])
    expect(spec.networks).toEqual([
      { name: 'front', driver: 'bridge', external: false },
      { name: 'shared', driver: null, external: true },
    ])
    expect(spec.volumes).toEqual([
      { name: 'pgdata', driver: null, external: false },
      { name: 'cache', driver: 'local', external: false },
    ])
  })

  it('accepts a minimal service', () => {
    const spec = buildSpec(ok('services:\n  app:\n    image: alpine\n'))
    expect(spec.services[0]).toMatchObject({ name: 'app', image: 'alpine', command: null, restart: null, ports: [], environment: [] })
  })
})

describe('fromYaml typical Portainer stacks', () => {
  it('parses image, ports, env list, volumes, restart, networks and depends_on', () => {
    const yaml = `
services:
  app:
    image: ghcr.io/acme/app:2
    restart: always
    ports:
      - "3000:3000"
      - 9000
    environment:
      - NODE_ENV=production
      - URL=http://x/?a=b
    volumes:
      - data:/data
      - ./conf:/etc/app:ro
    networks:
      - backend
    depends_on:
      - redis
  redis:
    image: redis:7
    restart: unless-stopped
    networks:
      - backend
networks:
  backend:
volumes:
  data:
`
    const spec = buildSpec(ok(yaml))
    expect(spec.services[0].ports).toEqual([
      { host: 3000, container: 3000, protocol: 'tcp' },
      { host: null, container: 9000, protocol: 'tcp' },
    ])
    expect(spec.services[0].environment).toEqual([
      { key: 'NODE_ENV', value: 'production', ref: null },
      { key: 'URL', value: 'http://x/?a=b', ref: null },
    ])
    expect(spec.networks).toEqual([{ name: 'backend', driver: null, external: false }])
    expect(spec.volumes).toEqual([{ name: 'data', driver: null, external: false }])
  })

  it('accepts numeric and boolean env values and a string command', () => {
    const spec = buildSpec(ok('services:\n  a:\n    image: x\n    command: run --flag "a b"\n    environment:\n      N: 5\n      B: true\n'))
    expect(spec.services[0].command).toEqual(['run', '--flag', 'a b'])
    expect(spec.services[0].environment).toEqual([
      { key: 'N', value: '5', ref: null },
      { key: 'B', value: 'true', ref: null },
    ])
  })

  it('treats ${KEY} as a .env reference only for known keys', () => {
    const yaml = 'services:\n  a:\n    image: x\n    environment:\n      TOKEN: ${TOKEN}\n'
    expect(buildSpec(ok(yaml, ['TOKEN'])).services[0].environment).toEqual([{ key: 'TOKEN', value: null, ref: 'TOKEN' }])
    expect(reason(yaml, [])).toMatch(/TOKEN/)
  })
})

describe('fromYaml unsupported constructs', () => {
  const svc = (extra: string) => `services:\n  a:\n    image: x\n${extra}`
  it.each([
    ['build', '    build: .\n', /build/],
    ['deploy', '    deploy:\n      replicas: 2\n', /deploy/],
    ['healthcheck', '    healthcheck:\n      test: ["CMD", "true"]\n', /healthcheck/],
    ['extends', '    extends:\n      service: b\n', /extends/],
    ['labels', '    labels:\n      a: b\n', /labels/],
    ['container_name', '    container_name: foo\n', /container_name/],
    ['unknown key', '    cap_add: [NET_ADMIN]\n', /cap_add/],
    ['long-syntax port', '    ports:\n      - target: 80\n        published: 8080\n', /ports/],
    ['port with host ip', '    ports:\n      - "127.0.0.1:80:80"\n', /ports/],
    ['port range', '    ports:\n      - "8000-8010:8000-8010"\n', /ports/],
    ['long-syntax volume', '    volumes:\n      - type: bind\n        source: ./a\n        target: /a\n', /volumes/],
    ['volume option z', '    volumes:\n      - ./a:/a:z\n', /volumes/],
    ['anonymous volume', '    volumes:\n      - /data\n', /volumes/],
    ['network with config', '    networks:\n      back:\n        aliases: [x]\n', /networks/],
    ['depends_on condition', '    depends_on:\n      b:\n        condition: service_healthy\n', /depends_on/],
    ['restart with count', '    restart: on-failure:3\n', /restart/],
    ['env without value', '    environment:\n      - FOO\n', /FOO/],
    ['env null value', '    environment:\n      FOO:\n', /FOO/],
    ['interpolation in env', '    environment:\n      A: prefix-${X}\n', /\$/],
    ['interpolation in image', '', /\$/],
  ])('rejects %s', (name, extra, re) => {
    const yaml = name === 'interpolation in image' ? 'services:\n  a:\n    image: x:${TAG}\n' : svc(extra)
    expect(reason(yaml, ['X'])).toMatch(re)
  })

  it('rejects an undeclared network and an unknown depends_on target', () => {
    expect(reason(svc('    networks: [nope]\n'))).toMatch(/nope/)
    expect(reason(svc('    depends_on: [ghost]\n'))).toMatch(/ghost/)
  })

  it('rejects top-level keys the wizard lacks', () => {
    expect(reason('version: "3.8"\nservices:\n  a:\n    image: x\n')).toMatch(/version/)
    expect(reason('services:\n  a:\n    image: x\nconfigs:\n  c:\n    file: ./c\nsecrets: {}\n')).toMatch(/configs/)
  })

  it('rejects top-level network/volume options the wizard lacks', () => {
    expect(reason('services:\n  a:\n    image: x\nnetworks:\n  n:\n    ipam:\n      driver: default\n')).toMatch(/ipam/)
    expect(reason('services:\n  a:\n    image: x\nvolumes:\n  v:\n    driver_opts:\n      type: nfs\n')).toMatch(/driver_opts/)
  })

  it('rejects anchors, aliases and merge keys', () => {
    expect(reason('x-base: &base\n  image: x\nservices:\n  a:\n    <<: *base\n')).toMatch(/anchor|alias|merge/i)
    expect(reason('services:\n  a: &a\n    image: x\n  b: *a\n')).toMatch(/anchor|alias|merge/i)
  })

  it('rejects absolute bind paths, which the wizard rejects too', () => {
    expect(reason('services:\n  a:\n    image: x\n    volumes:\n      - /srv/data:/data\n')).toMatch(/path|inside/i)
  })

  it('rejects a file without services', () => {
    expect(reason('services: {}\n')).toMatch(/service/)
    expect(reason('networks: {}\n')).toMatch(/services/)
  })
})

describe('fromYaml values kept exactly as written', () => {
  const env = (value: string) => `services:\n  a:\n    image: x\n    environment:\n      N: ${value}\n`
  it.each(['1.0', '08080', '0x10', '12345678901234567890', '1e3', '.5'])('rejects env number %s, which YAML would rewrite', (v) => {
    expect(reason(env(v))).toMatch(/N/)
  })
  it.each(['5', '8080', '-3', 'true'])('keeps env value %s', (v) => {
    expect(buildSpec(ok(env(v))).services[0].environment).toEqual([{ key: 'N', value: v, ref: null }])
  })
  it('keeps quoted numbers as the text written', () => {
    expect(buildSpec(ok(env('"08080"'))).services[0].environment).toEqual([{ key: 'N', value: '08080', ref: null }])
  })
})

describe('fromYaml ports kept exactly as written', () => {
  const port = (value: string) => `services:\n  a:\n    image: x\n    ports:\n      - ${value}\n`
  it.each(['080', '8e1'])('rejects unquoted port %s, which YAML would rewrite', (v) => {
    expect(reason(port(v))).toMatch(new RegExp(v))
  })
  it.each(['080', '8e1'])('explains unquoted port %s with the number message, not the unsupported-form message', (v) => {
    expect(reason(port(v))).toMatch(/is a number YAML would rewrite/)
  })
  it('keeps a plain decimal port', () => {
    expect(buildSpec(ok(port('8080'))).services[0].ports).toEqual([{ host: null, container: 8080, protocol: 'tcp' }])
  })
})

describe('fromYaml string commands', () => {
  const cmd = (value: string) => `services:\n  a:\n    image: x\n    command: ${value}\n`
  it.each([String.raw`echo a\ b`, `echo "unclosed`, `echo 'unclosed`])('rejects shell syntax the wizard would split differently: %s', (v) => {
    expect(reason(cmd(`'${v.replace(/'/g, "''")}'`))).toMatch(/command/)
  })
  it('keeps a backslash in the exec (list) form, which is not shell-split', () => {
    const yaml = 'services:\n  a:\n    image: x\n    command: ["echo", "a\\\\b"]\n'
    expect(buildSpec(ok(yaml)).services[0].command).toEqual(['echo', 'a\\b'])
  })
})

describe('fromYaml messages', () => {
  it('are translated texts with the offending path, not translation keys', () => {
    const r = reason('services:\n  a:\n    image: x\n    build: .\n')
    expect(r).toBe("services.a: 'build' is not supported by the wizard.")
  })
})

describe('fromYaml malformed input', () => {
  it.each([
    ['empty', ''],
    ['scalar', 'hello'],
    ['list', '- a\n- b\n'],
    ['bad indentation', 'services:\n  a:\n    image: x\n   bad: y\n'],
    ['unclosed flow', 'services: {a: [\n'],
    ['duplicate key', 'services:\n  a:\n    image: x\n    image: y\n'],
    ['two documents', 'services:\n  a:\n    image: x\n---\nservices:\n  b:\n    image: y\n'],
    ['services not a map', 'services: nope\n'],
    ['service not a map', 'services:\n  a: nope\n'],
    ['missing image', 'services:\n  a:\n    restart: always\n'],
  ])('returns ok:false without throwing for %s', (_n, yaml) => {
    const r = fromYaml(yaml, [])
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.reason.length).toBeGreaterThan(0)
  })
})
