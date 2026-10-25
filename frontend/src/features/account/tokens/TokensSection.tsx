import { useState, type FormEvent } from 'react'
import { ApiError } from '../../../shared/api/client.ts'
import { Button, Card, Dialog, EmptyState, Select, Skeleton, TextField } from '../../../shared/ui/index.ts'
import { OneTimeSecret } from '../../one-time-secret/index.ts'
import { useApiTokens, useCreateApiToken, useRevokeApiToken, type ApiToken, type CreatedApiToken } from './api.ts'
import './tokens.css'

/** Empty value = the server's maximum lifetime (it applies it when `expiresAt` is omitted). */
const EXPIRY_OPTIONS = [
  { value: '30', label: '30 days' },
  { value: '90', label: '90 days' },
  { value: '', label: 'Longest allowed' },
] as const

const DAY_MS = 86_400_000

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString()
}

function createError(error: unknown): string {
  if (error instanceof ApiError && error.code === 'INVALID_EXPIRY') {
    return 'The expiry is not allowed. Choose a shorter lifetime.'
  }
  return 'Could not create the token. Please try again.'
}

function CreateTokenDialog({ onClose }: { onClose: () => void }) {
  const create = useCreateApiToken()
  const [name, setName] = useState('')
  const [days, setDays] = useState('')
  // Only held in this component; closing the dialog unmounts it and the plaintext is gone.
  const [created, setCreated] = useState<CreatedApiToken | null>(null)

  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (!name.trim() || create.isPending) return
    const expiresAt = days ? new Date(Date.now() + Number(days) * DAY_MS).toISOString() : undefined
    create.mutate({ name: name.trim(), ...(expiresAt ? { expiresAt } : {}) }, { onSuccess: setCreated })
  }

  if (created) {
    return (
      <Dialog
        open
        onOpenChange={(open) => !open && onClose()}
        title="Token created"
        footer={
          <Button variant="primary" onClick={onClose}>
            Done
          </Button>
        }
      >
        <p className="otsecret-notice">
          This token is shown only once. Store it now, you cannot see it again. It acts with your permissions, so keep
          it secret.
        </p>
        <OneTimeSecret label="Token" value={created.token} />
      </Dialog>
    )
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title="Create API token"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" form="create-token-form" disabled={!name.trim()} loading={create.isPending}>
            Create
          </Button>
        </>
      }
    >
      <form id="create-token-form" className="ui-form" onSubmit={onSubmit}>
        <TextField label="Name" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} help="For example the script or service that uses it." />
        <Select label="Expires" value={days} onChange={(e) => setDays(e.target.value)} help="The token stops working after this time.">
          {EXPIRY_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </Select>
        {create.isError && (
          <p role="alert" className="ui-error">
            {createError(create.error)}
          </p>
        )}
      </form>
    </Dialog>
  )
}

function RevokeDialog({ token, onClose }: { token: ApiToken; onClose: () => void }) {
  const revoke = useRevokeApiToken()
  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title="Revoke token"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="danger" loading={revoke.isPending} onClick={() => revoke.mutate(token.id, { onSuccess: onClose })}>
            Revoke
          </Button>
        </>
      }
    >
      <p className="ui-dialog-text">
        Revoke <strong>{token.name}</strong>? Anything using it stops working immediately. This cannot be undone.
      </p>
      {revoke.isError && (
        <p role="alert" className="ui-error">
          Could not revoke the token. Please try again.
        </p>
      )}
    </Dialog>
  )
}

/** Personal API tokens: list, create (one-time reveal) and revoke. Available to every role. */
export function TokensSection() {
  const tokens = useApiTokens()
  const [creating, setCreating] = useState(false)
  const [revoking, setRevoking] = useState<ApiToken | null>(null)
  const origin = window.location.origin

  return (
    <Card className="account__card" role="region" aria-labelledby="account-tokens">
      <div className="tokens__head">
        <h2 className="account__heading" id="account-tokens">
          API tokens
        </h2>
        <Button onClick={() => setCreating(true)}>Create token</Button>
      </div>
      <p className="tokens__text">
        Tokens let scripts call the Shipir API with your permissions. They work in the Authorization header, not for
        signing in or managing tokens.
      </p>

      {tokens.isPending && (
        <div aria-busy="true" className="account__loading">
          <Skeleton height="var(--space-5)" />
          <Skeleton height="var(--space-5)" />
        </div>
      )}
      {tokens.isError && (
        <p role="alert" className="account__error">
          Could not load your tokens.
        </p>
      )}
      {tokens.data && tokens.data.length === 0 && <EmptyState title="No API tokens yet" />}
      {tokens.data && tokens.data.length > 0 && (
        <ul className="tokens__list" aria-label="API tokens">
          {tokens.data.map((t) => (
            <li key={t.id} className="tokens__item">
              <div className="tokens__main">
                <span className="tokens__name">{t.name}</span>
                <code className="tokens__prefix">{t.prefix}…</code>
                <span className="tokens__meta">
                  <span>Created {formatDate(t.createdAt)}</span>
                  <span>Expires {formatDate(t.expiresAt)}</span>
                  <span>
                    Last used <span>{t.lastUsedAt ? formatDate(t.lastUsedAt) : 'Never'}</span>
                  </span>
                </span>
              </div>
              <Button size="sm" variant="ghost" aria-label={`Revoke token ${t.name}`} onClick={() => setRevoking(t)}>
                Revoke
              </Button>
            </li>
          ))}
        </ul>
      )}

      <div>
        <p className="tokens__text">Use a token as a Bearer token:</p>
        <pre className="otsecret-code">{`curl -H "Authorization: Bearer $SHIPIR_TOKEN" ${origin}/api/hosts`}</pre>
      </div>

      {creating && <CreateTokenDialog onClose={() => setCreating(false)} />}
      {revoking && <RevokeDialog token={revoking} onClose={() => setRevoking(null)} />}
    </Card>
  )
}
