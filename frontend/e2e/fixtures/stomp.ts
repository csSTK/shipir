import type { Page } from '@playwright/test'

const stompFrame = (command: string, headers: Record<string, string>, body = '') =>
  `${command}\n${Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n${body}\0`

/** Minimal STOMP broker: records SUBSCRIBE headers and lets the test push frames. */
export async function mockBroker(
  page: Page,
  onSubscribe: (send: (body: object) => void, headers: Record<string, string>) => void,
  options: { refuse?: boolean } = {},
) {
  const subscribes: Record<string, string>[] = []
  await page.routeWebSocket('**/ws', (ws) => {
    ws.onMessage((raw) => {
      const text = String(raw)
      if (text.startsWith('CONNECT') || text.startsWith('STOMP')) {
        ws.send(stompFrame('CONNECTED', { version: '1.2', 'heart-beat': '0,0' }))
      } else if (text.startsWith('SUBSCRIBE') && !text.includes('/events') && !text.includes('/stats')) {
        const headers = Object.fromEntries(
          text.split('\n').slice(1).map((l) => l.split(':')).filter((p) => p.length >= 2).map(([k, ...v]) => [k, v.join(':')]),
        )
        subscribes.push(headers)
        if (options.refuse) {
          ws.send(stompFrame('ERROR', { message: 'Access denied' }))
          return
        }
        onSubscribe(
          (body) => ws.send(stompFrame('MESSAGE', { subscription: headers.id ?? '0', destination: headers.destination ?? '', 'message-id': String(Math.random()) }, JSON.stringify(body))),
          headers,
        )
      }
    })
  })
  return subscribes
}

