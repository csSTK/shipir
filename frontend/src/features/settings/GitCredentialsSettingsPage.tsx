import { FlaskConical, Pencil, Trash2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { Button, ConfirmDialog, Dialog, ResourceTable, TextField, type ResourceColumn, type RowActions } from '../../shared/ui/index.ts'
import {
  useCreateGitCredential,
  useDeleteGitCredential,
  useGitCredentials,
  useTestGitCredential,
  useUpdateGitCredential,
  type GitConnectionStatus,
  type GitCredential,
} from './api.ts'
import { SettingsPage } from './SettingsPage.tsx'
import { useTranslation } from 'react-i18next'
import { Trans } from '../../shared/i18n/Trans.tsx'
import i18n from 'i18next'
import './SettingsPage.css'

const FIELD_MAX = 255

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.code === 'CREDENTIAL_NAME_TAKEN') return i18n.t('settings:GitCredentialsSettingsPage.thisNameIsAlreadyIn')
    if (error.code === 'CREDENTIAL_IN_USE')
      return i18n.t('settings:GitCredentialsSettingsPage.thisCredentialIsStillUsed')
    if (error.code === 'ENCRYPTION_KEY_MISSING')
      return i18n.t('settings:GitCredentialsSettingsPage.theServerHasNoEncryption')
    if (error.code === 'TOKEN_REQUIRED') return i18n.t('settings:GitCredentialsSettingsPage.enterTheAccessToken')
    if (error.code === 'INVALID_REPO_URL') return i18n.t('settings:GitCredentialsSettingsPage.thisIsNotAValid')
    if (error.code === 'INVALID_CREDENTIAL_HOST')
      return i18n.t('settings:GitCredentialsSettingsPage.useOnlyAHostName')
    if (error.code === 'CREDENTIAL_HOST_MISMATCH')
      return i18n.t('settings:GitCredentialsSettingsPage.theRepositoryUrlIsNot')
    if (error.code === 'CREDENTIAL_NOT_FOUND') return i18n.t('settings:GitCredentialsSettingsPage.thisCredentialNoLongerExists')
    if (error.status === 409) return i18n.t('settings:GitCredentialsSettingsPage.thisNameIsAlreadyIn')
    if (error.status === 400 && error.details?.length) return error.details.join(' ')
    if (error.status === 400) return i18n.t('settings:GitCredentialsSettingsPage.checkTheEnteredValues')
  }
  return fallback
}

const TEST_MESSAGES: Record<GitConnectionStatus, { ok: boolean; text: string }> = {
  OK: { ok: true, get text() { return i18n.t('settings:GitCredentialsSettingsPage.connectionSuccessful') } },
  AUTH_FAILED: { ok: false, get text() { return i18n.t('settings:GitCredentialsSettingsPage.theServerRejectedTheUsername') } },
  NOT_FOUND: { ok: false, get text() { return i18n.t('settings:GitCredentialsSettingsPage.theRepositoryWasNotFound') } },
  UNREACHABLE: { ok: false, get text() { return i18n.t('settings:GitCredentialsSettingsPage.theRepositoryServerCouldNot') } },
}

