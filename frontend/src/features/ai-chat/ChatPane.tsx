import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Check, Clock, Pencil, Send, Trash2, Wrench, X } from 'lucide-react'
import { Button, ConfirmDialog, EmptyState, Skeleton, Switch } from '../../shared/ui/index.ts'
import { ApiError } from '../../shared/api/client.ts'
import {
  useChatSession,
  useDeleteChatSession,
  useRefreshSession,
  useResolveAction,
  useSendChatMessage,
  useUpdateChatSession,
  type ChatSession,
} from './api.ts'
import { MessageList } from './MessageList.tsx'
import { ViewContextChip } from './ViewContextChip.tsx'
import { useViewContext } from './useViewContext.ts'
import { toolLabel } from './toolLabel.ts'
import { IDLE_STREAM, useChatDock } from './useChatDock.ts'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'
import './ChatPane.css'

const SEND_ERRORS: Record<string, string> = {
  get CHAT_RUN_IN_PROGRESS() { return i18n.t('ai-chat:ChatPane.theAssistantIsStillReplying') },
  get ACTION_PENDING() { return i18n.t('ai-chat:ChatPane.confirmOrRejectThePending') },
}

function Header({ session }: { session: ChatSession }) {
  const { t } = useTranslation('ai-chat')
  const dock = useChatDock()
  const update = useUpdateChatSession(session.id)
  const remove = useDeleteChatSession(session.id)
  const navigate = useNavigate()
  const [renaming, setRenaming] = useState(false)
  const [title, setTitle] = useState(session.title)
  const [confirmingDelete, setConfirmingDelete] = useState(false)

  function rename(e: FormEvent) {
    e.preventDefault()
    const next = title.trim()
    if (next === '' || next === session.title) return setRenaming(false)
    update.mutate({ title: next }, { onSuccess: () => setRenaming(false) })
  }

  return (
    <header className="chat-header">
      <div className="chat-header__row">
        {renaming ? (
          <form onSubmit={rename} className="chat-rename">
            <input aria-label={t('ChatPane.chatTitle')} value={title} maxLength={255} onChange={(e) => setTitle(e.target.value)} autoFocus />
            <Button type="submit" variant="primary" size="sm" disabled={update.isPending}>
              {t('ChatPane.save')}
            </Button>
            <Button size="sm" onClick={() => setRenaming(false)}>
              {t('ChatPane.cancel')}
            </Button>
          </form>
        ) : (
          <>
            <h2 className="chat-header__title">{session.title}</h2>
            <Button
              size="sm"
              onClick={() => {
                setTitle(session.title)
                setRenaming(true)
              }}
            >
              <Pencil size={16} strokeWidth={1.75} aria-hidden="true" />
              {t('ChatPane.rename')}
            </Button>
            <Button size="sm" onClick={() => setConfirmingDelete(true)}>
              <Trash2 size={16} strokeWidth={1.75} aria-hidden="true" />
              {t('ChatPane.deleteChat')}
            </Button>
          </>
        )}
      </div>
      <Switch
        label={t('ChatPane.shareWithAdmins')}
        checked={session.sharedWithAdmin}
        disabled={update.isPending}
        onChange={() => update.mutate({ sharedWithAdmin: !session.sharedWithAdmin })}
      />
      <p className="chat-muted">{t('ChatPane.adminsCanReadAShared')}</p>
      {(update.isError || remove.isError) && (
        <p role="alert" className="chat-error">
          {t('ChatPane.couldNotSaveTheChange')}
        </p>
      )}
      <ConfirmDialog
        open={confirmingDelete}
        onOpenChange={setConfirmingDelete}
        title={t('ChatPane.deleteThisChat')}
        description={t('ChatPane.theConversationAndItsHistory')}
        confirmLabel={t('ChatPane.delete')}
        danger
        loading={remove.isPending}
        onConfirm={() =>
          remove.mutate(undefined, {
            onSuccess: () => {
              dock.forget(session.id)
              navigate('/assistant')
            },
          })
        }
      />
    </header>
  )
}

