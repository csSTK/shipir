import { useId, useState, type ChangeEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { FileUp } from 'lucide-react'
import { Button, Dialog, FormField, TextField } from '../../../shared/ui/index.ts'
import { describeCertificateError, type CertificateError } from './certificateErrors.ts'
import { useRenewCertificate, useUploadCertificate, type ProxyCertificate } from './api.ts'

/** Server limits: 64 KB chain, 16 KB key. */
const MAX_CHAIN_BYTES = 64 * 1024
const MAX_KEY_BYTES = 16 * 1024

interface Props {
  hostId: string
  /** The certificate to renew; null uploads a new one. */
  renewing: ProxyCertificate | null
  onClose: () => void
}

interface PemFieldProps {
  label: string
  fileLabel: string
  value: string
  onChange: (value: string) => void
  error: string | null
  help: string
  secret?: boolean
  /** Largest file the server accepts for this field. */
  maxBytes: number
}

/** A PEM textarea with a "choose file" button next to it. */
function PemField({ label, fileLabel, value, onChange, error, help, secret = false, maxBytes }: PemFieldProps) {
  const { t } = useTranslation('domains')
  const [fileError, setFileError] = useState<string | null>(null)

  function pick(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    // allow picking the same file again
    event.target.value = ''
    if (!file) return
    setFileError(null)
    if (file.size > maxBytes) {
      setFileError(t('CertificateDialog.fileTooLarge', { size: Math.round(maxBytes / 1024) }))
      return
    }
    file.text().then(onChange, () => setFileError(t('CertificateDialog.fileUnreadable')))
  }

  return (
    <div className="cert-dialog__pem">
      <FormField label={label} help={help} error={fileError ?? error}>
        {(control) => (
          <textarea
            {...control}
            className="ui-yaml-input"
            rows={6}
            value={value}
            onChange={(e) => onChange(e.target.value)}
            spellCheck={false}
            autoComplete="off"
            autoCapitalize="off"
            autoCorrect="off"
            data-1p-ignore={secret ? true : undefined}
            data-lpignore={secret ? true : undefined}
          />
        )}
      </FormField>
      <label className="ui-btn ui-btn--secondary ui-btn--sm cert-dialog__pick">
        <FileUp size={16} strokeWidth={1.75} aria-hidden="true" />
        {t('CertificateDialog.chooseFile')}
        <input type="file" accept=".pem,.crt,.cer,.key,text/plain" aria-label={fileLabel} className="cert-dialog__file" onChange={pick} />
      </label>
    </div>
  )
}

/** Upload a new certificate, or renew an existing one (chain and key only). The key field is write-only. */
export function CertificateDialog({ hostId, renewing, onClose }: Props) {
  const { t } = useTranslation('domains')
  const upload = useUploadCertificate(hostId)
  const renew = useRenewCertificate(hostId)
  const [name, setName] = useState('')
  const [certPem, setCertPem] = useState('')
  const [keyPem, setKeyPem] = useState('')
  const [error, setError] = useState<CertificateError | null>(null)
  const [nameError, setNameError] = useState<string | null>(null)
  const formId = useId()
  const busy = upload.isPending || renew.isPending
  const isRenew = renewing !== null

  function submit() {
    setError(null)
    setNameError(null)
    if (!isRenew && !name.trim()) {
      setNameError(t('CertificateDialog.nameRequired'))
      return
    }
    if (!certPem.trim()) {
      setError({ field: 'cert', message: t('CertificateDialog.certRequired') })
      return
    }
    if (!keyPem.trim()) {
      setError({ field: 'key', message: t('CertificateDialog.keyRequired') })
      return
    }
    const key = keyPem
    // The key never stays in the form after it was sent, whatever the outcome.
    setKeyPem('')
    // reset() drops the request (with the key) from the mutation once it settled.
    const callbacks = {
      onSuccess: () => {
        upload.reset()
        renew.reset()
        onClose()
      },
      onError: (e: unknown) => {
        upload.reset()
        renew.reset()
        const described = describeCertificateError(e)
        if (described.field === 'name') setNameError(described.message)
        else setError(described)
      },
    }
    if (renewing) renew.mutate({ id: renewing.id, certPem, keyPem: key }, callbacks)
    else upload.mutate({ name: name.trim(), certPem, keyPem: key }, callbacks)
  }

  const certError = error?.field === 'cert' ? error.message : null
  const keyError = error?.field === 'key' ? error.message : null
  const formError = error?.field === 'form' ? error.message : null

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && !busy && onClose()}
      size="lg"
      title={isRenew ? t('CertificateDialog.renewTitle', { name: renewing.name }) : t('CertificateDialog.uploadTitle')}
      description={isRenew ? t('CertificateDialog.renewIntro') : t('CertificateDialog.uploadIntro')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            {t('CertificateDialog.cancel')}
          </Button>
          <Button variant="primary" type="submit" form={formId} loading={busy}>
            {isRenew ? t('CertificateDialog.renew') : t('CertificateDialog.upload')}
          </Button>
        </>
      }
    >
      <form
        id={formId}
        className="ui-form cert-dialog"
        autoComplete="off"
        noValidate
        onSubmit={(e) => {
          e.preventDefault()
          submit()
        }}
      >
        {!isRenew && (
          <TextField label={t('CertificateDialog.name')} help={t('CertificateDialog.nameHelp')} error={nameError} value={name} onChange={(e) => setName(e.target.value)} autoComplete="off" maxLength={100} />
        )}
        <PemField label={t('CertificateDialog.chain')} fileLabel={t('CertificateDialog.chooseCertFile')} help={t('CertificateDialog.chainHelp')} value={certPem} onChange={setCertPem} error={certError} maxBytes={MAX_CHAIN_BYTES} />
        <PemField label={t('CertificateDialog.key')} fileLabel={t('CertificateDialog.chooseKeyFile')} help={t('CertificateDialog.keyHelp')} value={keyPem} onChange={setKeyPem} error={keyError} secret maxBytes={MAX_KEY_BYTES} />
        {formError && (
          <p role="alert" className="ui-error">
            {formError}
          </p>
        )}
      </form>
    </Dialog>
  )
}
