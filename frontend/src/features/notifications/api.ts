import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../shared/api/client.ts'
import i18n from 'i18next'

export const NOTIFICATION_TYPES = [
  'HOST_DOWN',
  'HOST_UP',
  'DEPLOYMENT_FAILED',
  'CONTAINER_CRASHED',
  'CONTAINER_UNHEALTHY',
  'GIT_AUTO_UPDATE_FAILED',
  'IMAGE_UPDATE_APPLIED',
  'IMAGE_UPDATE_FAILED',
  'UPTIME_DOWN',
  'UPTIME_UP',
] as const

export type NotificationType = (typeof NOTIFICATION_TYPES)[number]

export const NOTIFICATION_TYPE_LABELS: Record<NotificationType, string> = {
  get HOST_DOWN() { return i18n.t('notifications:api.hostDown') },
  get HOST_UP() { return i18n.t('notifications:api.hostUp') },
  get DEPLOYMENT_FAILED() { return i18n.t('notifications:api.deploymentFailed') },
  get CONTAINER_CRASHED() { return i18n.t('notifications:api.containerCrashed') },
  get CONTAINER_UNHEALTHY() { return i18n.t('notifications:api.containerUnhealthy') },
  get GIT_AUTO_UPDATE_FAILED() { return i18n.t('notifications:api.gitAutoUpdateFailed') },
  get IMAGE_UPDATE_APPLIED() { return i18n.t('notifications:api.imageUpdateApplied') },
  get IMAGE_UPDATE_FAILED() { return i18n.t('notifications:api.imageUpdateFailed') },
  get UPTIME_DOWN() { return i18n.t('notifications:api.uptimeCheckDown') },
  get UPTIME_UP() { return i18n.t('notifications:api.uptimeCheckUp') },
}

export interface AppNotification {
  id: string
  type: NotificationType
  /** Null once the host was deleted. */
  hostId: string | null
  hostName: string
  /** Null for host notifications and once the deployment or container was removed. */
  deploymentId: string | null
  deploymentName: string | null
  containerId: string | null
  containerName: string | null
  message: string
  read: boolean
  createdAt: string
}

export interface NotificationPage {
  content: AppNotification[]
  totalElements: number
}

export const NOTIFICATIONS_KEY = ['notifications'] as const
export const UNREAD_COUNT_KEY = ['notifications', 'unread-count'] as const

const UNREAD_REFRESH_MS = 30_000
const PAGE_SIZE = 50

export function useNotifications(unreadOnly: boolean) {
  return useQuery({
    queryKey: [...NOTIFICATIONS_KEY, 'list', { unreadOnly }],
    queryFn: () =>
      apiFetch<NotificationPage>(
        `/notifications?page=0&size=${PAGE_SIZE}${unreadOnly ? '&unreadOnly=true' : ''}`,
      ),
  })
}

/** Unread badge count; refreshes every 30 s and when the window regains focus. */
export function useUnreadCount() {
  return useQuery({
    queryKey: UNREAD_COUNT_KEY,
    queryFn: () => apiFetch<{ count: number }>('/notifications/unread-count'),
    refetchInterval: UNREAD_REFRESH_MS,
    refetchOnWindowFocus: true,
  })
}

function useInvalidate() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: NOTIFICATIONS_KEY })
}

export function useMarkRead() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (id: string) => apiFetch<void>(`/notifications/${id}/read`, { method: 'POST' }),
    onSuccess: () => void invalidate(),
  })
}

export function useMarkAllRead() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: () => apiFetch<void>('/notifications/read-all', { method: 'POST' }),
    onSuccess: () => void invalidate(),
  })
}

export interface NotificationPreference {
  type: NotificationType
  inAppEnabled: boolean
}

export const PREFERENCES_KEY = ['notification-preferences'] as const

export function useNotificationPreferences() {
  return useQuery({
    queryKey: PREFERENCES_KEY,
    queryFn: () => apiFetch<NotificationPreference[]>('/notifications/preferences'),
  })
}

/** Replaces all preferences; updates the cache at once and re-reads the server state only on failure. */
export function useSavePreferences() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (preferences: NotificationPreference[]) =>
      apiFetch<void>('/notifications/preferences', { method: 'PUT', body: preferences }),
    onMutate: (preferences) => queryClient.setQueryData(PREFERENCES_KEY, preferences),
    onError: () => void queryClient.invalidateQueries({ queryKey: PREFERENCES_KEY }),
  })
}
