import { useState, type KeyboardEvent } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { ApiError } from '../../../shared/api/client.ts'
import { Button, TextField } from '../../../shared/ui/index.ts'
import { useFetchPortainerStack, useListPortainerStacks, type PortainerStack, type PortainerStackFile } from './api.ts'
import './portainer.css'

/** Maps the backend's Portainer import error codes to short messages. */
function describeError(error: unknown, t: TFunction<'deployments'>): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'INVALID_URL':
        return t('PortainerImportFields.invalidUrl')
      case 'EGRESS_DENIED':
        return t('PortainerImportFields.egressDenied')
      case 'UNSUPPORTED_STACK':
        return t('PortainerImportFields.unsupportedStack')
      case 'PORTAINER_AUTH_FAILED':
        return t('PortainerImportFields.authFailed')
      case 'PORTAINER_STACK_NOT_FOUND':
        return t('PortainerImportFields.stackNotFound')
      case 'PORTAINER_UNREACHABLE':
        return t('PortainerImportFields.unreachable')
      case 'PORTAINER_RESPONSE_TOO_LARGE':
        return t('PortainerImportFields.responseTooLarge')
      case 'PORTAINER_TLS_FAILED':
        return t('PortainerImportFields.tlsFailed')
      case 'PORTAINER_BAD_RESPONSE':
        return t('PortainerImportFields.badResponse')
    }
  }
  return t('PortainerImportFields.failed')
}

/**
 * "Import from Portainer" source of the add dialog: URL + API token, list the stacks, pick one.
 * The token lives only in this component's state (cleared when the dialog closes); it never reaches
 * storage, the URL or a query key. The picked stack is handed to the dialog's normal prefill path.
 */
export function PortainerImportFields({ onStack }: { onStack: (stack: PortainerStackFile) => void }) {
  const { t } = useTranslation('deployments')
  const [url, setUrl] = useState('')
  const [apiToken, setApiToken] = useState('')
  const [stacks, setStacks] = useState<PortainerStack[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const list = useListPortainerStacks()
  const fetchStack = useFetchPortainerStack()
  const busy = list.isPending || fetchStack.isPending

  function connect() {
    if (busy) return
    const trimmed = url.trim()
    if (!trimmed || !apiToken) return setError(t('PortainerImportFields.enterUrlAndToken'))
    setError(null)
    setStacks(null)
    list.mutate(
      { url: trimmed, apiToken },
      { onSuccess: setStacks, onError: (err) => setError(describeError(err, t)) },
    )
  }

  function pick(stack: PortainerStack) {
    if (busy) return
    setError(null)
    fetchStack.mutate(
      { id: stack.id, url: url.trim(), apiToken },
      { onSuccess: onStack, onError: (err) => setError(describeError(err, t)) },
    )
  }

  /** Enter in a field connects instead of submitting the surrounding form. */
  function enter(e: KeyboardEvent) {
    if (e.key !== 'Enter') return
    e.preventDefault()
    connect()
  }

  return (
    <div className="pi-fields">
      <TextField
        label={t('PortainerImportFields.url')}
        type="url"
        value={url}
        onChange={(e) => setUrl(e.target.value)}
        onKeyDown={enter}
        placeholder="https://portainer.example.com"
        autoComplete="off"
        spellCheck={false}
      />
      <TextField
        label={t('PortainerImportFields.token')}
        type="password"
        value={apiToken}
        onChange={(e) => setApiToken(e.target.value)}
        onKeyDown={enter}
        help={t('PortainerImportFields.tokenHelp')}
        autoComplete="off"
        spellCheck={false}
      />
      <div className="pi-actions">
        <Button onClick={connect} loading={list.isPending}>
          {t('PortainerImportFields.connect')}
        </Button>
      </div>
      {error && (
        <p role="alert" className="ui-error">
          {error}
        </p>
      )}
      {stacks && stacks.length === 0 && <p className="pi-empty">{t('PortainerImportFields.noStacks')}</p>}
      {stacks && stacks.length > 0 && (
        <ul className="pi-stacks" aria-label={t('PortainerImportFields.stacks')}>
          {stacks.map((stack) => (
            <li key={stack.id} className="pi-stack">
              <div className="pi-stack__text">
                <span className="pi-stack__name">{stack.name}</span>
                {!stack.supported && <span className="pi-stack__reason">{stack.reason ?? t('PortainerImportFields.unsupportedStack')}</span>}
              </div>
              <Button
                size="sm"
                aria-label={t('PortainerImportFields.importStack', { name: stack.name })}
                disabled={!stack.supported || busy}
                onClick={() => pick(stack)}
              >
                {t('PortainerImportFields.import')}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
