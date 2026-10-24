import { Sparkles } from 'lucide-react'
import { useParams } from 'react-router-dom'
import { EmptyState } from '../../shared/ui/index.ts'
import { ChatPane } from './ChatPane.tsx'
import { SessionList } from './SessionList.tsx'
import './ChatPage.css'

/** `/assistant` and `/assistant/:sessionId`: chat history on the left, the open chat on the right. */
export function ChatPage({ role }: { role: string }) {
  const { sessionId } = useParams()
  return (
    <div className="chat">
      <SessionList />
      <main className="chat-main">
        {sessionId ? (
          <ChatPane key={sessionId} sessionId={sessionId} isAdmin={role === 'ADMIN'} />
        ) : (
          <EmptyState
            icon={Sparkles}
            title="Assistant"
            description="Ask questions about your environments or let the assistant propose changes. You confirm every change before it runs. Start a new chat or open an earlier one."
          />
        )}
      </main>
    </div>
  )
}
