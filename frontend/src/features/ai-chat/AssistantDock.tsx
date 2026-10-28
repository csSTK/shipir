import { useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import { Maximize2, Plus, Sparkles, X } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Button, EmptyState, IconButton, springSmooth } from '../../shared/ui/index.ts'
import { useChatSessions, useCreateChatSession } from './api.ts'
import { ChatPane } from './ChatPane.tsx'
import { useChatDock } from './useChatDock.ts'
import './AssistantDock.css'

type DockStatus = 'idle' | 'running' | 'pending' | 'ready'

const fade = { type: 'tween', duration: 0.12, ease: 'linear' } as const

function DockPanel({ isAdmin, narrow, onClose }: { isAdmin: boolean; narrow: boolean; onClose: () => void }) {
  const { t } = useTranslation('ai-chat')
  const dock = useChatDock()
  const reduced = useReducedMotion()
  const sessions = useChatSessions()
  const create = useCreateChatSession()
  const panel = useRef<HTMLDivElement>(null)
  // Focus moves into the panel when it opens, so keyboard users land in the conversation.
  useEffect(() => panel.current?.focus(), [])

  const hidden = reduced ? { opacity: 0 } : narrow ? { opacity: 0, y: '100%' } : { opacity: 0, scale: 0.92, y: 12 }
  const shown = reduced ? { opacity: 1 } : narrow ? { opacity: 1, y: 0 } : { opacity: 1, scale: 1, y: 0 }

  // Document level: sending disables the composer and drops focus to the body, where a panel listener would miss Escape.
  // Something inside that already handled the key (a dialog, an open popup) calls preventDefault and keeps it.
  const closeRef = useRef(onClose)
  closeRef.current = onClose
  useEffect(() => {
    const onKeyDown = (e: globalThis.KeyboardEvent) => {
      if (e.key === 'Escape' && !e.defaultPrevented) closeRef.current()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [])

  const newChat = () => create.mutate(undefined, { onSuccess: (s) => dock.open(s.id) })

  return (
    <motion.div
      ref={panel}
      role="dialog"
      aria-label={t('AssistantDock.assistant')}
      tabIndex={-1}
      className={`assistant-dock__panel${narrow ? ' assistant-dock__panel--sheet' : ''}`}
      initial={hidden}
      animate={shown}
      exit={hidden}
      transition={reduced ? fade : springSmooth}
    >
      <header className="assistant-dock__header">
        <select
          className="assistant-dock__picker"
          aria-label={t('AssistantDock.chat')}
          value={dock.sessionId ?? ''}
          onChange={(e) => dock.open(e.target.value)}
        >
          {dock.sessionId === null && (
            <option value="" disabled>
              {t('AssistantDock.chooseChat')}
            </option>
          )}
          {sessions.data?.items.map((s) => (
            <option key={s.id} value={s.id}>
              {s.title}
            </option>
          ))}
        </select>
        <IconButton icon={X} aria-label={t('AssistantDock.closeAssistant')} onClick={onClose} />
      </header>
      <div className="assistant-dock__actions">
        <Button size="sm" onClick={newChat} disabled={create.isPending} loading={create.isPending}>
          <Plus size={16} strokeWidth={1.75} aria-hidden="true" />
          {t('AssistantDock.newChat')}
        </Button>
        <Link to={dock.sessionId ? `/assistant/${dock.sessionId}` : '/assistant'} className="assistant-dock__full" onClick={onClose}>
          <Maximize2 size={16} strokeWidth={1.75} aria-hidden="true" />
          {t('AssistantDock.openFullSize')}
        </Link>
      </div>
      {create.isError && (
        <p role="alert" className="chat-error">
          {t('AssistantDock.couldNotStartChat')}
        </p>
      )}
      <div className="assistant-dock__body">
        {dock.sessionId ? (
          <ChatPane key={dock.sessionId} sessionId={dock.sessionId} isAdmin={isAdmin} compact />
        ) : (
          <EmptyState icon={Sparkles} title={t('AssistantDock.startTitle')} description={t('AssistantDock.startDescription')} />
        )}
      </div>
    </motion.div>
  )
}

/**
 * Floating assistant: a round icon at the bottom right that opens a docked chat panel (a bottom sheet on narrow
 * screens). It shows the dock provider's active session, so the panel and the `/assistant` page share the same chats.
 * Hidden on `/assistant`, where the full page is the chat.
 */
export function AssistantDock({ isAdmin, narrow }: { isAdmin: boolean; narrow: boolean }) {
  const { t } = useTranslation('ai-chat')
  const { pathname } = useLocation()
  const [open, setOpen] = useState(false)
  const icon = useRef<HTMLButtonElement>(null)
  const dock = useChatDock()
  const onChatPage = pathname === '/assistant' || pathname.startsWith('/assistant/')
  // The full page is the chat there, so the panel does not wait to pop open again afterwards.
  if (onChatPage && open) setOpen(false)
  if (onChatPage) return null

  const close = () => {
    setOpen(false)
    icon.current?.focus()
  }

  // While the panel is open the user sees the chat itself, so the icon only reports status when it is closed.
  const status: DockStatus = open ? 'idle' : dock.stream.running || dock.runningElsewhere ? 'running' : dock.pendingCount > 0 ? 'pending' : dock.unread ? 'ready' : 'idle'
  const statusText =
    status === 'running'
      ? t('AssistantDock.statusRunning')
      : status === 'pending'
        ? t('AssistantDock.statusPending', { count: dock.pendingCount })
        : status === 'ready'
          ? t('AssistantDock.statusReady')
          : ''

  return (
    <div className="assistant-dock">
      <AnimatePresence>{open && <DockPanel isAdmin={isAdmin} narrow={narrow} onClose={close} />}</AnimatePresence>
      <button
        ref={icon}
        type="button"
        className={`assistant-dock__icon${open ? ' assistant-dock__icon--away' : ''}`}
        aria-label={t('AssistantDock.openAssistant')}
        aria-expanded={open}
        aria-describedby="assistant-dock-status"
        data-status={status}
        tabIndex={open ? -1 : 0}
        onClick={() => setOpen(true)}
      >
        {status === 'running' ? (
          <span className="assistant-dock__spinner" aria-hidden="true" />
        ) : (
          <Sparkles size={24} strokeWidth={1.75} aria-hidden="true" />
        )}
        {status === 'pending' && (
          <span className="assistant-dock__badge" aria-hidden="true">
            {dock.pendingCount > 99 ? '99+' : dock.pendingCount}
          </span>
        )}
        {status === 'ready' && <span className="assistant-dock__dot" aria-hidden="true" />}
      </button>
      {/* Polite live region (no role=status, so it never collides with page-level status queries): always mounted so changes are announced; it also describes the icon. */}
      <p id="assistant-dock-status" data-testid="assistant-dock-status" aria-live="polite" aria-atomic="true" className="assistant-dock__live">
        {statusText}
      </p>
    </div>
  )
}
