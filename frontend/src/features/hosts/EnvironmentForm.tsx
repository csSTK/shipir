import { useState, type ChangeEvent, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { Check } from 'lucide-react'
import { Button, TextField } from '../../shared/ui/index.ts'
import {
  useCreateHost,
  useTestConnection,
  type ConnectionErrorCode,
  type ConnectionType,
  type Host,
  type HostRequest,
} from './api.ts'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'
import './EnvironmentForm.css'

const DEFAULT_PORT = '2376'

/**
 * Messages for connection test and create results. TLS_ERROR has no entry: a remote (TCP) target reports every failure
 * as UNREACHABLE, and a local socket has no TLS, so it never arrives here.
 */
const CONNECTION_MESSAGES: Partial<Record<ConnectionErrorCode, string>> = {
  get UNREACHABLE() { return i18n.t('hosts:EnvironmentForm.couldNotReachTheDocker') },
  get TIMEOUT() { return i18n.t('hosts:EnvironmentForm.theConnectionTimedOutCheck') },
  get PERMISSION_DENIED() { return i18n.t('hosts:EnvironmentForm.permissionDeniedShipirIsNot') },
  get INVALID_TLS_MATERIAL() { return i18n.t('hosts:EnvironmentForm.theCertificateOrKeyCould') },
}

function connectionMessage(code: string | null | undefined): string {
  const message = code ? CONNECTION_MESSAGES[code as ConnectionErrorCode] : undefined
  return message ?? i18n.t('hosts:EnvironmentForm.theConnectionFailed')
}

function submitMessages(error: unknown): string[] {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'HOST_NAME_TAKEN':
        return [i18n.t('hosts:EnvironmentForm.thisNameIsAlreadyIn')]
      case 'LOCAL_HOST_EXISTS':
        return [i18n.t('hosts:EnvironmentForm.aLocalDockerEnvironmentAlready')]
      case 'ENCRYPTION_KEY_MISSING':
        return [
          i18n.t('hosts:EnvironmentForm.theServerHasNoEncryption'),
        ]
      case 'INVALID_TLS_MATERIAL':
        return [connectionMessage('INVALID_TLS_MATERIAL')]
      case 'CONNECTION_FAILED':
        return [connectionMessage(error.details?.[0])]
      case 'VALIDATION_ERROR':
        if (error.details && error.details.length > 0) return error.details
    }
  }
  return [i18n.t('hosts:EnvironmentForm.couldNotAddTheEnvironment')]
}

function testErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.code === 'INVALID_TLS_MATERIAL') {
    return connectionMessage('INVALID_TLS_MATERIAL')
  }
  return i18n.t('hosts:EnvironmentForm.couldNotRunTheConnection')
}

type PemKey = 'tlsCaPem' | 'tlsCertPem' | 'tlsKeyPem'
/** A chosen PEM file: only the name is ever displayed, the content is held for the request. */
type PemFile = { name: string; content: string }

const PEM_FIELDS: { key: PemKey; label: string }[] = [
  { key: 'tlsCaPem', label: 'CA certificate' },
  { key: 'tlsCertPem', get label() { return i18n.t('hosts:EnvironmentForm.clientCertificate') } },
  { key: 'tlsKeyPem', get label() { return i18n.t('hosts:EnvironmentForm.clientKey') } },
]

interface EnvironmentFormProps {
  /** Called with the created environment after a successful submit. */
  onCreated?: (host: Host) => void
  submitLabel?: string
}

