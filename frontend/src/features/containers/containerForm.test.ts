import { describe, expect, it } from 'vitest'
import { sample } from './containerFixture.ts'
import { joinCommand, splitCommand } from './api.ts'
import { formFromDetails } from './containerForm.ts'

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
    expect(f.network).toBe('shop_net')
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
    expect(f.network).toBe('')
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
