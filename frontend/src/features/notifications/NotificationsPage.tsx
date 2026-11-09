import { useState } from 'react'
import { Bell, CircleArrowDown, CircleArrowUp, CircleX, GitBranch, HeartPulse, PackageCheck, type LucideIcon } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Checkbox, Button, EmptyState, PageHeader, Skeleton } from '../../shared/ui/index.ts'
import {
  NOTIFICATION_TYPE_LABELS,
  useMarkAllRead,
  useMarkRead,
  useNotifications,
  type AppNotification,
  type NotificationType,
} from './api.ts'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'
import { formatRelativeTime } from '../../shared/i18n/format.ts'
import './NotificationsPage.css'

function relativeTime(iso: string): string {
  const seconds = Math.round((new Date(iso).getTime() - Date.now()) / 1000)
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ['day', 86_400],
    ['hour', 3_600],
    ['minute', 60],
  ]
  for (const [unit, size] of units) {
    if (Math.abs(seconds) >= size) return formatRelativeTime(Math.round(seconds / size), unit)
  }
  return i18n.t('notifications:NotificationsPage.justNow')
}

type Tone = 'ok' | 'bad' | 'warn'

const TYPE_STYLE: Record<NotificationType, { icon: LucideIcon; tone: Tone }> = {
  HOST_DOWN: { icon: CircleArrowDown, tone: 'bad' },
  HOST_UP: { icon: CircleArrowUp, tone: 'ok' },
  DEPLOYMENT_FAILED: { icon: CircleX, tone: 'bad' },
  CONTAINER_CRASHED: { icon: CircleX, tone: 'bad' },
  CONTAINER_UNHEALTHY: { icon: HeartPulse, tone: 'warn' },
  GIT_AUTO_UPDATE_FAILED: { icon: GitBranch, tone: 'warn' },
  IMAGE_UPDATE_APPLIED: { icon: PackageCheck, tone: 'ok' },
  IMAGE_UPDATE_FAILED: { icon: CircleX, tone: 'bad' },
  UPTIME_DOWN: { icon: CircleArrowDown, tone: 'bad' },
  UPTIME_UP: { icon: CircleArrowUp, tone: 'ok' },
}

/** The deployment or container the notification is about; `to` is null when it was removed. */
function targetOf(item: AppNotification): { label: string; to: string | null } | null {
  if (item.deploymentName) {
    const to =
      item.hostId && item.deploymentId
        ? `/environments/${item.hostId}/deployments/${item.deploymentId}`
        : null
    return { label: item.deploymentName, to }
  }
  if (item.containerName) {
    const to =
      item.hostId && item.containerId
        ? `/environments/${item.hostId}/containers/${item.containerId}`
        : null
    return { label: item.containerName, to }
  }
  return null
}

const IMAGE_FAILURE_REASONS = ['UNHEALTHY', 'UP_FAILED', 'TIMEOUT', 'ERROR'] as const

/** Splits an image update failure code (`PULL_FAILED`, `<reason>[_NOT]_ROLLED_BACK`) into translation keys. */
function imageFailureKeys(code: string): { reason: string; rollback: string | null } {
  if (code === 'PULL_FAILED') return { reason: 'pullFailed', rollback: null }
  const notRolledBack = code.endsWith('_NOT_ROLLED_BACK')
  const rolledBack = !notRolledBack && code.endsWith('_ROLLED_BACK')
  const base = code.replace(/_(NOT_)?ROLLED_BACK$/, '')
  const known = (IMAGE_FAILURE_REASONS as readonly string[]).includes(base)
  return {
    reason: known ? base : 'other',
    rollback: rolledBack ? 'restored' : notRolledBack ? 'notRestored' : null,
  }
}

function ImageFailureDetail({ code }: { code: string }) {
  const { t } = useTranslation('notifications')
  const { reason, rollback } = imageFailureKeys(code)
  return (
    <p className="notif__detail">
      {t(`NotificationsPage.imageFailure.reason.${reason}`)}
      {rollback && ` ${t(`NotificationsPage.imageFailure.rollback.${rollback}`)}`}
    </p>
  )
}

function Row({ item, onRead }: { item: AppNotification; onRead: (id: string) => void }) {
  const { t } = useTranslation('notifications')
  const style = TYPE_STYLE[item.type]
  const target = targetOf(item)
  return (
    <li className={item.read ? 'notif' : 'notif notif--unread'}>
      <span className={`notif__icon notif__icon--${style.tone}`}>
        <style.icon size={20} strokeWidth={1.75} aria-hidden="true" />
      </span>
      <span className={`notif__type notif__type--${style.tone}`}>{NOTIFICATION_TYPE_LABELS[item.type]}</span>
      <div className="notif__body">
        <p className="notif__message">{t(`NotificationsPage.message.${item.type}`, { host: item.hostName, target: target?.label ?? item.hostName })}</p>
        {item.type === 'IMAGE_UPDATE_FAILED' && item.errorCode && <ImageFailureDetail code={item.errorCode} />}
        <p className="notif__meta">
          {item.hostId ? (
            <Link to={`/environments/${item.hostId}`}>{item.hostName}</Link>
          ) : (
            <span>{item.hostName}</span>
          )}
          {target && (
            <>
              {' · '}
              {target.to ? <Link to={target.to}>{target.label}</Link> : <span>{target.label}</span>}
            </>
          )}{' '}
          · <time dateTime={item.createdAt}>{relativeTime(item.createdAt)}</time>
        </p>
      </div>
      {!item.read && (
        <Button size="sm" className="notif__action" onClick={() => onRead(item.id)}>
          {t('NotificationsPage.markAsRead')}
        </Button>
      )}
    </li>
  )
}

export function NotificationsPage() {
  const { t } = useTranslation('notifications')
  const [unreadOnly, setUnreadOnly] = useState(false)
  const { data, isPending, isError } = useNotifications(unreadOnly)
  const markRead = useMarkRead()
  const markAll = useMarkAllRead()

  return (
    <section className="ui-page ui-page--narrow notifs" aria-label={t('NotificationsPage.notifications')}>
      <PageHeader
        title={t('NotificationsPage.notifications')}
        actions={
          <>
            <Checkbox className="notifs__filter" label={t('NotificationsPage.unreadOnly')} checked={unreadOnly} onChange={(event) => setUnreadOnly(event.target.checked)} />
            <Button disabled={markAll.isPending} onClick={() => markAll.mutate()}>
              {t('NotificationsPage.markAllAsRead')}
            </Button>
          </>
        }
      />
      {isPending && (
        <div className="notifs__skeleton" aria-busy="true">
          <Skeleton height="4.5rem" />
          <Skeleton height="4.5rem" />
          <Skeleton height="4.5rem" />
        </div>
      )}
      {isError && (
        <p role="alert" className="notifs__error">
          {t('NotificationsPage.couldNotLoadNotifications')}
        </p>
      )}
      {data && data.content.length === 0 && (
        <EmptyState icon={Bell} title={unreadOnly ? t('NotificationsPage.noUnreadNotifications') : t('NotificationsPage.noNotificationsYet')} description={t('NotificationsPage.hostAndDeploymentProblemsShow')} />
      )}
      {data && data.content.length > 0 && (
        <ul className="notifs__list">
          {data.content.map((item) => (
            <Row key={item.id} item={item} onRead={(id) => markRead.mutate(id)} />
          ))}
        </ul>
      )}
    </section>
  )
}
