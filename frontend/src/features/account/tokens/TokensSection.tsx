import { useState, type FormEvent } from 'react'
import { ApiError } from '../../../shared/api/client.ts'
import { Button, Card, Dialog, EmptyState, Select, Skeleton, TextField } from '../../../shared/ui/index.ts'
import { OneTimeSecret } from '../../one-time-secret/index.ts'
import { useApiTokens, useCreateApiToken, useRevokeApiToken, type ApiToken, type CreatedApiToken } from './api.ts'
import { useTranslation } from 'react-i18next'
import { Trans } from '../../../shared/i18n/Trans.tsx'
import i18n from 'i18next'
import { formatDate } from '../../../shared/i18n/format.ts'
import './tokens.css'

/** Empty value = the server's maximum lifetime (it applies it when `expiresAt` is omitted). */
const EXPIRY_OPTIONS = [
  { value: '30', get label() { return i18n.t('account:TokensSection.days', { count: 30 }) } },
  { value: '90', get label() { return i18n.t('account:TokensSection.days', { count: 90 }) } },
  { value: '', get label() { return i18n.t('account:TokensSection.longestAllowed') } },
] as const

const DAY_MS = 86_400_000

function createError(error: unknown): string {
  if (error instanceof ApiError && error.code === 'INVALID_EXPIRY') {
    return i18n.t('account:TokensSection.theExpiryIsNotAllowed')
  }
  return i18n.t('account:TokensSection.couldNotCreateTheToken')
}

function CreateTokenDialog({ onClose }: { onClose: () => void }) {
  const { t: tr } = useTranslation('account')
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
        title={tr('TokensSection.tokenCreated')}
        footer={
          <Button variant="primary" onClick={onClose}>
            {tr('TokensSection.done')}
          </Button>
        }
      >
        <p className="otsecret-notice">
          {tr('TokensSection.thisTokenIsShownOnly')}
        </p>
        <OneTimeSecret label={tr('TokensSection.token')} value={created.token} />
      </Dialog>
    )
  }

  return (
    <Dialog
      size="lg"
      open
      onOpenChange={(open) => !open && onClose()}
      title={tr('TokensSection.createApiToken')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {tr('TokensSection.cancel')}
          </Button>
          <Button variant="primary" type="submit" form="create-token-form" disabled={!name.trim()} loading={create.isPending}>
            {tr('TokensSection.create')}
          </Button>
        </>
      }
    >
      <form id="create-token-form" className="ui-form" onSubmit={onSubmit}>
        <TextField label={tr('TokensSection.name')} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} help={tr('TokensSection.forExampleTheScriptOr')} />
        <Select label={tr('TokensSection.expires')} value={days} onChange={(e) => setDays(e.target.value)} help={tr('TokensSection.theTokenStopsWorkingAfter')}>
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
  const { t: tr } = useTranslation('account')
  const revoke = useRevokeApiToken()
  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={tr('TokensSection.revokeToken')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {tr('TokensSection.cancel')}
          </Button>
          <Button variant="danger" loading={revoke.isPending} onClick={() => revoke.mutate(token.id, { onSuccess: onClose })}>
            {tr('TokensSection.revoke')}
          </Button>
        </>
      }
    >
      <p className="ui-dialog-text">
        <Trans t={tr} i18nKey="TokensSection.revokeNameAnythingUsingIt" values={{ name: token.name }} components={{ strong: <strong /> }} />
      </p>
      {revoke.isError && (
        <p role="alert" className="ui-error">
          {tr('TokensSection.couldNotRevokeTheToken')}
        </p>
      )}
    </Dialog>
  )
}

/** Personal API tokens: list, create (one-time reveal) and revoke. Available to every role. */
export function TokensSection() {
  const { t: tr } = useTranslation('account')
  const tokens = useApiTokens()
  const [creating, setCreating] = useState(false)
  const [revoking, setRevoking] = useState<ApiToken | null>(null)
  const origin = window.location.origin

  return (
    <Card className="account__card" role="region" aria-labelledby="account-tokens">
      <div className="tokens__head">
        <h2 className="account__heading" id="account-tokens">
          {tr('TokensSection.apiTokens')}
        </h2>
        <Button onClick={() => setCreating(true)}>{tr('TokensSection.createToken')}</Button>
      </div>
      <p className="tokens__text">
        {tr('TokensSection.tokensLetScriptsCallThe')}
      </p>

      {tokens.isPending && (
        <div aria-busy="true" className="account__loading">
          <Skeleton height="var(--space-5)" />
          <Skeleton height="var(--space-5)" />
        </div>
      )}
      {tokens.isError && (
        <p role="alert" className="account__error">
          {tr('TokensSection.couldNotLoadYourTokens')}
        </p>
      )}
      {tokens.data && tokens.data.length === 0 && <EmptyState title={tr('TokensSection.noApiTokensYet')} />}
      {tokens.data && tokens.data.length > 0 && (
        <ul className="tokens__list" aria-label={tr('TokensSection.apiTokens')}>
          {tokens.data.map((t) => (
            <li key={t.id} className="tokens__item">
              <div className="tokens__main">
                <span className="tokens__name">{t.name}</span>
                <code className="tokens__prefix">{t.prefix}…</code>
                <span className="tokens__meta">
                  <Trans t={tr} i18nKey="TokensSection.createdDateExpiresDate2" values={{ createdAt: formatDate(t.createdAt), expiresAt: formatDate(t.expiresAt) }} components={{ span: <span /> }} />
                  <span>
                    {tr('TokensSection.lastUsed')} <span>{t.lastUsedAt ? formatDate(t.lastUsedAt) : tr('TokensSection.never')}</span>
                  </span>
                </span>
              </div>
              <Button size="sm" variant="ghost" aria-label={tr('TokensSection.revokeTokenName', { name: t.name })} onClick={() => setRevoking(t)}>
                {tr('TokensSection.revoke')}
              </Button>
            </li>
          ))}
        </ul>
      )}

      <div>
        <p className="tokens__text">{tr('TokensSection.useATokenAsA')}</p>
        <pre className="otsecret-code">{tr('TokensSection.curlHAuthorizationBearerShipir', { origin })}</pre>
      </div>

      {creating && <CreateTokenDialog onClose={() => setCreating(false)} />}
      {revoking && <RevokeDialog token={revoking} onClose={() => setRevoking(null)} />}
    </Card>
  )
}
