import { useState, type ChangeEvent, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import {
  useCreateHost,
  useTestConnection,
  type ConnectionErrorCode,
  type ConnectionType,
  type Host,
  type HostRequest,
} from './api.ts'
import './EnvironmentForm.css'

const DEFAULT_PORT = '2376'

const CONNECTION_MESSAGES: Record<ConnectionErrorCode, string> = {
  UNREACHABLE: 'Could not reach the Docker host. Check the address and port.',
  TIMEOUT: 'The connection timed out. Check that the host is online and the port is open.',
  TLS_ERROR: 'The TLS handshake failed. Check that the certificates belong to this host.',
  PERMISSION_DENIED: 'Permission denied. Shipir is not allowed to use this Docker endpoint.',
  INVALID_TLS_MATERIAL: 'The certificate or key could not be read. Choose valid PEM files.',
}

function connectionMessage(code: string | null | undefined): string {
  return code && code in CONNECTION_MESSAGES
    ? CONNECTION_MESSAGES[code as ConnectionErrorCode]
    : 'The connection failed.'
}

function submitMessages(error: unknown): string[] {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'HOST_NAME_TAKEN':
        return ['This name is already in use. Choose another one.']
      case 'LOCAL_HOST_EXISTS':
        return ['A local Docker environment already exists.']
      case 'ENCRYPTION_KEY_MISSING':
        return [
          "The server has no encryption key configured, so TLS certificates can't be stored securely. Ask the administrator to set one.",
        ]
      case 'INVALID_TLS_MATERIAL':
        return [CONNECTION_MESSAGES.INVALID_TLS_MATERIAL]
      case 'CONNECTION_FAILED':
        return [connectionMessage(error.details?.[0])]
      case 'VALIDATION_ERROR':
        if (error.details && error.details.length > 0) return error.details
    }
  }
  return ['Could not add the environment. Please try again.']
}

function testErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.code === 'INVALID_TLS_MATERIAL') {
    return CONNECTION_MESSAGES.INVALID_TLS_MATERIAL
  }
  return 'Could not run the connection test. Please try again.'
}

type PemKey = 'tlsCaPem' | 'tlsCertPem' | 'tlsKeyPem'
/** A chosen PEM file: only the name is ever displayed, the content is held for the request. */
type PemFile = { name: string; content: string }

const PEM_FIELDS: { key: PemKey; label: string }[] = [
  { key: 'tlsCaPem', label: 'CA certificate' },
  { key: 'tlsCertPem', label: 'Client certificate' },
  { key: 'tlsKeyPem', label: 'Client key' },
]

interface EnvironmentFormProps {
  /** Called with the created environment after a successful submit. */
  onCreated?: (host: Host) => void
  submitLabel?: string
}

/** Adds a Docker environment: the local socket or a remote engine over TCP + TLS, with a connection test. */
export function EnvironmentForm({ onCreated, submitLabel = 'Add environment' }: EnvironmentFormProps) {
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
      <div className="env-form__segments" role="radiogroup" aria-label="Connection type">
        {(
          [
            ['LOCAL', 'Local Docker socket'],
            ['TCP', 'Remote (TCP + TLS)'],
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

      <label className="env-form__field">
        <span>Name</span>
        <input
          type="text"
          value={name}
          onChange={(e) => {
            setName(e.target.value)
            edited()
          }}
          autoComplete="off"
        />
      </label>

      {type === 'TCP' && (
        <>
          <div className="env-form__row">
            <label className="env-form__field env-form__field--grow">
              <span>Host</span>
              <input
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
            </label>
            <label className="env-form__field env-form__field--port">
              <span>Port</span>
              <input
                type="text"
                inputMode="numeric"
                value={port}
                onChange={(e) => {
                  setPort(e.target.value)
                  edited()
                }}
                aria-invalid={port.length > 0 && !portValid}
              />
            </label>
          </div>
          {PEM_FIELDS.map(({ key, label }) => (
            <label key={key} className="env-form__file">
              <span className="env-form__file-label">{label}</span>
              <span className="env-form__file-button">
                {pems[key] ? (
                  <>
                    <span aria-hidden="true">✓</span>
                    <span>{pems[key].name}</span>
                  </>
                ) : (
                  <span>Choose file…</span>
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
        {testConnection.isPending && 'Testing…'}
        {result?.ok && (
          <p className="env-form__ok">Connected. Docker {result.dockerVersion ?? ''}</p>
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
        <button
          className="env-form__button env-form__button--secondary"
          type="button"
          disabled={!connectable || testConnection.isPending}
          onClick={() => testConnection.mutate(buildRequest())}
        >
          Test connection
        </button>
        <button className="env-form__button" type="submit" disabled={!canSubmit}>
          {createHost.isPending ? 'Adding…' : submitLabel}
        </button>
      </div>
    </form>
  )
}
