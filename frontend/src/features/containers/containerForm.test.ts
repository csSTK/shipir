import { describe, expect, it } from 'vitest'
import { sample } from './containerFixture.ts'
import { joinCommand, splitCommand } from './api.ts'
import { anonymousVolumeTargets, buildContainerSpec, formFromDetails, validateContainerForm } from './containerForm.ts'

describe('formFromDetails', () => {
  it('prefills an edit from inspect with the cmd only, without tmpfs mounts or duplicate port bindings', () => {
    const f = formFromDetails(sample, 'edit')
    expect(f.name).toBe('web')
    expect(f.image).toBe('nginx:1')
    expect(f.command).toBe('nginx -g "daemon off;"')
    expect(f.mounts).toEqual([
      { type: 'VOLUME', source: 'data', target: '/data', readOnly: false },
      { type: 'BIND', source: '/srv/conf', target: '/conf', readOnly: true },
    ])
    expect(f.ports).toEqual([{ hostPort: '8080', containerPort: '80', protocol: 'tcp' }])
    expect(f.networks).toEqual([{ name: 'shop_net', aliases: '' }])
    expect(f.restartPolicy).toBe('unless-stopped')
    expect(f.env).toEqual([{ key: 'MODE', value: 'prod' }])
    expect(f.start).toBe(true)
  })

  it('clears the name and published host ports for a duplicate', () => {
    const f = formFromDetails(sample, 'duplicate')
    expect(f.name).toBe('')
    expect(f.ports).toEqual([{ hostPort: '', containerPort: '80', protocol: 'tcp' }])
  })

  it('carries over privileged and host network, and no network name for host mode', () => {
    const f = formFromDetails({ ...sample, privileged: true, hostNetwork: true, networks: [{ ...sample.networks[0], name: 'host' }], ports: [] }, 'edit')
    expect(f.privileged).toBe(true)
    expect(f.hostNetwork).toBe(true)
    expect(f.networks).toEqual([])
  })
})

const net = (name: string, aliases: string[] = []) => ({ name, ipAddress: null, gateway: null, macAddress: null, aliases })

describe('networks', () => {
  it('keeps every network with its aliases, minus the container id alias', () => {
    const id = 'a1b2c3d4e5f6a7b8c9d0'
    const f = formFromDetails({ ...sample, id, networks: [net('back'), net('front', ['a1b2c3d4e5f6', 'www', 'shop'])] }, 'edit')
    expect(f.networks).toEqual([
      { name: 'back', aliases: '' },
      { name: 'front', aliases: 'www, shop' },
    ])
  })

  it('treats a container only on the default bridge as having no network', () => {
    expect(formFromDetails({ ...sample, networks: [net('bridge')] }, 'edit').networks).toEqual([])
  })

  it('keeps bridge when another network is attached too', () => {
    const f = formFromDetails({ ...sample, networks: [net('bridge'), net('shop_net')] }, 'edit')
    expect(f.networks.map((n) => n.name)).toEqual(['bridge', 'shop_net'])
  })

  it('builds the spec with all networks and split aliases', () => {
    const f = { ...formFromDetails(sample, 'edit'), networks: [{ name: ' a ', aliases: 'x, y ,,' }, { name: 'b', aliases: '' }] }
    expect(buildContainerSpec(f, false).networks).toEqual([{ name: 'a', aliases: ['x', 'y'] }, { name: 'b', aliases: [] }])
  })

  it('rejects empty and duplicate network names', () => {
    const f = { ...formFromDetails(sample, 'edit'), networks: [{ name: '', aliases: '' }] }
    expect(validateContainerForm(f, false).network).toBeTruthy()
    const dup = { ...f, networks: [{ name: 'a', aliases: '' }, { name: 'a', aliases: '' }] }
    expect(validateContainerForm(dup, false).network).toBeTruthy()
  })

  it('still gates the host network and refuses it with other networks', () => {
    const host = { ...formFromDetails(sample, 'edit'), networks: [{ name: 'host', aliases: '' }] }
    expect(validateContainerForm(host, false).network).toBeTruthy()
    expect(validateContainerForm(host, true).network).toBeUndefined()
    expect(validateContainerForm({ ...host, networks: [{ name: 'host', aliases: '' }, { name: 'a', aliases: '' }] }, true).network).toBeTruthy()
  })
})

describe('anonymous volumes', () => {
  const hash = 'a'.repeat(64)
  const withAnon = { ...sample, mounts: [...sample.mounts, { type: 'volume', name: hash, source: '/var/lib/docker/volumes/x/_data', destination: '/var/lib/x', readOnly: false }] }

  it('are not offered as named mounts, but listed by their path', () => {
    expect(formFromDetails(withAnon, 'edit').mounts.map((m) => m.target)).toEqual(['/data', '/conf'])
    expect(anonymousVolumeTargets(withAnon)).toEqual(['/var/lib/x'])
  })

  it('a named volume is not anonymous', () => {
    expect(anonymousVolumeTargets(sample)).toEqual([])
  })
})

describe('command round trip', () => {
  const cases: [string, string[]][] = [
    ['embedded double quotes', ['sh', '-c', 'echo "hi" && x']],
    ['embedded single quotes', ['sh', '-c', "echo 'hi there'"]],
    ['an empty argument', ['sh', '-c', '']],
    ['a quoted-looking argument', ['\'a\'', '"b"']],
    ['backslashes', ['C:\\dir\\x', 'a\\ b', 'end\\']],
    ['plain words', ['nginx', '-g', 'daemon off;']],
  ]
  it.each(cases)('splitCommand inverts joinCommand for %s', (_name, args) => {
    expect(splitCommand(joinCommand(args))).toEqual(args)
  })

  it.each(cases)('an unchanged edit keeps the cmd for %s', (_name, args) => {
    expect(splitCommand(formFromDetails({ ...sample, cmd: args }, 'edit').command)).toEqual(args)
  })
})
