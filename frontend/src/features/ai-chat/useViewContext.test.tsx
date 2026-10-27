import { renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { parseViewContext, useViewContext } from './useViewContext.ts'

const HOST = '11111111-1111-1111-1111-111111111111'
const DEPLOYMENT = '22222222-2222-2222-2222-222222222222'
const CID = 'a1b2c3d4e5f6'

describe('parseViewContext', () => {
  it.each([
    ['/', { page: 'OVERVIEW' }],
    [`/environments/${HOST}`, { page: 'HOST', hostId: HOST }],
    [`/environments/${HOST}/deployments`, { page: 'DEPLOYMENT', hostId: HOST }],
    [`/environments/${HOST}/deployments/${DEPLOYMENT}`, { page: 'DEPLOYMENT', hostId: HOST, deploymentId: DEPLOYMENT }],
    [`/environments/${HOST}/containers`, { page: 'CONTAINER', hostId: HOST }],
    [`/environments/${HOST}/containers/${CID}`, { page: 'CONTAINER', hostId: HOST, containerId: CID }],
    [`/environments/${HOST}/containers/${CID}/logs`, { page: 'CONTAINER', hostId: HOST, containerId: CID }],
    [`/environments/${HOST}/containers/${CID}/console`, { page: 'CONTAINER', hostId: HOST, containerId: CID }],
    [`/environments/${HOST}/images`, { page: 'IMAGES', hostId: HOST }],
    [`/environments/${HOST}/networks`, { page: 'NETWORKS', hostId: HOST }],
    [`/environments/${HOST}/volumes`, { page: 'VOLUMES', hostId: HOST }],
    ['/settings/users', { page: 'SETTINGS' }],
  ])('maps %s', (path, expected) => {
    expect(parseViewContext(path)).toEqual(expected)
  })

  it('omits a container name because the server only accepts hex ids', () => {
    expect(parseViewContext(`/environments/${HOST}/containers/web-1`)).toEqual({ page: 'CONTAINER', hostId: HOST })
  })

  it('omits ids that are not UUIDs', () => {
    expect(parseViewContext('/environments/not-a-uuid/images')).toEqual({ page: 'IMAGES' })
    expect(parseViewContext(`/environments/${HOST}/deployments/nope`)).toEqual({ page: 'DEPLOYMENT', hostId: HOST })
  })

  it.each(['/assistant', '/assistant/s1', '/account', '/notifications', '/nowhere'])('sends no context for %s', (path) => {
    expect(parseViewContext(path)).toBeNull()
  })
})

describe('useViewContext', () => {
  function run(path: string, seed: (c: QueryClient) => void = () => {}) {
    const client = new QueryClient()
    seed(client)
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>{children}</MemoryRouter>
      </QueryClientProvider>
    )
    return renderHook(() => useViewContext(), { wrapper }).result.current
  }

  it('labels a deployment page with the cached deployment name', () => {
    const r = run(`/environments/${HOST}/deployments/${DEPLOYMENT}`, (c) => {
      c.setQueryData(['hosts', HOST, 'managed-deployments'], [{ id: DEPLOYMENT, name: 'shop' }])
    })
    expect(r.context).toEqual({ page: 'DEPLOYMENT', hostId: HOST, deploymentId: DEPLOYMENT })
    expect(r.label).toBe('shop')
  })

  it('labels a host page with the cached host name', () => {
    const r = run(`/environments/${HOST}`, (c) => c.setQueryData(['hosts'], [{ id: HOST, name: 'Production' }]))
    expect(r.label).toBe('Production')
  })

  it('labels a container page with the cached container name', () => {
    const r = run(`/environments/${HOST}/containers/${CID}`, (c) =>
      c.setQueryData(['hosts', HOST, 'containers'], [{ id: `${CID}ffffffffffffffffffffffffffffffffffffffffffffffffffff`, name: 'web-1' }]),
    )
    expect(r.label).toBe('web-1')
  })

  it('falls back to the page name when nothing is cached', () => {
    expect(run('/').label).toBe('Environments')
    expect(run(`/environments/${HOST}/images`).label).toBe('Images')
  })

  it('has no context or label on other pages', () => {
    expect(run('/account')).toEqual({ context: null, label: null })
  })
})
