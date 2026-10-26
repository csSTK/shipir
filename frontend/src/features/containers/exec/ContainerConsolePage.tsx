import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { openTopicChannel, type ConnectionState, type TopicChannel } from '../../../shared/realtime/stomp.ts'
import { Badge, Button, PageHeader, Toolbar } from '../../../shared/ui/index.ts'
import { useContainers } from '../api.ts'
import { describeExecError, useStartExec, type ExecSession, type Shell } from './api.ts'
import { useTranslation } from 'react-i18next'
import './ContainerConsolePage.css'

type Phase = 'idle' | 'starting' | 'connecting' | 'live' | 'ended'

interface Frame {
  seq: number
  type: 'OUTPUT' | 'STATUS'
  data?: string
  status?: 'ENDED' | 'ERROR'
  reason?: string
}

const SHELLS: Shell[] = ['/bin/sh', '/bin/bash']
const encoder = new TextEncoder()

function parseFrame(body: string): Frame | null {
  try {
    const f: unknown = JSON.parse(body)
    if (!f || typeof f !== 'object' || !('seq' in f) || typeof f.seq !== 'number') return null
    if (!('type' in f) || (f.type !== 'OUTPUT' && f.type !== 'STATUS')) return null
    return f as Frame
  } catch {
    return null
  }
}

function decodeBase64(data: string): Uint8Array {
  const raw = atob(data)
  const bytes = new Uint8Array(raw.length)
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i)
  return bytes
}

