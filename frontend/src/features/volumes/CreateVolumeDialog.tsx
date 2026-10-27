import { useState, type FormEvent } from 'react'
import { describeCreateError } from '../hosts/createResource.ts'
import { useCreateVolume, validateVolumeName } from './api.ts'
import { Button, Dialog } from '../../shared/ui/index.ts'
import { useTranslation } from 'react-i18next'

/** Creates a local volume. Client checks mirror the server; server errors stay inline. */
export function CreateVolumeDialog({ hostId, onClose }: { hostId: string; onClose: () => void }) {
  const { t } = useTranslation('volumes')
  const [name, setName] = useState('')
  const [touched, setTouched] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const create = useCreateVolume(hostId)
  const nameError = validateVolumeName(name)

  function submit(e: FormEvent) {
    e.preventDefault()
    setTouched(true)
    if (nameError) return
    setError(null)
    create.mutate({ name, driver: 'local' }, { onSuccess: onClose, onError: (err) => setError(describeCreateError(err, 'volume', 'INVALID_VOLUME_SPEC')) })
  }

  return (
    <Dialog size="lg" open onOpenChange={(open) => !open && onClose()} title={t('CreateVolumeDialog.createVolume')}>
      <form className="ui-form" onSubmit={submit} noValidate>
        <label className="ui-form-field">
          {t('CreateVolumeDialog.name')}
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            aria-invalid={touched && nameError ? true : undefined}
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            autoFocus
          />
        </label>
        {touched && nameError && (
          <span role="alert" className="ui-error">
            {nameError}
          </span>
        )}

        <p className="ui-hint">{t('CreateVolumeDialog.driverLocal')}</p>
        {error && (
          <p role="alert" className="ui-error">
            {error}
          </p>
        )}
        <div className="ui-dialog-actions">
          <Button variant="ghost" onClick={onClose}>
            {t('CreateVolumeDialog.cancel')}
          </Button>
          <Button variant="primary" type="submit" disabled={create.isPending}>
            {create.isPending ? t('CreateVolumeDialog.creating') : t('CreateVolumeDialog.create')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
