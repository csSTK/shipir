import { FlaskConical, Pencil, Trash2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { Button, ConfirmDialog, Dialog, ResourceTable, TextField, type ResourceColumn, type RowActions } from '../../shared/ui/index.ts'
import {
  useCreateRegistry,
  useDeleteRegistry,
  useRegistries,
  useTestRegistry,
  useUpdateRegistry,
  type RegistryCredential,
  type RegistryTestStatus,
} from './api.ts'
import { SettingsPage } from './SettingsPage.tsx'
import { useTranslation } from 'react-i18next'
import { Trans } from '../../shared/i18n/Trans.tsx'
import i18n from 'i18next'
import './SettingsPage.css'

const FIELD_MAX = 255

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.code === 'REGISTRY_NAME_TAKEN') return i18n.t('settings:RegistriesSettingsPage.thisNameIsAlreadyIn')
    if (error.code === 'REGISTRY_HOST_TAKEN') return i18n.t('settings:RegistriesSettingsPage.aCredentialForThisRegistry')
    if (error.code === 'INVALID_REGISTRY_HOST') return i18n.t('settings:RegistriesSettingsPage.thisIsNotAValid')
    if (error.code === 'ENCRYPTION_KEY_MISSING')
      return i18n.t('settings:RegistriesSettingsPage.theServerHasNoEncryption')
    if (error.code === 'SECRET_REQUIRED') return i18n.t('settings:RegistriesSettingsPage.enterThePasswordOrToken')
    if (error.code === 'REGISTRY_CREDENTIAL_NOT_FOUND') return i18n.t('settings:RegistriesSettingsPage.thisCredentialNoLongerExists')
    if (error.status === 400 && error.details?.length) return error.details.join(' ')
    if (error.status === 400) return i18n.t('settings:RegistriesSettingsPage.checkTheEnteredValues')
  }
  return fallback
}

const TEST_MESSAGES: Record<RegistryTestStatus, { ok: boolean; text: string }> = {
  OK: { ok: true, get text() { return i18n.t('settings:RegistriesSettingsPage.loginSuccessful') } },
  AUTH_FAILED: { ok: false, get text() { return i18n.t('settings:RegistriesSettingsPage.theRegistryRejectedTheUsername') } },
  UNREACHABLE: { ok: false, get text() { return i18n.t('settings:RegistriesSettingsPage.theRegistryCouldNotBe') } },
}

