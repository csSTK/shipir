import { useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { useCreateBackup } from './api.ts'
import './BackupSettingsPage.css'

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

/** Settings > Backup: ADMIN downloads an encrypted export of the database and stack files. */
export function BackupSettingsPage() {
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
    <section className="bk">
      <h2 className="bk-title">Backup</h2>
      <p className="bk-lead">
        Download an encrypted copy of the Shipir database and stack files. The passphrase you choose protects the file.
      </p>

      <div className="bk-note" role="note">
        <p>
          <strong>The passphrase cannot be recovered.</strong> Without it the backup cannot be opened, so store it
          somewhere safe.
        </p>
        <p>
          The encryption key (<code>SHIPIR_ENCRYPTION_KEY</code>) is not part of the backup. Keep it separately: the
          encrypted values in the database need it after a restore.
        </p>
      </div>

      <form className="bk-form" onSubmit={submit} noValidate>
        <label className="bk-field">
          <span>Passphrase</span>
          <input
            type="password"
            autoComplete="new-password"
            maxLength={MAX_LENGTH}
            value={passphrase}
            aria-describedby="bk-passphrase-hint"
            onChange={(e) => {
              setPassphrase(e.target.value)
              setDone(false)
            }}
          />
        </label>
        <p id="bk-passphrase-hint" className="bk-muted">
          At least {MIN_LENGTH} characters.
        </p>
        <label className="bk-field">
          <span>Confirm passphrase</span>
          <input
            type="password"
            autoComplete="new-password"
            maxLength={MAX_LENGTH}
            value={confirm}
            onChange={(e) => {
              setConfirm(e.target.value)
              setDone(false)
            }}
          />
        </label>

        {problem && (
          <p role="alert" className="bk-error">
            {problem}
          </p>
        )}
        {done && (
          <p role="status" className="bk-ok">
            Backup downloaded.
          </p>
        )}

        <div className="bk-actions">
          <button type="submit" className="bk-btn bk-btn--primary" disabled={backup.isPending}>
            {backup.isPending ? 'Creating backup…' : 'Download backup'}
          </button>
        </div>
      </form>

      <p className="bk-muted">
        To restore a backup, follow <em>Backup &amp; restore</em> in the README.
      </p>
    </section>
  )
}
