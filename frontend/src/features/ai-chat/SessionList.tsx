import { NavLink, useNavigate } from 'react-router-dom'
import { Plus } from 'lucide-react'
import { Button, Skeleton } from '../../shared/ui/index.ts'
import { useChatSessions, useCreateChatSession } from './api.ts'

function when(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}

/** The user's own chats, newest first, plus the New chat button. */
export function SessionList() {
  const sessions = useChatSessions()
  const create = useCreateChatSession()
  const navigate = useNavigate()

  const newChat = () =>
    create.mutate(undefined, { onSuccess: (s) => navigate(`/assistant/${s.id}`) })

  return (
    <aside className="chat-sessions">
      <Button variant="primary" className="chat-sessions__new" onClick={newChat} disabled={create.isPending} loading={create.isPending}>
        <Plus size={16} strokeWidth={1.75} aria-hidden="true" />
        New chat
      </Button>
      {create.isError && (
        <p role="alert" className="chat-error">
          Could not start a chat. Try again.
        </p>
      )}
      <nav aria-label="Chats">
        {sessions.isPending && (
          <div aria-busy="true" className="chat-loading">
            <Skeleton height="2.25rem" />
            <Skeleton height="2.25rem" />
          </div>
        )}
        {sessions.isError && (
          <p role="alert" className="chat-error">
            Could not load your chats.
          </p>
        )}
        {sessions.data?.items.length === 0 && <p className="chat-muted">No chats yet.</p>}
        <ul>
          {sessions.data?.items.map((s) => (
            <li key={s.id}>
              <NavLink to={`/assistant/${s.id}`} className="chat-sessions__link">
                <span className="chat-sessions__title">{s.title}</span>
                <span className="chat-sessions__date">{when(s.updatedAt)}</span>
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
    </aside>
  )
}
