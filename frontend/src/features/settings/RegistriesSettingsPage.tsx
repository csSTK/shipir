import { FlaskConical, Pencil, Trash2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { Button, ConfirmDialog, Dialog, ResourceTable, TextField, type ResourceColumn } from '../../shared/ui/index.ts'
import type { RowActions } from '../../shared/ui/ResourceTable.tsx'
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
import './SettingsPage.css'

const FIELD_MAX = 255

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.code === 'REGISTRY_NAME_TAKEN') return 'This name is already in use.'
    if (error.code === 'REGISTRY_HOST_TAKEN') return 'A credential for this registry host already exists.'
    if (error.code === 'INVALID_REGISTRY_HOST') return 'This is not a valid registry host (for example ghcr.io or registry.example.com:5000).'
    if (error.code === 'ENCRYPTION_KEY_MISSING')
      return 'The server has no encryption key. Set SHIPIR_ENCRYPTION_KEY to store registry secrets.'
    if (error.code === 'SECRET_REQUIRED') return 'Enter the password or token.'
    if (error.code === 'REGISTRY_CREDENTIAL_NOT_FOUND') return 'This credential no longer exists.'
    if (error.status === 400 && error.details?.length) return error.details.join(' ')
    if (error.status === 400) return 'Check the entered values.'
  }
  return fallback
}

const TEST_MESSAGES: Record<RegistryTestStatus, { ok: boolean; text: string }> = {
  OK: { ok: true, text: 'Login successful.' },
  AUTH_FAILED: { ok: false, text: 'The registry rejected the username or secret.' },
  UNREACHABLE: { ok: false, text: 'The registry could not be reached.' },
}

function CredentialDialog({ credential, onClose }: { credential: RegistryCredential | null; onClose: () => void }) {
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
    if (n.length < 1 || n.length > FIELD_MAX) return setProblem(`The name must be 1 to ${FIELD_MAX} characters.`)
    if (h.length < 1 || h.length > FIELD_MAX) return setProblem(`The registry host must be 1 to ${FIELD_MAX} characters.`)
    if (u.length < 1 || u.length > FIELD_MAX) return setProblem(`The username must be 1 to ${FIELD_MAX} characters.`)
    if (!credential && secret.trim() === '') return setProblem('Enter the password or token.')
    setProblem(null)
    const input = { name: n, registryHost: h, username: u, ...(secret.trim() !== '' ? { secret: secret.trim() } : {}) }
    const options = {
      onSuccess: onClose,
      onError: (err: unknown) => setProblem(errorMessage(err, 'Could not save the credential. Try again.')),
    }
    if (credential) update.mutate({ id: credential.id, input }, options)
    else create.mutate(input, options)
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={credential ? 'Edit registry' : 'Add registry'}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" form="registry-form" loading={pending}>
            Save
          </Button>
        </>
      }
    >
      <form id="registry-form" onSubmit={submit} className="settings__form">
        <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} autoFocus autoComplete="off" />
        <TextField label="Registry host" value={registryHost} onChange={(e) => setRegistryHost(e.target.value)} autoComplete="off" placeholder="ghcr.io" />
        <TextField label="Username" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" />
        <TextField
          label="Password or token"
          type="password"
          value={secret}
          onChange={(e) => setSecret(e.target.value)}
          autoComplete="off"
          placeholder={credential ? 'Leave empty to keep the current secret' : ''}
          help={credential ? `Current: ${credential.secretHint}` : undefined}
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
  const [problem, setProblem] = useState<string | null>(null)
  const test = useTestRegistry()
  const result = test.isPending ? null : test.data ? TEST_MESSAGES[test.data.status] : null

  function run(e: FormEvent) {
    e.preventDefault()
    setProblem(null)
    test.mutate(credential.id, { onError: (err) => setProblem(errorMessage(err, 'Could not run the test. Try again.')) })
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={`Test ${credential.name}`}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Close
          </Button>
          <Button variant="primary" type="submit" form="registry-test-form" loading={test.isPending}>
            Run test
          </Button>
        </>
      }
    >
      <form id="registry-test-form" onSubmit={run} className="settings__form">
        <p className="settings__text">
          Logs in to <strong>{credential.registryHost}</strong> as {credential.username}.
        </p>
        {test.isPending && <p className="settings__muted">Testing…</p>}
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
  const [problem, setProblem] = useState<string | null>(null)
  const remove = useDeleteRegistry()
  return (
    <ConfirmDialog
      open
      onOpenChange={(open) => !open && onClose()}
      title="Delete registry"
      confirmLabel="Delete"
      danger
      loading={remove.isPending}
      onConfirm={() =>
        remove.mutate(credential.id, {
          onSuccess: onClose,
          onError: (err) => setProblem(errorMessage(err, 'Could not delete the credential. Try again.')),
        })
      }
    >
      <p className="settings__text">
        Delete <strong>{credential.name}</strong>? The stored secret is removed and pulls from this registry stop using it.
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
  const { data, isPending, isError, refetch } = useRegistries()
  const [dialog, setDialog] = useState<DialogState>(null)
  const close = () => setDialog(null)

  const columns: ResourceColumn<RegistryCredential>[] = [
    { id: 'name', header: 'Name', sortValue: (c) => c.name, defaultWidth: 200, hideable: false, render: (c) => <strong>{c.name}</strong> },
    { id: 'host', header: 'Host', sortValue: (c) => c.registryHost, defaultWidth: 220, render: (c) => <span className="settings__mono">{c.registryHost}</span> },
    { id: 'username', header: 'Username', sortValue: (c) => c.username, defaultWidth: 180, render: (c) => c.username },
    { id: 'secret', header: 'Secret', defaultWidth: 180, render: (c) => <span className="settings__mono">{c.secretHint}</span> },
  ]
  const rowActions = (c: RegistryCredential): RowActions => ({
    primary: [
      { label: 'Test', icon: FlaskConical, onSelect: () => setDialog({ kind: 'test', credential: c }) },
      { label: 'Edit', icon: Pencil, onSelect: () => setDialog({ kind: 'edit', credential: c }) },
      { label: 'Delete', icon: Trash2, danger: true, onSelect: () => setDialog({ kind: 'delete', credential: c }) },
    ],
  })

  return (
    <SettingsPage
      wide
      title="Registries"
      subtitle="Logins for pulling images from private registries. Saved secrets are never shown again."
      actions={
        <Button variant="primary" onClick={() => setDialog({ kind: 'edit', credential: null })}>
          Add registry
        </Button>
      }
    >
      <ResourceTable
        label="registries"
        columns={columns}
        rows={data}
        rowKey={(c) => c.id}
        searchText={(c) => `${c.name} ${c.registryHost} ${c.username}`}
        defaultSort="name"
        emptyMessage="No registries yet."
        isPending={isPending}
        error={isError ? 'Could not load registries.' : null}
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