function CredentialDialog({ credential, onClose }: { credential: GitCredential | null; onClose: () => void }) {
  const { t } = useTranslation('settings')
  const [name, setName] = useState(credential?.name ?? '')
  const [host, setHost] = useState(credential?.host ?? '')
  const [username, setUsername] = useState(credential?.username ?? '')
  const [token, setToken] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const create = useCreateGitCredential()
  const update = useUpdateGitCredential()
  const pending = create.isPending || update.isPending

  function submit(e: FormEvent) {
    e.preventDefault()
    const n = name.trim()
    const u = username.trim()
    const h = host.trim()
    if (n.length < 1 || n.length > FIELD_MAX) return setProblem(t('GitCredentialsSettingsPage.theNameMustBe1', { FIELD_MAX }))
    if (u.length < 1 || u.length > FIELD_MAX) return setProblem(t('GitCredentialsSettingsPage.theUsernameMustBe1', { FIELD_MAX }))
    if (h === '') return setProblem(t('GitCredentialsSettingsPage.enterTheHostTheToken'))
    if (!credential && token.trim() === '') return setProblem(t('GitCredentialsSettingsPage.enterTheAccessToken'))
    setProblem(null)
    const input = { name: n, host: h, username: u, ...(token.trim() !== '' ? { token: token.trim() } : {}) }
    const options = {
      onSuccess: onClose,
      onError: (err: unknown) => setProblem(errorMessage(err, t('GitCredentialsSettingsPage.couldNotSaveTheCredential'))),
    }
    if (credential) update.mutate({ id: credential.id, input }, options)
    else create.mutate(input, options)
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={credential ? t('GitCredentialsSettingsPage.editCredential') : t('GitCredentialsSettingsPage.addCredential')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t('GitCredentialsSettingsPage.cancel')}
          </Button>
          <Button variant="primary" type="submit" form="git-credential-form" loading={pending}>
            {t('GitCredentialsSettingsPage.save')}
          </Button>
        </>
      }
    >
      <form id="git-credential-form" onSubmit={submit} className="settings__form">
        <TextField label={t('GitCredentialsSettingsPage.name')} value={name} onChange={(e) => setName(e.target.value)} autoFocus autoComplete="off" />
        <TextField
          label={t('GitCredentialsSettingsPage.host')}
          value={host}
          onChange={(e) => setHost(e.target.value)}
          autoComplete="off"
          placeholder="github.com"
          help={t('GitCredentialsSettingsPage.theTokenIsOnlySent')}
        />
        <TextField label={t('GitCredentialsSettingsPage.username')} value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" />
        <TextField
          label={t('GitCredentialsSettingsPage.accessToken')}
          type="password"
          value={token}
          onChange={(e) => setToken(e.target.value)}
          autoComplete="off"
          placeholder={credential ? t('GitCredentialsSettingsPage.leaveEmptyToKeepThe') : ''}
          help={credential ? t('GitCredentialsSettingsPage.currentTokenhint', { tokenHint: credential.tokenHint }) : undefined}
        />
        {problem && (
          <p role="alert" className="settings__error">
            {problem}
          </p>
        )}
      </form>
    </Dialog>
  )
}

function TestDialog({ credential, onClose }: { credential: GitCredential; onClose: () => void }) {
  const { t } = useTranslation('settings')
  const [repoUrl, setRepoUrl] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const test = useTestGitCredential()
  const result = test.isPending ? null : test.data ? TEST_MESSAGES[test.data.status] : null

  function run(e: FormEvent) {
    e.preventDefault()
    if (repoUrl.trim() === '') return setProblem(t('GitCredentialsSettingsPage.enterTheRepositoryUrl'))
    setProblem(null)
    test.mutate(
      { id: credential.id, repoUrl: repoUrl.trim() },
      { onError: (err) => setProblem(errorMessage(err, t('GitCredentialsSettingsPage.couldNotRunTheTest'))) },
    )
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={t('GitCredentialsSettingsPage.testName', { name: credential.name })}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t('GitCredentialsSettingsPage.close')}
          </Button>
          <Button variant="primary" type="submit" form="git-test-form" loading={test.isPending}>
            {t('GitCredentialsSettingsPage.runTest')}
          </Button>
        </>
      }
    >
      <form id="git-test-form" onSubmit={run} className="settings__form">
        <TextField
          label={t('GitCredentialsSettingsPage.repositoryUrl')}
          type="url"
          value={repoUrl}
          onChange={(e) => setRepoUrl(e.target.value)}
          autoFocus
          autoComplete="off"
          placeholder="https://github.com/owner/repo.git"
        />
        {test.isPending && <p className="settings__muted">{t('GitCredentialsSettingsPage.testing')}</p>}
        {result && (
          <p role="status" className={result.ok ? 'settings__ok' : 'settings__error'}>
            {result.text}
          </p>
        )}
        {problem && (
          <p role="alert" className="settings__error">
            {problem}
          </p>
        )}
      </form>
    </Dialog>
  )
}

