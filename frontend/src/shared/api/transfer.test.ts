import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from './client.ts'
import { downloadFile, filenameFromDisposition, postForm, postFormJson, saveBlob, uploadMultipart } from './transfer.ts'

let anchor: HTMLAnchorElement | undefined
beforeEach(() => {
  anchor = undefined
  URL.createObjectURL = vi.fn(() => 'blob:x')
  URL.revokeObjectURL = vi.fn()
  const original = document.createElement.bind(document)
  vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
    const el = original(tag)
    if (tag === 'a') {
      anchor = el as HTMLAnchorElement
      vi.spyOn(anchor, 'click').mockImplementation(() => {})
    }
    return el
  })
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

describe('filenameFromDisposition', () => {
  it('reads quoted and bare filenames', () => {
    expect(filenameFromDisposition('attachment; filename="a.tar"')).toBe('a.tar')
    expect(filenameFromDisposition('attachment; filename=b.tar')).toBe('b.tar')
  })
  it('is undefined without a filename', () => {
    expect(filenameFromDisposition('attachment')).toBeUndefined()
    expect(filenameFromDisposition(null)).toBeUndefined()
  })
})

describe('saveBlob', () => {
  it('clicks a download link and releases the object URL after 30s', () => {
    vi.useFakeTimers()
    saveBlob({ blob: new Blob(['x']), filename: 'f.txt' })
    expect(anchor?.download).toBe('f.txt')
    expect(anchor?.click).toHaveBeenCalledOnce()
    expect(URL.revokeObjectURL).not.toHaveBeenCalled()
    vi.advanceTimersByTime(30_000)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:x')
  })
})

describe('downloadFile', () => {
  it('returns the blob and the Content-Disposition filename', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('hi', { headers: { 'Content-Disposition': 'attachment; filename="a.txt"' } })))
    const file = await downloadFile('/api/x')
    expect(file.filename).toBe('a.txt')
    expect(await file.blob.text()).toBe('hi')
  })

  it('sends the CSRF header on POST with a JSON body, none on GET', async () => {
    document.cookie = 'XSRF-TOKEN=tok; path=/'
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(new Response('x')))
    vi.stubGlobal('fetch', fetchMock)
    await downloadFile('/api/b', { method: 'POST', body: { passphrase: 'p' }, accept: 'application/octet-stream' })
    const init = fetchMock.mock.calls[0][1]
    expect(init.method).toBe('POST')
    expect(init.headers).toMatchObject({ 'X-XSRF-TOKEN': 'tok', 'Content-Type': 'application/json', Accept: 'application/octet-stream' })
    expect(init.body).toBe('{"passphrase":"p"}')
    await downloadFile('/api/b')
    expect(fetchMock.mock.calls[1][1].headers).not.toHaveProperty('X-XSRF-TOKEN')
  })

  it('throws ApiError with code, details and Retry-After, using statusText as message', async () => {
    const body = JSON.stringify({ code: 'TOO_MANY', details: ['a'] })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { status: 429, statusText: 'Too Many', headers: { 'Retry-After': '7' } })))
    const error = await downloadFile('/api/x').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 429, code: 'TOO_MANY', details: ['a'], retryAfterSeconds: 7, message: 'Too Many' })
  })

  it('records the request path relative to /api and the server message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: 'No such file' }), { status: 404, statusText: 'Not Found' })))
    const error = await downloadFile('/api/auth/export?x=1').catch((e: unknown) => e)
    expect(error).toMatchObject({ status: 404, path: '/auth/export?x=1', serverMessage: 'No such file' })
  })

  it('keeps no code for a non-JSON error body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>', { status: 502 })))
    await expect(downloadFile('/api/x')).rejects.toMatchObject({ status: 502, code: undefined })
  })
})

