import { describe, expect, it } from 'vitest'
import { buildSpec, emptyService, referencedEnvKeys, specToState, splitCommand, validatePort, validateWizard, type ServiceForm, type WizardState } from './model.ts'

function service(patch: Partial<ServiceForm>): ServiceForm {
  return { ...emptyService(), name: 'web', image: 'nginx:1.27', ...patch }
}
const state = (services: ServiceForm[], extra: Partial<WizardState> = {}): WizardState => ({ services, networks: [], volumes: [], ...extra })

describe('validatePort', () => {
  it.each([['80', false, null], ['', false, null], ['65535', true, null]])('accepts %j', (v, req, out) => {
    expect(validatePort(v, req)).toBe(out)
  })
  it.each(['0', '65536', 'abc', '-1', '80.5'])('rejects %j', (v) => {
    expect(validatePort(v, true)).toMatch(/1 to 65535/)
  })
  it('requires the container port', () => {
    expect(validatePort(' ', true)).toBe('Enter a port.')
  })
})

describe('validateWizard', () => {
  it('accepts a minimal valid deployment', () => {
    const p = validateWizard(state([service({})]))
    expect(p.fields.size).toBe(0)
    expect(p.general).toEqual([])
  })
  it('flags bad and duplicate service names', () => {
    const p = validateWizard(state([service({ name: 'Web App' }), service({ name: 'db' }), service({ name: 'db' })]))
    expect(p.fields.get('services.0.name')).toMatch(/lowercase/)
    expect(p.fields.get('services.2.name')).toMatch(/unique/)
    expect(p.fields.has('services.1.name')).toBe(false)
  })
  it('requires an image without whitespace', () => {
    expect(validateWizard(state([service({ image: '' })])).fields.has('services.0.image')).toBe(true)
    expect(validateWizard(state([service({ image: 'a b' })])).fields.has('services.0.image')).toBe(true)
  })
  it('flags port ranges', () => {
    const p = validateWizard(state([service({ ports: [{ host: '70000', container: '80', protocol: 'tcp' }, { host: '', container: '', protocol: 'udp' }] })]))
    expect(p.fields.get('services.0.ports.0.host')).toMatch(/1 to 65535/)
    expect(p.fields.get('services.0.ports.1.container')).toBe('Enter a port.')
    expect(p.fields.has('services.0.ports.0.container')).toBe(false)
  })
  it('flags env keys, duplicates and bad references', () => {
    const p = validateWizard(
      state([service({ env: [{ key: '1A', value: 'x', ref: false }, { key: 'A', value: '', ref: false }, { key: 'A', value: 'ok', ref: false }, { key: 'B', value: 'not valid', ref: true }] })]),
    )
    expect(p.fields.has('services.0.env.0.key')).toBe(true)
    expect(p.fields.has('services.0.env.1.key')).toBe(false)
    expect(p.fields.get('services.0.env.2.key')).toMatch(/more than once/)
    expect(p.fields.has('services.0.env.3.value')).toBe(true)
  })
  it('checks volume sources and targets', () => {
    const p = validateWizard(
      state(
        [service({ volumes: [
          { source: 'data', target: '/data', readOnly: false },
          { source: 'missing', target: '/m', readOnly: false },
          { source: './conf', target: 'etc', readOnly: true },
          { source: '/etc', target: '/x', readOnly: false },
          { source: '../up', target: '/x', readOnly: false },
        ] })],
        { volumes: [{ name: 'data', driver: '', external: false }] },
      ),
    )
    expect(p.fields.has('services.0.volumes.0.source')).toBe(false)
    expect(p.fields.get('services.0.volumes.1.source')).toMatch(/Declare/)
    expect(p.fields.has('services.0.volumes.2.source')).toBe(false)
    expect(p.fields.get('services.0.volumes.2.target')).toMatch(/absolute/)
    expect(p.fields.has('services.0.volumes.3.source')).toBe(true)
    expect(p.fields.has('services.0.volumes.4.source')).toBe(true)
  })
  it('flags top-level names, duplicates and driver on external', () => {
    const p = validateWizard(
      state([service({})], { networks: [{ name: 'Bad', driver: '', external: false }, { name: 'ok', driver: 'bridge', external: true }, { name: 'ok', driver: '', external: false }] }),
    )
    expect(p.fields.has('networks.0.name')).toBe(true)
    expect(p.fields.get('networks.1.driver')).toMatch(/External/)
    expect(p.fields.get('networks.2.name')).toMatch(/unique/)
  })
  it('requires a service', () => {
    expect(validateWizard(state([])).general).toEqual(['Add at least one service.'])
  })
})

