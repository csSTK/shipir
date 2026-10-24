import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  NOTIFICATION_TYPE_LABELS,
  useMarkAllRead,
  useMarkRead,
  useNotifications,
  type AppNotification,
  type NotificationType,
} from './api.ts'
import './NotificationsPage.css'

const rtf = new Intl.RelativeTimeFormat('en', { numeric: 'auto' })

function relativeTime(iso: string): string {
  const seconds = Math.round((new Date(iso).getTime() - Date.now()) / 1000)
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ['day', 86_400],
    ['hour', 3_600],
    ['minute', 60],
  ]
  for (const [unit, size] of units) {
    if (Math.abs(seconds) >= size) return rtf.format(Math.round(seconds / size), unit)
  }
  return 'just now'
}

type Tone = 'up' | 'down' | 'warn'

const TYPE_STYLE: Record<NotificationType, { glyph: string; tone: Tone }> = {
  HOST_DOWN: { glyph: '▼', tone: 'down' },
  HOST_UP: { glyph: '▲', tone: 'up' },
  DEPLOYMENT_FAILED: { glyph: '✕', tone: 'down' },
  CONTAINER_CRASHED: { glyph: '✕', tone: 'down' },
  CONTAINER_UNHEALTHY: { glyph: '!', tone: 'warn' },
  GIT_AUTO_UPDATE_FAILED: { glyph: '!', tone: 'warn' },
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

function Row({ item, onRead }: { item: AppNotification; onRead: (id: string) => void }) {
  const style = TYPE_STYLE[item.type]
  const target = targetOf(item)
  return (
    <li className={item.read ? 'notif' : 'notif notif--unread'}>
      <span className={`notif__type notif__type--${style.tone}`}>
        <span aria-hidden="true">{style.glyph}</span> {NOTIFICATION_TYPE_LABELS[item.type]}
      </span>
      <div className="notif__body">
        <p className="notif__message">{item.message}</p>
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
        <button type="button" className="notif__read" onClick={() => onRead(item.id)}>
          Mark as read
        </button>
      )}
    </li>
  )
}

export function NotificationsPage() {
  const [unreadOnly, setUnreadOnly] = useState(false)
  const { data, isPending, isError } = useNotifications(unreadOnly)
  const markRead = useMarkRead()
  const markAll = useMarkAllRead()

  return (
    <section className="notifs" aria-labelledby="notifs-title">
      <div className="notifs__bar">
        <h2 id="notifs-title" className="notifs__title">
          Notifications
        </h2>
        <label className="notifs__filter">
          <input
            type="checkbox"
            checked={unreadOnly}
            onChange={(event) => setUnreadOnly(event.target.checked)}
          />
          Unread only
        </label>
        <button
          type="button"
          className="notifs__all"
          disabled={markAll.isPending}
          onClick={() => markAll.mutate()}
        >
          Mark all as read
        </button>
      </div>
      {isPending && <div className="notifs__skeleton" aria-busy="true" />}
      {isError && (
        <p role="alert" className="notifs__message">
          Could not load notifications.
        </p>
      )}
      {data && data.content.length === 0 && (
        <p className="notifs__message">
          {unreadOnly ? 'No unread notifications.' : 'No notifications yet.'}
        </p>
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