function CredentialDialog({ credential, onClose }: { credential: RegistryCredential | null; onClose: () => void }) {
  const { t } = useTranslation('settings')
  const [name, setName] = useState(credential?.name ?? '')
  const [registryHost, setRegistryHost] = useState(credential?.registryHost ?? '')
  const [username, setUsername] = useState(credential?.username ?? '')
  const [secret, setSecret] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const create = useCreateRegistry()
  const update = useUpdateRegistry()
  const pending = create.isPending || update.isPending

  function submit(e: FormEvent) {
    e.preventDefault()
    const n = name.trim()
    const h = registryHost.trim()
    const u = username.trim()
    if (n.length < 1 || n.length > FIELD_MAX) return setProblem(t('RegistriesSettingsPage.theNameMustBe1', { FIELD_MAX }))
    if (h.length < 1 || h.length > FIELD_MAX) return setProblem(t('RegistriesSettingsPage.theRegistryHostMustBe', { FIELD_MAX }))
    if (u.length < 1 || u.length > FIELD_MAX) return setProblem(t('RegistriesSettingsPage.theUsernameMustBe1', { FIELD_MAX }))
    if (!credential && secret.trim() === '') return setProblem(t('RegistriesSettingsPage.enterThePasswordOrToken'))
    setProblem(null)
    const input = { name: n, registryHost: h, username: u, ...(secret.trim() !== '' ? { secret: secret.trim() } : {}) }
    const options = {
      onSuccess: onClose,
      onError: (err: unknown) => setProblem(errorMessage(err, t('RegistriesSettingsPage.couldNotSaveTheCredential'))),
    }
    if (credential) update.mutate({ id: credential.id, input }, options)
    else create.mutate(input, options)
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={credential ? t('RegistriesSettingsPage.editRegistry') : t('RegistriesSettingsPage.addRegistry')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t('RegistriesSettingsPage.cancel')}
          </Button>
          <Button variant="primary" type="submit" form="registry-form" loading={pending}>
            {t('RegistriesSettingsPage.save')}
          </Button>
        </>
      }
    >
      <form id="registry-form" onSubmit={submit} className="settings__form">
        <TextField label={t('RegistriesSettingsPage.name')} value={name} onChange={(e) => setName(e.target.value)} autoFocus autoComplete="off" />
        <TextField label={t('RegistriesSettingsPage.registryHost')} value={registryHost} onChange={(e) => setRegistryHost(e.target.value)} autoComplete="off" placeholder="ghcr.io" />
        <TextField label={t('RegistriesSettingsPage.username')} value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" />
        <TextField
          label={t('RegistriesSettingsPage.passwordOrToken')}
          type="password"
          value={secret}
          onChange={(e) => setSecret(e.target.value)}
          autoComplete="off"
          placeholder={credential ? t('RegistriesSettingsPage.leaveEmptyToKeepThe') : ''}
          help={credential ? t('RegistriesSettingsPage.currentSecrethint', { secretHint: credential.secretHint }) : undefined}
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

function TestDialog({ credential, onClose }: { credential: RegistryCredential; onClose: () => void }) {
  const { t } = useTranslation('settings')
  const [problem, setProblem] = useState<string | null>(null)
  const test = useTestRegistry()
  const result = test.isPending ? null : test.data ? TEST_MESSAGES[test.data.status] : null

  function run(e: FormEvent) {
    e.preventDefault()
    setProblem(null)
    test.mutate(credential.id, { onError: (err) => setProblem(errorMessage(err, t('RegistriesSettingsPage.couldNotRunTheTest'))) })
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={t('RegistriesSettingsPage.testName', { name: credential.name })}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t('RegistriesSettingsPage.close')}
          </Button>
          <Button variant="primary" type="submit" form="registry-test-form" loading={test.isPending}>
            {t('RegistriesSettingsPage.runTest')}
          </Button>
        </>
      }
    >
      <form id="registry-test-form" onSubmit={run} className="settings__form">
        <p className="settings__text">
          <Trans t={t} i18nKey="RegistriesSettingsPage.logsInToRegistryhostAs" values={{ registryHost: credential.registryHost, username: credential.username }} components={{ strong: <strong /> }} />
        </p>
        {test.isPending && <p className="settings__muted">{t('RegistriesSettingsPage.testing')}</p>}
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

function DeleteDialog({ credential, onClose }: { credential: RegistryCredential; onClose: () => void }) {
  const { t } = useTranslation('settings')
  const [problem, setProblem] = useState<string | null>(null)
  const remove = useDeleteRegistry()
  return (
    <ConfirmDialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={t('RegistriesSettingsPage.deleteRegistry')}
      confirmLabel={t('RegistriesSettingsPage.delete')}
      danger
      loading={remove.isPending}
      onConfirm={() =>
        remove.mutate(credential.id, {
          onSuccess: onClose,
          onError: (err) => setProblem(errorMessage(err, t('RegistriesSettingsPage.couldNotDeleteTheCredential'))),
        })
      }
    >
      <p className="settings__text">
        <Trans t={t} i18nKey="RegistriesSettingsPage.deleteNameTheStoredSecret" values={{ name: credential.name }} components={{ strong: <strong /> }} />
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
  | { kind: 'edit'; credential: RegistryCredential | null }
  | { kind: 'test' | 'delete'; credential: RegistryCredential }
  | null

/** Settings > Registries: ADMIN management of private registry logins. The caller restricts access to ADMIN. */
export function RegistriesSettingsPage() {
  const { t } = useTranslation('settings')
  const { data, isPending, isError, refetch } = useRegistries()
  const [dialog, setDialog] = useState<DialogState>(null)
  const close = () => setDialog(null)

  const columns: ResourceColumn<RegistryCredential>[] = [
    { id: 'name', header: t('RegistriesSettingsPage.name'), sortValue: (c) => c.name, defaultWidth: 200, hideable: false, render: (c) => <strong>{c.name}</strong> },
    { id: 'host', header: t('RegistriesSettingsPage.host'), sortValue: (c) => c.registryHost, defaultWidth: 220, render: (c) => <span className="settings__mono">{c.registryHost}</span> },
    { id: 'username', header: t('RegistriesSettingsPage.username'), sortValue: (c) => c.username, defaultWidth: 180, render: (c) => c.username },
    { id: 'secret', header: t('RegistriesSettingsPage.secret'), defaultWidth: 180, render: (c) => <span className="settings__mono">{c.secretHint}</span> },
  ]
  const rowActions = (c: RegistryCredential): RowActions => ({
    primary: [
      { label: t('RegistriesSettingsPage.test'), icon: FlaskConical, onSelect: () => setDialog({ kind: 'test', credential: c }) },
      { label: t('RegistriesSettingsPage.edit'), icon: Pencil, onSelect: () => setDialog({ kind: 'edit', credential: c }) },
      { label: t('RegistriesSettingsPage.delete'), icon: Trash2, danger: true, onSelect: () => setDialog({ kind: 'delete', credential: c }) },
    ],
  })

  return (
    <SettingsPage
      wide
      title={t('RegistriesSettingsPage.registries')}
      subtitle={t('RegistriesSettingsPage.loginsForPullingImagesFrom')}
      actions={
        <Button variant="primary" onClick={() => setDialog({ kind: 'edit', credential: null })}>
          {t('RegistriesSettingsPage.addRegistry')}
        </Button>
      }
    >
      <ResourceTable
        label={t('RegistriesSettingsPage.registries2')}
        columns={columns}
        rows={data}
        rowKey={(c) => c.id}
        searchText={(c) => `${c.name} ${c.registryHost} ${c.username}`}
        defaultSort="name"
        emptyMessage={t('RegistriesSettingsPage.noRegistriesYet')}
        isPending={isPending}
        error={isError ? t('RegistriesSettingsPage.couldNotLoadRegistries') : null}
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