class FakeXhr {
  static last: FakeXhr
  status = 0
  responseText = ''
  statusText = ''
  headers: Record<string, string> = {}
  upload: { onprogress?: (e: { lengthComputable: boolean; loaded: number; total: number }) => void } = {}
  onload?: () => void
  onerror?: () => void
  onabort?: () => void
  method = ''
  url = ''
  body: unknown
  constructor() { FakeXhr.last = this }
  open(method: string, url: string) { this.method = method; this.url = url }
  setRequestHeader(k: string, v: string) { this.headers[k] = v }
  send(body: unknown) { this.body = body }
  abort() { this.onabort?.() }
}

describe('uploadMultipart', () => {
  beforeEach(() => vi.stubGlobal('XMLHttpRequest', FakeXhr))
  const messages = { network: 'net', aborted: 'abort' }

  it('sends the form with CSRF header, reports progress and resolves on 2xx', async () => {
    document.cookie = 'XSRF-TOKEN=tok; path=/'
    const onProgress = vi.fn()
    const form = new FormData()
    const { promise } = uploadMultipart('/api/up', form, { method: 'PUT', onProgress, messages })
    expect(FakeXhr.last).toMatchObject({ method: 'PUT', url: '/api/up', body: form })
    expect(FakeXhr.last.headers).toMatchObject({ 'X-XSRF-TOKEN': 'tok', Accept: 'application/json' })
    FakeXhr.last.upload.onprogress?.({ lengthComputable: true, loaded: 1, total: 4 })
    expect(onProgress).toHaveBeenCalledWith(0.25)
    FakeXhr.last.status = 204
    FakeXhr.last.onload?.()
    await expect(promise).resolves.toBeUndefined()
  })

  it('rejects with ApiError carrying the error code', async () => {
    const { promise } = uploadMultipart('/api/up', new FormData(), { messages })
    FakeXhr.last.status = 413
    FakeXhr.last.responseText = JSON.stringify({ code: 'SIZE_LIMIT_EXCEEDED' })
    FakeXhr.last.onload?.()
    await expect(promise).rejects.toMatchObject({ status: 413, code: 'SIZE_LIMIT_EXCEEDED' })
  })

  it('records the path relative to /api and the server message on failure', async () => {
    const { promise } = uploadMultipart('/api/up/here', new FormData(), { messages })
    FakeXhr.last.status = 400
    FakeXhr.last.responseText = JSON.stringify({ message: 'Bad archive' })
    FakeXhr.last.onload?.()
    await expect(promise).rejects.toMatchObject({ path: '/up/here', serverMessage: 'Bad archive' })
  })

  it('rejects with status 0 on network error and ABORTED on abort', async () => {
    const first = uploadMultipart('/api/up', new FormData(), { messages })
    FakeXhr.last.onerror?.()
    await expect(first.promise).rejects.toMatchObject({ status: 0, message: 'net' })
    const second = uploadMultipart('/api/up', new FormData(), { messages })
    second.abort()
    await expect(second.promise).rejects.toMatchObject({ status: 0, code: 'ABORTED', message: 'abort' })
  })
})

describe('postFormJson', () => {
  it('posts the form with CSRF and returns the parsed JSON answer', async () => {
    document.cookie = 'XSRF-TOKEN=tok; path=/'
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ runId: 'r1' }), { status: 202 }))
    vi.stubGlobal('fetch', fetchMock)
    const form = new FormData()
    await expect(postFormJson<{ runId: string }>('/api/x', form)).resolves.toEqual({ runId: 'r1' })
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/x')
    expect(init.method).toBe('POST')
    expect(init.body).toBe(form)
    expect(init.headers['X-XSRF-TOKEN']).toBe('tok')
  })

  it('throws an ApiError with the server code on failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'BUILD_IN_PROGRESS' }), { status: 409 })))
    await expect(postFormJson('/api/x', new FormData())).rejects.toMatchObject({ status: 409, code: 'BUILD_IN_PROGRESS' })
  })

  it('postForm still resolves without a body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))
    await expect(postForm('/api/x', new FormData())).resolves.toBeUndefined()
  })
})
