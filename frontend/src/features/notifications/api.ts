import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../shared/api/client.ts'

export type NotificationType = 'HOST_DOWN' | 'HOST_UP'

export interface AppNotification {
  id: string
  type: NotificationType
  /** Null once the host was deleted. */
  hostId: string | null
  hostName: string
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
