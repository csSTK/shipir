import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { downloadLog, LogDownloadError } from './logDownload.ts'

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
})

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

function respond(disposition: string) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('x', { status: 200, headers: { 'Content-Disposition': disposition } })))
}

describe('downloadLog', () => {
  it('saves the response as a file named by Content-Disposition', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('hello', { status: 200, headers: { 'Content-Disposition': 'attachment; filename="web-logs.txt"' } })))
    await downloadLog('/api/x')
    expect(anchor?.click).toHaveBeenCalledOnce()
    expect(URL.createObjectURL).toHaveBeenCalled()
    expect(anchor?.download).toBe('web-logs.txt')
  })

  it.each([
    ['attachment; filename="../x"', 'x'],
    ['attachment; filename="a/b"', 'b'],
    ['attachment; filename="..\\evil.txt"', 'evil.txt'],
    ['attachment; filename="/"', 'container-logs.txt'],
    ['attachment; filename="c:x.txt"', 'cx.txt'],
    ['attachment; filename="a\u0001b.txt"', 'ab.txt'],
    ['attachment', 'container-logs.txt'],
  ])('sanitizes the filename %s', async (disposition, expected) => {
    respond(disposition)
    await downloadLog('/api/x')
    expect(anchor?.download).toBe(expected)
  })

  it('releases the object URL after a while', async () => {
    vi.useFakeTimers()
    respond('attachment; filename="a.txt"')
    await downloadLog('/api/x')
    expect(URL.revokeObjectURL).not.toHaveBeenCalled()
    vi.advanceTimersByTime(30_000)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:x')
  })

  it('throws a LogDownloadError with status and code on 429', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'TOO_MANY_DOWNLOADS' }), { status: 429, headers: { 'Retry-After': '5' } })),
    )
    await expect(downloadLog('/api/x')).rejects.toMatchObject({ status: 429, code: 'TOO_MANY_DOWNLOADS' })
    await expect(downloadLog('/api/x')).rejects.toBeInstanceOf(LogDownloadError)
  })

  it('throws with no code for a non-JSON error body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>', { status: 502 })))
    await expect(downloadLog('/api/x')).rejects.toMatchObject({ status: 502, code: undefined })
  })
})
