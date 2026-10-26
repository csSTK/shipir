import { useSearchParams } from 'react-router-dom'
import { MessageList } from '../ai-chat/index.ts'
import { Card, ResourceTable, Skeleton, type ResourceColumn } from '../../shared/ui/index.ts'
import { useSharedChatSession, useSharedChatSessions, type SharedChatSessionPage } from './api.ts'
import { Pager } from './Pager.tsx'
import { SettingsPage } from './SettingsPage.tsx'
import { useTranslation } from 'react-i18next'
import { formatDateTime } from '../../shared/i18n/format.ts'
import './SharedChatSessionsPage.css'

const PAGE_SIZE = 20

const formatTime = (iso: string) =>
  formatDateTime(iso, { dateStyle: 'medium', timeStyle: 'short' })

/** Settings > AI assistant > Shared chats: ADMIN reads chats users shared, never acts on them. */
export function SharedChatSessionsPage() {
  const { t } = useTranslation('settings')
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

  const columns: ResourceColumn<SharedChatSessionPage['items'][number]>[] = [
    {
      id: 'title',
      header: t('SharedChatSessionsPage.chat'),
      defaultWidth: 360,
      hideable: false,
      render: (session) => (
        <button
          type="button"
          className="sc-item"
          aria-current={selected === session.id ? 'true' : undefined}
          onClick={() => select(session.id)}
        >
          {session.title}
        </button>
      ),
    },
    { id: 'owner', header: t('SharedChatSessionsPage.owner'), defaultWidth: 160, render: (session) => session.ownerUsername },
    { id: 'updated', header: t('SharedChatSessionsPage.updated'), defaultWidth: 200, render: (session) => formatTime(session.updatedAt) },
  ]

  return (
    <SettingsPage wide title={t('SharedChatSessionsPage.sharedChats')} subtitle={t('SharedChatSessionsPage.readOnlyCopiesOfChats')}>
      <ResourceTable
        label={t('SharedChatSessionsPage.sharedChats2')}
        columns={columns}
        rows={list.data?.items}
        rowKey={(session) => session.id}
        searchText={(session) => `${session.title} ${session.ownerUsername}`}
        defaultSort="title"
        emptyMessage={t('SharedChatSessionsPage.noSharedChats')}
        isPending={list.isPending}
        error={list.isError ? t('SharedChatSessionsPage.couldNotLoadSharedChats') : null}
        onRetry={() => void list.refetch()}
      />
      {list.data && list.data.items.length > 0 && <Pager page={pageNumber} pages={pages} onPage={go} />}

      {selected && (
        <Card className="sc-transcript">
          {detail.isPending && <Skeleton width="100%" height="var(--space-8)" />}
          {detail.isError && (
            <p role="alert" className="settings__error">
              {t('SharedChatSessionsPage.couldNotLoadThisChat')}
            </p>
          )}
          {detail.data && (
            <>
              <header className="sc-transcript__head">
                <h2 className="sc-transcript__title">{detail.data.session.title}</h2>
                <span className="settings__muted">
                  {detail.data.session.ownerUsername} · {formatTime(detail.data.session.updatedAt)}
                </span>
              </header>
              <MessageList messages={detail.data.messages} readOnly />
            </>
          )}
        </Card>
      )}
    </SettingsPage>
  )
}