/** Adds a Docker environment: the local socket or a remote engine over TCP + TLS, with a connection test. */
export function EnvironmentForm({ onCreated, submitLabel = i18n.t('hosts:EnvironmentForm.addEnvironment') }: EnvironmentFormProps) {
  const { t } = useTranslation('hosts')
  const createHost = useCreateHost()
  const testConnection = useTestConnection()
  const [type, setType] = useState<ConnectionType>('LOCAL')
  const [name, setName] = useState('')
  const [host, setHost] = useState('')
  const [port, setPort] = useState(DEFAULT_PORT)
  const [pems, setPems] = useState<Partial<Record<PemKey, PemFile>>>({})

  const portNumber = Number(port)
  const portValid = Number.isInteger(portNumber) && portNumber >= 1 && portNumber <= 65535
  const connectable =
    type === 'LOCAL' ||
    (host.trim().length > 0 && portValid && PEM_FIELDS.every(({ key }) => pems[key] !== undefined))
  const canSubmit = name.trim().length > 0 && connectable && !createHost.isPending

  // Any edit makes earlier results stale.
  const edited = () => {
    testConnection.reset()
    createHost.reset()
  }

  const buildRequest = (): HostRequest =>
    type === 'LOCAL'
      ? { ...(name.trim() && { name: name.trim() }), connectionType: 'LOCAL' }
      : {
          ...(name.trim() && { name: name.trim() }),
          connectionType: 'TCP',
          host: host.trim(),
          port: portNumber,
          tlsCaPem: pems.tlsCaPem?.content,
          tlsCertPem: pems.tlsCertPem?.content,
          tlsKeyPem: pems.tlsKeyPem?.content,
        }

  const onPickFile = async (key: PemKey, event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    if (!file) return
    const content = await file.text()
    setPems((current) => ({ ...current, [key]: { name: file.name, content } }))
    edited()
  }

  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (!canSubmit) return
    createHost.mutate(buildRequest(), { onSuccess: (created) => onCreated?.(created) })
  }

  const result = testConnection.data
  return (
    <form className="env-form" onSubmit={onSubmit}>
      <div className="env-form__segments" role="radiogroup" aria-label={t('EnvironmentForm.connectionType')}>
        {(
          [
            ['LOCAL', t('EnvironmentForm.localDockerSocket')],
            ['TCP', t('EnvironmentForm.remoteTcpTls')],
          ] as const
        ).map(([value, label]) => (
          <label key={value} className="env-form__segment">
            <input
              type="radio"
              name="connectionType"
              value={value}
              checked={type === value}
              onChange={() => {
                setType(value)
                edited()
              }}
            />
            <span>{label}</span>
          </label>
        ))}
      </div>

      <TextField
        label={t('EnvironmentForm.name')}
        type="text"
        value={name}
        onChange={(e) => {
          setName(e.target.value)
          edited()
        }}
        autoComplete="off"
      />

      {type === 'TCP' && (
        <>
          <div className="env-form__row">
            <div className="env-form__col--grow">
              <TextField
                label={t('EnvironmentForm.host')}
                type="text"
                value={host}
                onChange={(e) => {
                  setHost(e.target.value)
                  edited()
                }}
                placeholder="docker.example.com"
                autoComplete="off"
                autoCapitalize="none"
                spellCheck={false}
              />
            </div>
            <div className="env-form__col--port">
              <TextField
                label={t('EnvironmentForm.port')}
                type="text"
                inputMode="numeric"
                value={port}
                onChange={(e) => {
                  setPort(e.target.value)
                  edited()
                }}
                error={port.length > 0 && !portValid ? t('EnvironmentForm.portRange') : null}
              />
            </div>
          </div>
          {PEM_FIELDS.map(({ key, label }) => (
            <label key={key} className="env-form__file">
              <span className="env-form__file-label">{label}</span>
              <span className="env-form__file-button">
                {pems[key] ? (
                  <>
                    <Check size={16} strokeWidth={1.75} aria-hidden="true" className="env-form__check" />
                    <span>{pems[key].name}</span>
                  </>
                ) : (
                  <span>{t('EnvironmentForm.chooseFile')}</span>
                )}
              </span>
              <input
                className="env-form__file-input"
                type="file"
                accept=".pem,.crt,.cer,.key"
                onChange={(e) => void onPickFile(key, e)}
              />
            </label>
          ))}
        </>
      )}

      <div className="env-form__status" role="status">
        {testConnection.isPending && t('EnvironmentForm.testing')}
        {result?.ok && (
          <p className="env-form__ok">{t('EnvironmentForm.connectedDockerDockerversion', { dockerVersion: result.dockerVersion ?? '' })}</p>
        )}
        {result && !result.ok && <p className="env-form__error">{connectionMessage(result.errorCode)}</p>}
        {testConnection.isError && (
          <p className="env-form__error">{testErrorMessage(testConnection.error)}</p>
        )}
      </div>
      <div className="env-form__alert" role="alert">
        {createHost.isError &&
          submitMessages(createHost.error).map((message) => <p key={message}>{message}</p>)}
      </div>

      <div className="env-form__actions">
        <Button disabled={!connectable || testConnection.isPending} onClick={() => testConnection.mutate(buildRequest())}>
          {t('EnvironmentForm.testConnection')}
        </Button>
        <Button variant="primary" type="submit" disabled={!canSubmit} loading={createHost.isPending}>
          {createHost.isPending ? t('EnvironmentForm.adding') : submitLabel}
        </Button>
      </div>
    </form>
  )
}
