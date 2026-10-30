import i18n from 'i18next'
import { ApiError } from '../../../shared/api/client.ts'

export type RouteErrorField = 'hostname' | 'container' | 'port' | 'form'

export interface RouteError {
  field: RouteErrorField
  message: string
  /** Port choices the server lists with PORT_REQUIRED (empty: the user has to type one). */
  portOptions?: number[]
  /** Set for PROXY_NOT_SET_UP so the dialog can point to the setup. */
  proxyMissing?: boolean
}

/** Maps route API failures to a plain-language message and the form field it belongs to. */
export function describeRouteError(error: unknown): RouteError {
  if (error instanceof ApiError) {
    if (error.status === 403) return { field: 'form', message: i18n.t('domains:routeErrors.forbidden') }
    switch (error.code) {
      case 'ROUTE_HOST_TAKEN':
        return { field: 'hostname', message: i18n.t('domains:routeErrors.hostTaken') }
      case 'INVALID_HOSTNAME':
        return { field: 'hostname', message: i18n.t('domains:routeErrors.invalidHostname') }
      case 'WILDCARD_NOT_ALLOWED':
        return { field: 'hostname', message: i18n.t('domains:routeErrors.wildcard') }
      case 'INVALID_CONTAINER':
      case 'CONTAINER_NOT_FOUND':
        return { field: 'container', message: i18n.t('domains:routeErrors.invalidContainer') }
      case 'INVALID_PORT':
        return { field: 'port', message: i18n.t('domains:routeErrors.invalidPort') }
      case 'PORT_REQUIRED': {
        const portOptions = (error.details ?? []).map(Number).filter((n) => Number.isInteger(n) && n > 0)
        return { field: 'port', message: i18n.t('domains:routeErrors.portRequired'), portOptions }
      }
      case 'PROXY_NOT_SET_UP':
      case 'PROXY_NOT_FOUND':
        return { field: 'form', message: i18n.t('domains:routeErrors.proxyMissing'), proxyMissing: true }
      case 'ROUTE_NOT_FOUND':
        return { field: 'form', message: i18n.t('domains:routeErrors.routeNotFound') }
      case 'PROXY_ATTACH_FAILED':
        return { field: 'form', message: i18n.t('domains:routeErrors.attachFailed') }
      case 'TOO_MANY_ALIASES':
      case 'INVALID_PATH_PREFIX':
      case 'INVALID_ENTRYPOINT':
      case 'CERT_REQUIRED':
      case 'DNS_CREDENTIALS_REQUIRED':
      case 'CERT_NOT_FOUND':
      case 'CERT_DOES_NOT_COVER_HOST':
      case 'UNSUPPORTED_FOR_PROXY':
      case 'INVALID_BASIC_AUTH':
      case 'TOO_MANY_USERS':
      case 'TOO_MANY_HEADERS':
      case 'INVALID_HEADER':
      case 'INVALID_ROUTE':
        return { field: 'form', message: i18n.t(`domains:routeErrors.advanced.${error.code}`) }
      case 'PROXY_SYNC_FAILED':
        return { field: 'form', message: i18n.t('domains:routeErrors.syncFailed') }
    }
  }
  return { field: 'form', message: i18n.t('domains:routeErrors.generic') }
}