/** One open chat: header, history, live stream and composer. Mount with `key={sessionId}`. `compact` (the dock) drops the header, its title and actions live on the full page. */
export function ChatPane({ sessionId, isAdmin, compact = false }: { sessionId: string; isAdmin: boolean; compact?: boolean }) {
  const { t } = useTranslation('ai-chat')
  const detail = useChatSession(sessionId)
  const refresh = useRefreshSession(sessionId)
  const send = useSendChatMessage(sessionId)
  const resolve = useResolveAction(sessionId)
  const dock = useChatDock()
  const { open, view } = dock
  // The run state lives in the dock provider, so leaving this page does not stop a running answer.
  useEffect(() => {
    open(sessionId)
    return view(sessionId)
  }, [sessionId, open, view])
  const stream = dock.sessionId === sessionId ? dock.stream : IDLE_STREAM
  const viewing = useViewContext()
  // Removing the chip drops the context for the next message only; it is keyed to the page so another page shows it again.
  const [dismissed, setDismissed] = useState<string | null>(null)
  const viewKey = viewing.context ? JSON.stringify(viewing.context) : null
  const showChip = viewing.context !== null && viewing.label !== null && dismissed !== viewKey
  const [draft, setDraft] = useState('')
  const [optimistic, setOptimistic] = useState<{ text: string; baseCount: number } | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [notConfigured, setNotConfigured] = useState(false)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [actionErrors, setActionErrors] = useState<Record<string, string>>({})

  if (detail.isPending)
    return (
      <div aria-busy="true" className="chat-loading">
        <Skeleton height="1.75rem" width="50%" />
        <Skeleton height="4rem" />
        <Skeleton height="4rem" width="70%" />
      </div>
    )
  if (detail.isError) {
    const gone = detail.error instanceof ApiError && detail.error.status === 404
    return (
      <p role="alert" className="chat-error">
        {gone ? t('ChatPane.thisChatNoLongerExists') : t('ChatPane.couldNotLoadThisChat')}
      </p>
    )
  }

  const { session, messages } = detail.data
  const hasPending = messages.some((m) => m.status === 'PENDING')
  const blocked = stream.running || hasPending || stream.connection !== 'connected'

  function submit(e: FormEvent) {
    e.preventDefault()
    const content = draft.trim()
    if (content === '' || blocked) return
    setProblem(null)
    setDraft('')
    setOptimistic({ text: content, baseCount: messages.length })
    setDismissed(null)
    stream.start()
    send.mutate({ content, context: showChip ? viewing.context ?? undefined : undefined }, {
      onError: (err) => {
        setOptimistic(null)
        stream.stop()
        setDraft(content)
        if (err instanceof ApiError && err.code === 'AI_NOT_CONFIGURED') setNotConfigured(true)
        else setProblem((err instanceof ApiError && err.code && SEND_ERRORS[err.code]) || t('ChatPane.couldNotSendTheMessage'))
      },
    })
  }

  function onResolve(messageId: string, decision: 'confirm' | 'reject') {
    setBusyId(messageId)
    setActionErrors(({ [messageId]: _drop, ...rest }) => rest)
    resolve.mutate(
      { messageId, decision },
      {
        onSuccess: (r) => {
          if (r.runId) stream.start()
          if (r.error) setActionErrors((prev) => ({ ...prev, [messageId]: r.result ?? t('ChatPane.theActionFailed') }))
          void refresh()
        },
        onError: (err) => {
          const stale = err instanceof ApiError && err.code === 'ACTION_NOT_PENDING'
          setActionErrors((prev) => ({ ...prev, [messageId]: stale ? t('ChatPane.thisActionWasAlreadyResolved') : t('ChatPane.couldNotSendYourDecision') }))
          if (stale) void refresh()
        },
        onSettled: () => setBusyId(null),
      },
    )
  }

  const live = (
    <>
      {optimistic && optimistic.baseCount === messages.length && <p className="chat-bubble chat-bubble--user">{optimistic.text}</p>}
      {stream.activity.map((a, i) => (
        <p key={i} className={`chat-activity chat-activity--${a.state}`}>
          {a.state === 'error' ? <X size={16} strokeWidth={1.75} aria-hidden="true" /> : a.state === 'ok' ? <Check size={16} strokeWidth={1.75} aria-hidden="true" /> : <Wrench size={16} strokeWidth={1.75} aria-hidden="true" />}
          {a.state === 'running' ? t('ChatPane.using') : a.state === 'ok' ? t('ChatPane.used') : t('ChatPane.failedToUse')} {toolLabel(a.tool)}
        </p>
      ))}
      {stream.text && <p className="chat-bubble chat-bubble--assistant">{stream.text}</p>}
      {stream.rateLimitWait !== null && (
        <p className="chat-thinking chat-thinking--wait" role="status">
          <Clock size={16} strokeWidth={1.75} aria-hidden="true" />
          <span>{t('ChatPane.rateLimitReached')}</span>
          {/* Only the static part is announced; the ticking countdown is visual. */}
          <span aria-hidden="true">{stream.rateLimitWait > 0 ? t('ChatPane.retryingInRatelimitwaitS', { rateLimitWait: stream.rateLimitWait }) : t('ChatPane.retrying')}</span>
        </p>
      )}
      {stream.running && !stream.text && stream.rateLimitWait === null && <p className="chat-thinking" role="status">{t('ChatPane.thinking')}</p>}
    </>
  )

  return (
    <div className="chat-pane">
      {!compact && <Header key={`${session.id}:${session.title}`} session={session} />}
      <MessageList messages={messages} busyMessageId={busyId} actionErrors={actionErrors} onResolve={onResolve} live={live} />
      {notConfigured && (
        <div role="status">
          <EmptyState
            title={t('ChatPane.theAssistantIsNotSet')}
            description={isAdmin ? t('ChatPane.addAnAiProviderFirst') : t('ChatPane.askAnAdministratorToConfigure')}
            action={isAdmin ? <Link to="/settings/ai">{t('ChatPane.openAiSettings')}</Link> : undefined}
          />
        </div>
      )}
      {showChip && viewing.label && <ViewContextChip label={viewing.label} onRemove={() => setDismissed(viewKey)} />}
      <form className="chat-composer" onSubmit={submit}>
        <label className="chat-composer__label" htmlFor="chat-input">
          {t('ChatPane.message')}
        </label>
        <textarea
          id="chat-input"
          rows={2}
          value={draft}
          disabled={blocked}
          placeholder={hasPending ? t('ChatPane.confirmOrRejectTheAction') : t('ChatPane.askAboutYourEnvironmentsOr')}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              e.currentTarget.form?.requestSubmit()
            }
          }}
        />
        <Button type="submit" variant="primary" disabled={blocked || draft.trim() === ''}>
          <Send size={16} strokeWidth={1.75} aria-hidden="true" />
          {t('ChatPane.send')}
        </Button>
      </form>
      {problem && (
        <p role="alert" className="chat-error">
          {problem}
        </p>
      )}
    </div>
  )
}
