import { useSearchParams } from 'react-router-dom'
import { MessageList } from '../ai-chat/index.ts'
import '../ai-chat/ChatPage.css'
import { useSharedChatSession, useSharedChatSessions } from './api.ts'
import './SharedChatSessionsPage.css'

const PAGE_SIZE = 20

const formatTime = (iso: string) =>
  new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })

/** Settings > AI assistant > Shared chats: ADMIN reads chats users shared, never acts on them. */
export function SharedChatSessionsPage() {
  const [params, setParams] = useSearchParams()
  const pageNumber = Math.max(1, Number(params.get('page')) || 1)
  const selected = params.get('session')

  const list = useSharedChatSessions(pageNumber - 1, PAGE_SIZE)
  const detail = useSharedChatSession(selected)

  function select(id: string) {
    const next = new URLSearchParams(params)
    next.set('session', id)
    setParams(next)
  }

  function go(p: number) {
    const next = new URLSearchParams(params)
    if (p > 1) next.set('page', String(p))
    else next.delete('page')
    setParams(next)
  }

  const pages = list.data ? Math.max(1, Math.ceil(list.data.totalElements / list.data.size)) : 1

  return (
    <section className="sc">
      <h2 className="sc-title">Shared chats</h2>
      <p className="sc-muted">Read-only copies of chats users shared with admins for debugging.</p>

      {list.isPending && <div aria-busy="true" className="sc-muted">Loading…</div>}
      {list.isError && (
        <p role="alert" className="sc-error">
          Could not load shared chats.
        </p>
      )}
      {list.data && list.data.items.length === 0 && <p className="sc-muted">No shared chats.</p>}
      {list.data && list.data.items.length > 0 && (
        <>
          <ul className="sc-list">
            {list.data.items.map((session) => (
              <li key={session.id}>
                <button
                  type="button"
                  className="sc-item"
                  aria-current={selected === session.id ? 'true' : undefined}
                  onClick={() => select(session.id)}
                >
                  <span className="sc-item__title">{session.title}</span>
                  <span className="sc-item__meta">
                    {session.ownerUsername} · {formatTime(session.updatedAt)}
                  </span>
                </button>
              </li>
            ))}
          </ul>
          <nav className="sc-pager" aria-label="Pagination">
            <button className="sc-btn" disabled={pageNumber <= 1} onClick={() => go(pageNumber - 1)}>
              Previous
            </button>
            <span className="sc-muted">
              Page {pageNumber} of {pages}
            </span>
            <button className="sc-btn" disabled={pageNumber >= pages} onClick={() => go(pageNumber + 1)}>
              Next
            </button>
          </nav>
        </>
      )}

      {selected && (
        <div className="sc-transcript">
          {detail.isPending && <div aria-busy="true" className="sc-muted">Loading chat…</div>}
          {detail.isError && (
            <p role="alert" className="sc-error">
              Could not load this chat.
            </p>
          )}
          {detail.data && (
            <>
              <header className="sc-transcript__head">
                <h3 className="sc-transcript__title">{detail.data.session.title}</h3>
                <span className="sc-muted">
                  {detail.data.session.ownerUsername} · {formatTime(detail.data.session.updatedAt)}
                </span>
              </header>
              <MessageList messages={detail.data.messages} readOnly />
            </>
          )}
        </div>
      )}
    </section>
  )
}
