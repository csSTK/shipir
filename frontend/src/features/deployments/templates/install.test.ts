import { describe, expect, it } from 'vitest'
import { initialState } from '../wizard/model.ts'
import type { AppTemplate } from './api.ts'
import { generateSecret, initialEnv, matchContainer, webTarget } from './install.ts'

function template(env: AppTemplate['env']): AppTemplate {
  return { id: 't', name: 'T', description: 'd', env, spec: { services: [] } }
}

describe('generateSecret', () => {
  it('defaults to at least 32 characters (BookStack APP_KEY needs exactly 32)', () => {
    expect(generateSecret().length).toBeGreaterThanOrEqual(32)
  })

  it('honours an explicit length', () => {
    expect(generateSecret(12)).toHaveLength(12)
  })
})

describe('initialEnv', () => {
  it('generates a secret with the length pinned in the hint, independent of the default', () => {
    const env = initialEnv(
      template([{ key: 'APP_KEY', description: 'k', secret: true, generate: true, length: 32 }]),
      initialState(),
    )
    expect(env[0].value).toHaveLength(32)
  })

  it('uses a shorter pinned length too', () => {
    const env = initialEnv(
      template([{ key: 'K', description: 'k', secret: true, generate: true, length: 20 }]),
      initialState(),
    )
    expect(env[0].value).toHaveLength(20)
  })
})



const svc = (name: string, ports: { host: number | null; container: number; protocol?: string }[]) => ({ name, image: 'x', ports, environment: [] })
const tpl = (services: ReturnType<typeof svc>[]) => ({ id: 't', name: 'T', description: '', env: [], spec: { services, networks: [], volumes: [] } }) as unknown as AppTemplate

describe('webTarget', () => {
  it('picks the first published TCP port of the first service that has one', () => {
    const t = tpl([svc('db', []), svc('web', [{ host: 8080, container: 80 }, { host: 9000, container: 9000 }])])
    expect(webTarget(t)).toEqual({ service: 'web', port: 80 })
  })

  it('skips UDP and unpublished ports', () => {
    const t = tpl([svc('dns', [{ host: 53, container: 53, protocol: 'udp' }]), svc('app', [{ host: null, container: 1 }, { host: 3000, container: 3000, protocol: 'tcp' }])])
    expect(webTarget(t)).toEqual({ service: 'app', port: 3000 })
  })

  it('is null when nothing is published', () => {
    expect(webTarget(tpl([svc('worker', [])]))).toBeNull()
  })
})

describe('matchContainer', () => {
  const names = (...n: string[]) => n.map((containerName) => ({ containerName }))

  it('finds the compose container of the service within the project', () => {
    expect(matchContainer(names('blog-db-1', 'blog-wp-1', 'other-wp-1'), 'blog', 'wp')).toBe('blog-wp-1')
  })

  it('accepts the legacy underscore naming', () => {
    expect(matchContainer(names('blog_wp_1'), 'blog', 'wp')).toBe('blog_wp_1')
  })

  it('does not match a project that merely starts with the name', () => {
    expect(matchContainer(names('blog-2-wp-1'), 'blog', 'wp')).toBeNull()
  })

  it('is null while the container does not exist yet', () => {
    expect(matchContainer([], 'blog', 'wp')).toBeNull()
  })
})