describe('splitCommand', () => {
  it('splits on whitespace and keeps quoted groups', () => {
    expect(splitCommand('  sh -c "echo hi there" \'a b\' ""')).toEqual(['sh', '-c', 'echo hi there', 'a b', ''])
    expect(splitCommand('')).toEqual([])
  })
})

describe('buildSpec', () => {
  it('maps the form to the request payload', () => {
    const s = state(
      [
        service({
          name: ' web ',
          command: 'nginx -g "daemon off;"',
          restart: 'unless-stopped',
          ports: [{ host: '8080', container: '80', protocol: 'tcp' }, { host: '', container: '53', protocol: 'udp' }],
          env: [{ key: 'MODE', value: 'prod', ref: false }, { key: 'TOKEN', value: 'API_TOKEN', ref: true }],
          volumes: [{ source: 'data', target: '/data', readOnly: true }],
          networks: ['front', 'gone'],
          dependsOn: ['db', 'web', 'ghost'],
        }),
        service({ name: 'db', image: 'postgres:16' }),
      ],
      { networks: [{ name: 'front', driver: '', external: false }], volumes: [{ name: 'data', driver: 'local', external: false }] },
    )
    expect(buildSpec(s)).toEqual({
      services: [
        {
          name: 'web',
          image: 'nginx:1.27',
          command: ['nginx', '-g', 'daemon off;'],
          restart: 'unless-stopped',
          ports: [{ host: 8080, container: 80, protocol: 'tcp' }, { host: null, container: 53, protocol: 'udp' }],
          environment: [{ key: 'MODE', value: 'prod', ref: null }, { key: 'TOKEN', value: null, ref: 'API_TOKEN' }],
          volumes: [{ source: 'data', target: '/data', readOnly: true }],
          networks: ['front'],
          labels: null,
          dependsOn: ['db'],
        },
        { name: 'db', image: 'postgres:16', command: null, restart: null, ports: [], environment: [], volumes: [], networks: [], labels: null, dependsOn: [] },
      ],
      networks: [{ name: 'front', driver: null, external: false }],
      volumes: [{ name: 'data', driver: 'local', external: false }],
    })
  })
  it('lists referenced env keys once', () => {
    const s = state([service({ env: [{ key: 'A', value: 'K', ref: true }, { key: 'B', value: 'K', ref: true }, { key: 'C', value: 'lit', ref: false }] })])
    expect(referencedEnvKeys(s)).toEqual(['K'])
  })
})

describe('specToState', () => {
  it('is the inverse of buildSpec for a full spec', () => {
    const original = state(
      [
        service({
          name: 'web',
          command: 'sh -c "echo hi"',
          restart: 'always',
          ports: [{ host: '8080', container: '80', protocol: 'udp' }, { host: '', container: '443', protocol: 'tcp' }],
          env: [{ key: 'A', value: 'x', ref: false }, { key: 'B', value: 'DB_PASSWORD', ref: true }],
          volumes: [{ source: 'data', target: '/data', readOnly: true }],
          networks: ['front'],
          dependsOn: ['db'],
        }),
        service({ name: 'db', image: 'postgres:16' }),
      ],
      { networks: [{ name: 'front', driver: 'bridge', external: false }], volumes: [{ name: 'data', driver: '', external: true }] },
    )
    const spec = buildSpec(original)
    const back = specToState(spec)
    expect(buildSpec(back)).toEqual(spec)
    expect(validateWizard(back).fields.size).toBe(0)
    expect(referencedEnvKeys(back)).toEqual(['DB_PASSWORD'])
  })
  it('tolerates missing optional fields and keeps an empty form usable', () => {
    const s = specToState({ services: [{ name: 'a', image: 'x' }] })
    expect(s.services[0]).toMatchObject({ command: '', restart: '', ports: [], env: [], networks: [] })
    expect(specToState({}).services).toHaveLength(1)
  })
})
