import { describe, expect, it } from 'vitest'
import { initialState } from '../wizard/model.ts'
import type { AppTemplate } from './api.ts'
import { generateSecret, initialEnv } from './install.ts'

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