function DeleteDialog({ credential, onClose }: { credential: GitCredential; onClose: () => void }) {
  const { t } = useTranslation('settings')
  const [problem, setProblem] = useState<string | null>(null)
  const remove = useDeleteGitCredential()
  return (
    <ConfirmDialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={t('GitCredentialsSettingsPage.deleteCredential')}
      confirmLabel={t('GitCredentialsSettingsPage.delete')}
      danger
      loading={remove.isPending}
      onConfirm={() =>
        remove.mutate(credential.id, {
          onSuccess: onClose,
          onError: (err) => setProblem(errorMessage(err, t('GitCredentialsSettingsPage.couldNotDeleteTheCredential'))),
        })
      }
    >
      <p className="settings__text">
        <Trans t={t} i18nKey="GitCredentialsSettingsPage.deleteNameTheStoredToken" values={{ name: credential.name }} components={{ strong: <strong /> }} />
      </p>
      {problem && (
        <p role="alert" className="settings__error">
          {problem}
        </p>
      )}
    </ConfirmDialog>
  )
}

type DialogState =
  | { kind: 'edit'; credential: GitCredential | null }
  | { kind: 'test' | 'delete'; credential: GitCredential }
  | null

/** Settings > Git credentials: ADMIN management of stored Git access tokens. The caller restricts access to ADMIN. */
export function GitCredentialsSettingsPage() {
  const { t } = useTranslation('settings')
  const { data, isPending, isError, refetch } = useGitCredentials()
  const [dialog, setDialog] = useState<DialogState>(null)
  const close = () => setDialog(null)

  const columns: ResourceColumn<GitCredential>[] = [
    { id: 'name', header: t('GitCredentialsSettingsPage.name'), sortValue: (c) => c.name, defaultWidth: 220, hideable: false, render: (c) => <strong>{c.name}</strong> },
    { id: 'host', header: t('GitCredentialsSettingsPage.host'), sortValue: (c) => c.host ?? '', defaultWidth: 200, render: (c) => c.host ?? t('GitCredentialsSettingsPage.notSet') },
    { id: 'username', header: t('GitCredentialsSettingsPage.username'), sortValue: (c) => c.username, defaultWidth: 200, render: (c) => c.username },
    { id: 'token', header: t('GitCredentialsSettingsPage.token'), defaultWidth: 200, render: (c) => <span className="settings__mono">{c.tokenHint}</span> },
  ]
  const rowActions = (c: GitCredential): RowActions => ({
    primary: [
      { label: t('GitCredentialsSettingsPage.test'), icon: FlaskConical, onSelect: () => setDialog({ kind: 'test', credential: c }) },
      { label: t('GitCredentialsSettingsPage.edit'), icon: Pencil, onSelect: () => setDialog({ kind: 'edit', credential: c }) },
      { label: t('GitCredentialsSettingsPage.delete'), icon: Trash2, danger: true, onSelect: () => setDialog({ kind: 'delete', credential: c }) },
    ],
  })

  return (
    <SettingsPage
      wide
      title={t('GitCredentialsSettingsPage.gitCredentials')}
      subtitle={t('GitCredentialsSettingsPage.accessTokensForDeploymentsDeployed')}
      actions={
        <Button variant="primary" onClick={() => setDialog({ kind: 'edit', credential: null })}>
          {t('GitCredentialsSettingsPage.addCredential')}
        </Button>
      }
    >
      <ResourceTable
        label={t('GitCredentialsSettingsPage.credentials')}
        columns={columns}
        rows={data}
        rowKey={(c) => c.id}
        searchText={(c) => `${c.name} ${c.host ?? ''} ${c.username}`}
        defaultSort="name"
        emptyMessage={t('GitCredentialsSettingsPage.noCredentialsYet')}
        isPending={isPending}
        error={isError ? t('GitCredentialsSettingsPage.couldNotLoadCredentials') : null}
        onRetry={() => void refetch()}
        rowActions={rowActions}
        rowLabel={(c) => c.name}
      />
      {dialog?.kind === 'edit' && <CredentialDialog credential={dialog.credential} onClose={close} />}
      {dialog?.kind === 'test' && <TestDialog credential={dialog.credential} onClose={close} />}
      {dialog?.kind === 'delete' && <DeleteDialog credential={dialog.credential} onClose={close} />}
    </SettingsPage>
  )
}
