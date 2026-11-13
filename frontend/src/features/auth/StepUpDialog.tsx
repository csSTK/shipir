import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, Dialog, Skeleton, TextField } from '../../shared/ui/index.ts'
import {
  isSsoStepUpRequired,
  isStepUpError,
  startSsoReauth,
  stepUpErrorMessage,
  useStepUpStatus,
  type StepUpCredentials,
} from './stepUp.ts'

interface StepUpDialogProps {
  /** Dialog title; defaults to "Confirm it's you". */
  title?: string
  onCancel: () => void
  /**
   * Runs the guarded action with the credentials. It must reject with the request's `ApiError` when the server
   * refuses. A step-up refusal stays in this dialog (the user can retry); any other error goes to `onFailure`.
   */
  onConfirm: (credentials: StepUpCredentials) => Promise<unknown>
  /** Called for an error that is not a step-up refusal; the caller shows it and closes the dialog. */
  onFailure: (error: unknown) => void
}

/**
 * Asks the signed-in user to prove again that it is them, before a sensitive action (backup export, API token
 * creation, ...). Local users enter their password (and a code when two-factor is on); SSO users are sent to the
 * identity provider to log in again. Mount it only while the confirmation is open.
 */
export function StepUpDialog({ title, onCancel, onConfirm, onFailure }: StepUpDialogProps) {
  const { t } = useTranslation('auth')
  const status = useStepUpStatus()
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [pending, setPending] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  // The server may find a "fresh" SSO sign-in stale after all; then only the provider button helps.
  const [ssoStale, setSsoStale] = useState(false)

  async function run(credentials: StepUpCredentials) {
    if (pending) return
    setPending(true)
    setProblem(null)
    try {
      await onConfirm(credentials)
    } catch (error) {
      if (isStepUpError(error)) {
        if (isSsoStepUpRequired(error)) setSsoStale(true)
        setPassword('')
        setCode('')
        setProblem(stepUpErrorMessage(error))
        setPending(false)
      } else {
        onFailure(error)
      }
    }
  }

  const data = status.data
  const sso = data?.method === 'SSO'
  const needsProvider = sso && (!data.fresh || ssoStale)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!password || (data?.totpRequired && !code.trim())) return
    void run({ password, ...(data?.totpRequired ? { code: code.trim() } : {}) })
  }

  let footerAction = null
  if (data) {
    if (needsProvider) {
      footerAction = (
        <Button variant="primary" onClick={startSsoReauth}>
          {t('StepUpDialog.ssoReauthButton')}
        </Button>
      )
    } else if (sso) {
      footerAction = (
        <Button variant="primary" loading={pending} onClick={() => void run({})}>
          {t('StepUpDialog.continue')}
        </Button>
      )
    } else {
      footerAction = (
        <Button
          variant="primary"
          type="submit"
          form="step-up-form"
          loading={pending}
          disabled={!password || (data.totpRequired && !code.trim())}
        >
          {t('StepUpDialog.confirm')}
        </Button>
      )
    }
  }

  return (
    <Dialog
      open
      size="lg"
      dismissible={!pending}
      onOpenChange={(open) => !open && onCancel()}
      title={title ?? t('StepUpDialog.confirmItsYou')}
      footer={
        <>
          <Button variant="ghost" onClick={onCancel} disabled={pending}>
            {t('StepUpDialog.cancel')}
          </Button>
          {footerAction}
        </>
      }
    >
      {status.isPending && (
        <div aria-busy="true" aria-label={t('StepUpDialog.loading')}>
          <Skeleton height="var(--space-5)" />
        </div>
      )}
      {status.isError && (
        <p role="alert" className="ui-error">
          {t('StepUpDialog.couldNotLoad')}
        </p>
      )}
      {data && sso && (
        <p className="ui-dialog-text">{needsProvider ? t('StepUpDialog.ssoReauthText') : t('StepUpDialog.ssoFreshText')}</p>
      )}
      {data && !sso && (
        <form id="step-up-form" className="ui-form" onSubmit={submit}>
          <p className="ui-dialog-text">
            {data.totpRequired ? t('StepUpDialog.enterYourPasswordAndCode') : t('StepUpDialog.enterYourPasswordToContinue')}
          </p>
          <TextField
            label={t('StepUpDialog.password')}
            type="password"
            autoComplete="current-password"
            maxLength={1024}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          {data.totpRequired && (
            <TextField
              label={t('StepUpDialog.authenticationCode')}
              autoComplete="one-time-code"
              inputMode="text"
              maxLength={64}
              value={code}
              onChange={(e) => setCode(e.target.value)}
            />
          )}
        </form>
      )}
      {problem && (
        <p role="alert" className="ui-error">
          {problem}
        </p>
      )}
    </Dialog>
  )
}
