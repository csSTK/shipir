import { spawnSync } from 'node:child_process'

import type { PortSource } from './port'

// Free-port lookup for the Playwright configs. Playwright loads a config synchronously, so these run a tiny Node script
// with an argument list (no shell, no curl dependency, same behaviour on every OS).
const FREE_PORT = `
const server = require('node:net').createServer()
server.listen(0, () => { console.log(server.address().port); server.close() })
`

export const systemProbe: PortSource = {
  freePort: () => {
    const result = spawnSync(process.execPath, ['-e', FREE_PORT], { encoding: 'utf8', timeout: 5000 })
    const port = Number(result.stdout.trim())
    if (!Number.isInteger(port) || port < 1) throw new Error('Could not find a free port for the e2e dev server.')
    return port
  },
}
