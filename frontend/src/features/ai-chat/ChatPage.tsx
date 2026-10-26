import { Sparkles } from 'lucide-react'
import { useParams } from 'react-router-dom'
import { EmptyState } from '../../shared/ui/index.ts'
import { ChatPane } from './ChatPane.tsx'
import { SessionList } from './SessionList.tsx'
import { useTranslation } from 'react-i18next'
import './ChatPage.css'

/** `/assistant` and `/assistant/:sessionId`: chat history on the left, the open chat on the right. */
export function ChatPage({ role }: { role: string }) {
  const { t } = useTranslation('ai-chat')
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
            title={t('ChatPage.assistant')}
            description={t('ChatPage.askQuestionsAboutYourEnvironments')}
          />
        )}
      </main>
    </div>
  )
}
