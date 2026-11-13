import { useState, type FormEvent } from 'react'
import { Button, Card, TextField } from '../../shared/ui/index.ts'
import { ApiError } from '../../shared/api/client.ts'
import { StepUpDialog } from '../auth/index.ts'
import { useCreateBackup } from './api.ts'
import { SettingsPage } from './SettingsPage.tsx'
import { useTranslation } from 'react-i18next'
import { Trans } from '../../shared/i18n/Trans.tsx'
import i18n from 'i18next'
import './SettingsPage.css'

const MIN_LENGTH = 12
const MAX_LENGTH = 128

/** Mirrors the backend rule (12-128 characters) and the confirmation match. Null = valid. */
function validatePassphrase(passphrase: string, confirm: string): string | null {
  if (passphrase.length < MIN_LENGTH) return i18n.t('settings:BackupSettingsPage.useAtLeastMinLength', { MIN_LENGTH })
  if (passphrase.length > MAX_LENGTH) return i18n.t('settings:BackupSettingsPage.useAtMostMaxLength', { MAX_LENGTH })
  if (passphrase !== confirm) return i18n.t('settings:BackupSettingsPage.thePassphrasesDoNotMatch')
  return null
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return i18n.t('settings:BackupSettingsPage.youAreNotAllowedTo')
    if (error.details?.length) return error.details.join(' ')
  }
  return i18n.t('settings:BackupSettingsPage.couldNotCreateTheBackup')
}

function BackupForm() {
  const { t } = useTranslation('settings')
  const [passphrase, setPassphrase] = useState('')
  const [confirm, setConfirm] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  // The passphrase is valid; the export waits for the user to confirm their identity.
  const [confirming, setConfirming] = useState(false)
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
    setConfirming(true)
  }

  return (
    <Card>
      {confirming && (
        <StepUpDialog
          onCancel={() => setConfirming(false)}
          onConfirm={async (stepUp) => {
            await backup.mutateAsync({ passphrase, stepUp })
            setConfirming(false)
            setPassphrase('')
            setConfirm('')
            setDone(true)
          }}
          onFailure={(err) => {
            setConfirming(false)
            setProblem(errorMessage(err))
          }}
        />
      )}
      <form className="settings__form" onSubmit={submit} noValidate>
        <TextField
          label={t('BackupSettingsPage.passphrase')}
          type="password"
          autoComplete="new-password"
          maxLength={MAX_LENGTH}
          value={passphrase}
          help={t('BackupSettingsPage.atLeastMinLengthCharacters', { MIN_LENGTH })}
          onChange={(e) => {
            setPassphrase(e.target.value)
            setDone(false)
          }}
        />
        <TextField
          label={t('BackupSettingsPage.confirmPassphrase')}
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
            {t('BackupSettingsPage.backupDownloaded')}
          </p>
        )}
        <div className="settings__actions">
          <Button variant="primary" type="submit" loading={backup.isPending}>
            {backup.isPending ? t('BackupSettingsPage.creatingBackup') : t('BackupSettingsPage.downloadBackup')}
          </Button>
        </div>
      </form>
    </Card>
  )
}

/** Settings > Backup: ADMIN downloads an encrypted export of the database and stack files. */
export function BackupSettingsPage() {
  const { t } = useTranslation('settings')
  return (
    <SettingsPage
      title={t('BackupSettingsPage.backup')}
      subtitle={t('BackupSettingsPage.downloadAnEncryptedCopyOf')}
    >
      <Card role="note" className="settings__form">
        <p className="settings__text">
          <Trans t={t} i18nKey="BackupSettingsPage.thePassphraseCannotBeRecovered" components={{ strong: <strong /> }} />
        </p>
        <p className="settings__muted">
          <Trans t={t} i18nKey="BackupSettingsPage.theEncryptionKeyShipirEncryption" components={{ code: <code /> }} />
        </p>
      </Card>
      <BackupForm />
      <p className="settings__muted">
        <Trans t={t} i18nKey="BackupSettingsPage.toRestoreABackupFollow" components={{ em: <em /> }} />
      </p>
    </SettingsPage>
  )
}
