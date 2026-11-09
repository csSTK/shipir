import { describe, expect, it } from 'vitest'
import { COMPOSE_FILE_MATCH } from './composeFileMatch.ts'

describe('COMPOSE_FILE_MATCH', () => {
  it('scopes the Compose schema to compose files, not every model', () => {
    expect(COMPOSE_FILE_MATCH).not.toContain('*')
    expect(COMPOSE_FILE_MATCH).not.toContain('**')
    expect(COMPOSE_FILE_MATCH).toEqual(expect.arrayContaining(['**/compose.yaml', '**/docker-compose.yml']))
  })
})