/** `/environments/:id/containers/:cid/console` (ADMIN): interactive shell in a running container. */
export function ContainerConsolePage() {
  const { t } = useTranslation('containers')
  const { id: hostId = '', cid = '' } = useParams()
  const containers = useContainers(hostId)
  const name = containers.data?.find((c) => c.id === cid)?.name ?? cid.slice(0, 12)
  const start = useStartExec(hostId, cid)

  const [shell, setShell] = useState<Shell>('/bin/sh')
  const [phase, setPhase] = useState<Phase>('idle')
  const [note, setNote] = useState<{ tone: 'info' | 'error'; text: string } | null>(null)

  const hostRef = useRef<HTMLDivElement>(null)
  const termRef = useRef<Terminal | null>(null)
  const fitRef = useRef<FitAddon | null>(null)
  const channelRef = useRef<TopicChannel | null>(null)
  const sessionRef = useRef<ExecSession | null>(null)

  // The terminal lives as long as the page; sessions come and go.
  useEffect(() => {
    const host = hostRef.current
    if (!host) return
    const css = getComputedStyle(host)
    const token = (name: string) => css.getPropertyValue(name).trim() || undefined
    const term = new Terminal({
      cursorBlink: true,
      fontFamily: token('--font-mono') ?? 'monospace',
      fontSize: 13,
      scrollback: 5000,
      theme: { background: token('--color-surface-sunken'), foreground: token('--color-text'), cursor: token('--color-accent') },
    })
    const fit = new FitAddon()
    term.loadAddon(fit)
    term.open(host)
    termRef.current = term
    fitRef.current = fit

    const dataSub = term.onData((data) => {
      const s = sessionRef.current
      if (s) channelRef.current?.publish(s.input, encoder.encode(data))
    })
    const resizeSub = term.onResize(({ cols, rows }) => {
      const s = sessionRef.current
      if (s) channelRef.current?.publish(s.resize, JSON.stringify({ cols, rows }))
    })
    const observer = new ResizeObserver(() => {
      try {
        fit.fit()
      } catch {
        // Host not laid out yet (hidden or zero size): the next observation fits it.
      }
    })
    observer.observe(host)

    return () => {
      observer.disconnect()
      dataSub.dispose()
      resizeSub.dispose()
      channelRef.current?.close()
      channelRef.current = null
      sessionRef.current = null
      term.dispose()
      termRef.current = null
      fitRef.current = null
    }
  }, [])

  function finish(text: string | null, tone: 'info' | 'error' = 'info') {
    channelRef.current?.close()
    channelRef.current = null
    sessionRef.current = null
    setPhase('ended')
    setNote(text ? { tone, text } : null)
  }

  function connect() {
    const term = termRef.current
    const fit = fitRef.current
    if (!term || !fit) return
    try {
      fit.fit()
    } catch {
      // keep the current size
    }
    term.reset()
    setNote(null)
    setPhase('starting')
    start.mutate(
      { shell, cols: Math.min(Math.max(term.cols, 1), 500), rows: Math.min(Math.max(term.rows, 1), 200) },
      {
        onError: (e) => {
          setPhase('idle')
          setNote({ tone: 'error', text: describeExecError(e) })
        },
        onSuccess: (session) => {
          sessionRef.current = session
          setPhase('connecting')
          let lastSeq = -1
          let ended = false
          channelRef.current = openTopicChannel(session.output, {
            onState: (s: ConnectionState) => {
              if (ended) return
              if (s === 'connected') {
                setPhase('live')
                term.focus()
              } else if (s === 'reconnecting') {
                // The server ends the console with the connection, so a new socket would be a new session.
                ended = true
                finish(t('ContainerConsolePage.connectionLostTheConsoleWas'), 'error')
              }
            },
            onMessage: (body) => {
              const frame = parseFrame(body)
              if (!frame || ended || frame.seq <= lastSeq) return
              lastSeq = frame.seq
              if (frame.type === 'OUTPUT') {
                if (frame.data) term.write(decodeBase64(frame.data))
              } else if (frame.status === 'ENDED') {
                ended = true
                finish(t('ContainerConsolePage.theSessionEnded'))
              } else if (frame.status === 'ERROR') {
                ended = true
                finish(t('ContainerConsolePage.theSessionFailedValue', { value: frame.reason ? ` (${frame.reason})` : '' }), 'error')
              }
            },
          })
        },
      },
    )
  }

  const busy = phase === 'starting' || phase === 'connecting'
  const open = busy || phase === 'live'
  const badge =
    phase === 'live' ? (
      <Badge tone="ok">{t('ContainerConsolePage.connected')}</Badge>
    ) : busy ? (
      <Badge tone="muted">{t('ContainerConsolePage.connecting')}</Badge>
    ) : (
      <Badge tone="muted">{phase === 'ended' ? t('ContainerConsolePage.disconnected') : t('ContainerConsolePage.notConnected')}</Badge>
    )

  return (
    <section className="cconsole">
      <Link to={`/environments/${hostId}/containers`} className="cconsole__back">
        <ArrowLeft size={16} strokeWidth={1.75} aria-hidden="true" /> {t('ContainerConsolePage.containers')}
      </Link>
      <PageHeader title={t('ContainerConsolePage.consoleName', { name })} />
      <Toolbar label={t('ContainerConsolePage.consoleOptions')}>
        <label className="cconsole__field">
          <span>{t('ContainerConsolePage.shell')}</span>
          <select value={shell} onChange={(e) => setShell(e.target.value as Shell)} disabled={open} className="cconsole__select">
            {SHELLS.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
        {open ? (
          <Button variant="ghost" onClick={() => finish(t('ContainerConsolePage.disconnected2'))}>
            {t('ContainerConsolePage.disconnect')}
          </Button>
        ) : (
          <Button variant="primary" onClick={connect}>
            {t('ContainerConsolePage.connect')}
          </Button>
        )}
        <div role="status" className="cconsole__state">
          {badge}
        </div>
      </Toolbar>
      {note && (
        <p role={note.tone === 'error' ? 'alert' : undefined} className={note.tone === 'error' ? 'ui-error' : 'cconsole__note'}>
          {note.text}
        </p>
      )}
      <div className="cconsole__frame">
        <div ref={hostRef} className="cconsole__term" role="group" aria-label={t('ContainerConsolePage.terminal')} />
      </div>
    </section>
  )
}
