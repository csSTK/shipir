import { useEffect, useState, type FormEvent } from 'react'
import { Button, TextField } from '../../shared/ui/index.ts'
import { twoFactorErrorMessage } from './twoFactorErrors.ts'
import { useConfirmTwoFactor, useTwoFactorSetup, type TwoFactorSetup } from './api.ts'
import { QrCode } from './QrCode.tsx'
import { useTranslation } from 'react-i18next'
import { Trans } from '../../shared/i18n/Trans.tsx'
import i18n from 'i18next'
import './TwoFactorEnrollment.css'

function RecoveryCodes({ codes, onDone, doneLabel }: { codes: string[]; onDone: () => void; doneLabel: string }) {
  const { t } = useTranslation('auth')
  const [copied, setCopied] = useState(false)
  const text = codes.join('\n')

  function download() {
    const url = URL.createObjectURL(new Blob([t('TwoFactorEnrollment.shipirRecoveryCodesText', { text })], { type: 'text/plain' }))
    const link = document.createElement('a')
    link.href = url
    link.download = 'shipir-recovery-codes.txt'
    link.click()
    URL.revokeObjectURL(url)
  }
  async function copy() {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  return (
    <div className="tfa">
      <p className="tfa__text">
        {t('TwoFactorEnrollment.twoFactorAuthenticationIsOn')}
      </p>
      <ul className="tfa__codes">
        {codes.map((code) => (
          <li key={code}>
            <code>{code}</code>
          </li>
        ))}
      </ul>
      <div className="tfa__actions">
        <Button size="sm" onClick={download}>
          {t('TwoFactorEnrollment.downloadCodes')}
        </Button>
        <Button size="sm" onClick={() => void copy()}>
          {copied ? t('TwoFactorEnrollment.copied') : t('TwoFactorEnrollment.copyCodes')}
        </Button>
      </div>
      <Button variant="primary" onClick={onDone}>
        {doneLabel}
      </Button>
    </div>
  )
}

/**
 * Enrolment flow shared by Account and first-start setup: start, scan the QR code, confirm with a code,
 * then the one-time recovery codes. The secret is dropped from state once confirmed.
 */
export function TwoFactorEnrollment({
  onFinished,
  onConfirmed,
  finishLabel = i18n.t('auth:TwoFactorEnrollment.done'),
  autoStart = false,
}: {
  onFinished: () => void
  /** Fires when the code was accepted, before the recovery codes are dismissed. */
  onConfirmed?: () => void
  finishLabel?: string
  autoStart?: boolean
}) {
  const { t } = useTranslation('auth')
  const setupMutation = useTwoFactorSetup()
  const confirmMutation = useConfirmTwoFactor()
  const [setup, setSetup] = useState<TwoFactorSetup | null>(null)
  const [codes, setCodes] = useState<string[] | null>(null)
  const [code, setCode] = useState('')
  const { mutate: requestSetup } = setupMutation

  function start() {
    requestSetup(undefined, { onSuccess: setSetup })
  }
  useEffect(() => {
    if (autoStart) requestSetup(undefined, { onSuccess: setSetup })
  }, [autoStart, requestSetup])

  function confirm(event: FormEvent) {
    event.preventDefault()
    if (confirmMutation.isPending || code.trim() === '') return
    confirmMutation.mutate(code.trim(), {
      onSuccess: (result) => {
        setSetup(null)
        setCode('')
        setCodes(result.recoveryCodes)
        onConfirmed?.()
      },
    })
  }

  if (codes) return <RecoveryCodes codes={codes} onDone={onFinished} doneLabel={finishLabel} />

  if (!setup) {
    return (
      <div className="tfa">
        {setupMutation.isError && (
          <p role="alert" className="tfa__error">
            {twoFactorErrorMessage(setupMutation.error, t('TwoFactorEnrollment.couldNotStartTheSetup'))}
          </p>
        )}
        {setupMutation.isPending ? (
          <p className="tfa__text" role="status">
            {t('TwoFactorEnrollment.preparing')}
          </p>
        ) : (
          <Button variant="primary" onClick={start}>
            {t('TwoFactorEnrollment.enableTwoFactorAuthentication')}
          </Button>
        )}
      </div>
    )
  }

  return (
    <form className="tfa" onSubmit={confirm}>
      <p className="tfa__text">{t('TwoFactorEnrollment.scanTheQrCodeWith')}</p>
      <QrCode value={setup.otpauthUri} label={t('TwoFactorEnrollment.qrCodeForTwoFactor')} />
      <p className="tfa__text">
        <Trans t={t} i18nKey="TwoFactorEnrollment.keySecret" values={{ secret: setup.secret }} components={{ code: <code className="tfa__secret" /> }} />
      </p>
      <TextField
        label={t('TwoFactorEnrollment.verificationCode')}
        value={code}
        onChange={(e) => setCode(e.target.value)}
        inputMode="numeric"
        autoComplete="one-time-code"
        autoFocus
      />
      {confirmMutation.isError && (
        <p role="alert" className="tfa__error">
          {twoFactorErrorMessage(confirmMutation.error, t('TwoFactorEnrollment.couldNotConfirmTheCode'))}
        </p>
      )}
      <Button variant="primary" type="submit" loading={confirmMutation.isPending}>
        {t('TwoFactorEnrollment.confirm')}
      </Button>
    </form>
  )
}
