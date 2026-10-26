import { useEffect, useRef, type ReactNode } from 'react'
import { Check } from 'lucide-react'
import './MessageList.css'
import { ActionConfirmCard } from './ActionConfirmCard.tsx'
import type { ChatMessage } from './api.ts'
import { toolLabel } from './toolLabel.ts'
import { useTranslation } from 'react-i18next'

interface Props {
  messages: ChatMessage[]
  /** Hides confirm/reject (read-only views such as the ADMIN debugging view). */
  readOnly?: boolean
  busyMessageId?: string | null
  /** Per-message error text from a failed confirm/reject. */
  actionErrors?: Record<string, string>
  onResolve?: (messageId: string, decision: 'confirm' | 'reject') => void
  /** Rendered after the stored messages (live stream, optimistic message). */
  live?: ReactNode
}

function toolNames(messages: ChatMessage[]): Map<string, string> {
  const names = new Map<string, string>()
  for (const m of messages) for (const c of m.toolCalls ?? []) names.set(c.id, c.name)
  return names
}

export function MessageList({ messages, readOnly = false, busyMessageId = null, actionErrors = {}, onResolve, live }: Props) {
  const { t } = useTranslation('ai-chat')
  const names = toolNames(messages)
  const end = useRef<HTMLDivElement>(null)
  useEffect(() => {
    end.current?.scrollIntoView?.({ block: 'end' })
  }, [messages, live])

  return (
    <div className="chat-messages" role="log" aria-label={t('MessageList.conversation')}>
      {messages.map((m) => {
        if (m.role === 'USER') {
          return (
            <p key={m.id} className="chat-bubble chat-bubble--user">
              {m.content}
            </p>
          )
        }
        if (m.role === 'ASSISTANT') {
          return m.content ? (
            <p key={m.id} className="chat-bubble chat-bubble--assistant">
              {m.content}
            </p>
          ) : null
        }
        const call = m.toolCalls?.[0]
        if (m.status && call) {
          return (
            <ActionConfirmCard
              key={m.id}
              call={call}
              status={m.status}
              readOnly={readOnly}
              busy={busyMessageId === m.id}
              error={actionErrors[m.id]}
              onConfirm={() => onResolve?.(m.id, 'confirm')}
              onReject={() => onResolve?.(m.id, 'reject')}
            />
          )
        }
        const tool = m.toolCallId ? names.get(m.toolCallId) : undefined
        return tool ? (
          <p key={m.id} className="chat-activity">
            <Check size={16} strokeWidth={1.75} aria-hidden="true" />
            {t('MessageList.lookedUpToollabel', { toolLabel: toolLabel(tool) })}
          </p>
        ) : null
      })}
      {live}
      <div ref={end} />
    </div>
  )
}
