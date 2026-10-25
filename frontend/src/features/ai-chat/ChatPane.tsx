import { useState, type FormEvent } from 'react'
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
import { toolLabel } from './toolLabel.ts'
import { useChatStream } from './useChatStream.ts'

const SEND_ERRORS: Record<string, string> = {
  CHAT_RUN_IN_PROGRESS: 'The assistant is still replying. Wait for it to finish.',
  ACTION_PENDING: 'Confirm or reject the pending action first.',
}

function Header({ session }: { session: ChatSession }) {
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
            <input aria-label="Chat title" value={title} maxLength={255} onChange={(e) => setTitle(e.target.value)} autoFocus />
            <Button type="submit" variant="primary" size="sm" disabled={update.isPending}>
              Save
            </Button>
            <Button size="sm" onClick={() => setRenaming(false)}>
              Cancel
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
              Rename
            </Button>
            <Button size="sm" onClick={() => setConfirmingDelete(true)}>
              <Trash2 size={16} strokeWidth={1.75} aria-hidden="true" />
              Delete chat
            </Button>
          </>
        )}
      </div>
      <Switch
        label="Share with admins"
        checked={session.sharedWithAdmin}
        disabled={update.isPending}
        onChange={() => update.mutate({ sharedWithAdmin: !session.sharedWithAdmin })}
      />
      <p className="chat-muted">Admins can read a shared chat, read-only, to help debug problems. Turn it off at any time.</p>
      {(update.isError || remove.isError) && (
        <p role="alert" className="chat-error">
          Could not save the change. Try again.
        </p>
      )}
      <ConfirmDialog
        open={confirmingDelete}
        onOpenChange={setConfirmingDelete}
        title="Delete this chat?"
        description="The conversation and its history are removed for good."
        confirmLabel="Delete"
        danger
        loading={remove.isPending}
        onConfirm={() => remove.mutate(undefined, { onSuccess: () => navigate('/assistant') })}
      />
    </header>
  )
}

/** One open chat: header, history, live stream and composer. Mount with `key={sessionId}`. */
export function ChatPane({ sessionId, isAdmin }: { sessionId: string; isAdmin: boolean }) {
  const detail = useChatSession(sessionId)
  const refresh = useRefreshSession(sessionId)
  const send = useSendChatMessage(sessionId)
  const resolve = useResolveAction(sessionId)
  const stream = useChatStream(sessionId, refresh)
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
        {gone ? 'This chat no longer exists.' : 'Could not load this chat.'}
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
    stream.start()
    send.mutate(content, {
      onError: (err) => {
        setOptimistic(null)
        stream.stop()
        setDraft(content)
        if (err instanceof ApiError && err.code === 'AI_NOT_CONFIGURED') setNotConfigured(true)
        else setProblem((err instanceof ApiError && err.code && SEND_ERRORS[err.code]) || 'Could not send the message. Try again.')
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
          if (r.error) setActionErrors((prev) => ({ ...prev, [messageId]: r.result ?? 'The action failed.' }))
          void refresh()
        },
        onError: (err) => {
          const stale = err instanceof ApiError && err.code === 'ACTION_NOT_PENDING'
          setActionErrors((prev) => ({ ...prev, [messageId]: stale ? 'This action was already resolved.' : 'Could not send your decision. Try again.' }))
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
          {a.state === 'running' ? 'Using' : a.state === 'ok' ? 'Used' : 'Failed to use'} {toolLabel(a.tool)}
        </p>
      ))}
      {stream.text && <p className="chat-bubble chat-bubble--assistant">{stream.text}</p>}
      {stream.rateLimitWait !== null && (
        <p className="chat-thinking chat-thinking--wait" role="status">
          <Clock size={16} strokeWidth={1.75} aria-hidden="true" />
          <span>Rate limit reached</span>
          {/* Only the static part is announced; the ticking countdown is visual. */}
          <span aria-hidden="true">{stream.rateLimitWait > 0 ? `, retrying in ${stream.rateLimitWait}s` : ', retrying…'}</span>
        </p>
      )}
      {stream.running && !stream.text && stream.rateLimitWait === null && <p className="chat-thinking" role="status">Thinking…</p>}
    </>
  )

  return (
    <div className="chat-pane">
      <Header key={`${session.id}:${session.title}`} session={session} />
      <MessageList messages={messages} busyMessageId={busyId} actionErrors={actionErrors} onResolve={onResolve} live={live} />
      {notConfigured && (
        <div role="status">
          <EmptyState
            title="The assistant is not set up yet."
            description={isAdmin ? 'Add an AI provider first.' : 'Ask an administrator to configure the AI provider.'}
            action={isAdmin ? <Link to="/settings/ai">Open AI settings</Link> : undefined}
          />
        </div>
      )}
      <form className="chat-composer" onSubmit={submit}>
        <label className="chat-composer__label" htmlFor="chat-input">
          Message
        </label>
        <textarea
          id="chat-input"
          rows={2}
          value={draft}
          disabled={blocked}
          placeholder={hasPending ? 'Confirm or reject the action above to continue' : 'Ask about your environments or deployments'}
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
          Send
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
