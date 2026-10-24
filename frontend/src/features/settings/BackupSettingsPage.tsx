import { useState, type FormEvent } from 'react'
import { Button, Card, TextField } from '../../shared/ui/index.ts'
import { ApiError } from '../../shared/api/client.ts'
import { useCreateBackup } from './api.ts'
import { SettingsPage } from './SettingsPage.tsx'
import './SettingsPage.css'

const MIN_LENGTH = 12
const MAX_LENGTH = 128

/** Mirrors the backend rule (12-128 characters) and the confirmation match. Null = valid. */
function validatePassphrase(passphrase: string, confirm: string): string | null {
  if (passphrase.length < MIN_LENGTH) return `Use at least ${MIN_LENGTH} characters.`
  if (passphrase.length > MAX_LENGTH) return `Use at most ${MAX_LENGTH} characters.`
  if (passphrase !== confirm) return 'The passphrases do not match.'
  return null
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return 'You are not allowed to create backups.'
    if (error.details?.length) return error.details.join(' ')
  }
  return 'Could not create the backup. Try again.'
}

function BackupForm() {
  const [passphrase, setPassphrase] = useState('')
  const [confirm, setConfirm] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  const backup = useCreateBackup()

  function submit(e: FormEvent) {
    e.preventDefault()
    if (backup.isPending) return
    setDone(false)
    const invalid = validatePassphrase(passphrase, confirm)
    if (invalid) {
      setProblem(invalid)
      return
    }
    setProblem(null)
    backup.mutate(passphrase, {
      onSuccess: () => {
        setPassphrase('')
        setConfirm('')
        setDone(true)
      },
      onError: (err) => setProblem(errorMessage(err)),
    })
  }

  return (
    <Card>
      <form className="settings__form" onSubmit={submit} noValidate>
        <TextField
          label="Passphrase"
          type="password"
          autoComplete="new-password"
          maxLength={MAX_LENGTH}
          value={passphrase}
          help={`At least ${MIN_LENGTH} characters.`}
          onChange={(e) => {
            setPassphrase(e.target.value)
            setDone(false)
          }}
        />
        <TextField
          label="Confirm passphrase"
          type="password"
          autoComplete="new-password"
          maxLength={MAX_LENGTH}
          value={confirm}
          onChange={(e) => {
            setConfirm(e.target.value)
            setDone(false)
          }}
        />
        {problem && (
          <p role="alert" className="settings__error">
            {problem}
          </p>
        )}
        {done && (
          <p role="status" className="settings__ok">
            Backup downloaded.
          </p>
        )}
        <div className="settings__actions">
          <Button variant="primary" type="submit" loading={backup.isPending}>
            {backup.isPending ? 'Creating backup…' : 'Download backup'}
          </Button>
        </div>
      </form>
    </Card>
  )
}

/** Settings > Backup: ADMIN downloads an encrypted export of the database and stack files. */
export function BackupSettingsPage() {
  return (
    <SettingsPage
      title="Backup"
      subtitle="Download an encrypted copy of the Shipir database and stack files. The passphrase you choose protects the file."
    >
      <Card role="note" className="settings__form">
        <p className="settings__text">
          <strong>The passphrase cannot be recovered.</strong> Without it the backup cannot be opened, so store it
          somewhere safe.
        </p>
        <p className="settings__muted">
          The encryption key (<code>SHIPIR_ENCRYPTION_KEY</code>) is not part of the backup. Keep it separately: the
          encrypted values in the database need it after a restore.
        </p>
      </Card>
      <BackupForm />
      <p className="settings__muted">
        To restore a backup, follow <em>Backup &amp; restore</em> in the README.
      </p>
    </SettingsPage>
  )
}
