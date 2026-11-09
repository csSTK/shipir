import { expect, it } from 'vitest'
import * as domains from './index.ts'

it('exports useManagedProxy for other features', () => {
  expect(domains.useManagedProxy).toBeTypeOf('function')
})
