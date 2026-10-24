import { useEffect, useState, type FormEvent } from 'react'
import { Button, TextField } from '../../shared/ui/index.ts'
import { twoFactorErrorMessage } from './twoFactorErrors.ts'
import { useConfirmTwoFactor, useTwoFactorSetup, type TwoFactorSetup } from './api.ts'
import { QrCode } from './QrCode.tsx'
import './TwoFactorEnrollment.css'

function RecoveryCodes({ codes, onDone, doneLabel }: { codes: string[]; onDone: () => void; doneLabel: string }) {
  const [copied, setCopied] = useState(false)
  const text = codes.join('\n')

  function download() {
    const url = URL.createObjectURL(new Blob([`Shipir recovery codes\n\n${text}\n`], { type: 'text/plain' }))
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
        Two-factor authentication is on. Store these recovery codes somewhere safe: each works once if you lose your
        authenticator. They are shown only now.
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
          Download codes
        </Button>
        <Button size="sm" onClick={() => void copy()}>
          {copied ? 'Copied' : 'Copy codes'}
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
  finishLabel = 'Done',
  autoStart = false,
}: {
  onFinished: () => void
  /** Fires when the code was accepted, before the recovery codes are dismissed. */
  onConfirmed?: () => void
  finishLabel?: string
  autoStart?: boolean
}) {
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
            {twoFactorErrorMessage(setupMutation.error, 'Could not start the setup. Try again.')}
          </p>
        )}
        {setupMutation.isPending ? (
          <p className="tfa__text" role="status">
            Preparing…
          </p>
        ) : (
          <Button variant="primary" onClick={start}>
            Enable two-factor authentication
          </Button>
        )}
      </div>
    )
  }

  return (
    <form className="tfa" onSubmit={confirm}>
      <p className="tfa__text">Scan the QR code with an authenticator app, or enter the key by hand. Then enter the 6-digit code it shows.</p>
      <QrCode value={setup.otpauthUri} label="QR code for two-factor setup" />
      <p className="tfa__text">
        Key: <code className="tfa__secret">{setup.secret}</code>
      </p>
      <TextField
        label="Verification code"
        value={code}
        onChange={(e) => setCode(e.target.value)}
        inputMode="numeric"
        autoComplete="one-time-code"
        autoFocus
      />
      {confirmMutation.isError && (
        <p role="alert" className="tfa__error">
          {twoFactorErrorMessage(confirmMutation.error, 'Could not confirm the code. Try again.')}
        </p>
      )}
      <Button variant="primary" type="submit" loading={confirmMutation.isPending}>
        Confirm
      </Button>
    </form>
  )
}
