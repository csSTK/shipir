import { useState } from 'react'
import { ConfirmDialog } from '../deployments/ConfirmDialog.tsx'
import { resourceNoun, type ResourceKind } from './createResource.ts'
import { describeDeleteError } from './deleteResource.ts'
import { useTranslation } from 'react-i18next'
import { Trans } from '../../shared/i18n/Trans.tsx'
import './DeleteResourceDialog.css'

interface Props {
  kind: ResourceKind
  name: string
  /** Offers the "Force (stop and remove)" checkbox (running containers). */
  forceOption?: boolean
  /** Shown when the resource belongs to a managed deployment. */
  warning?: string | null
  onDelete: (force: boolean) => Promise<unknown>
  onClose: () => void
}

/** Confirmation for deleting a container, image, volume or network. Errors stay inline. */
export function DeleteResourceDialog({ kind, name, forceOption = false, warning = null, onDelete, onClose }: Props) {
  const { t } = useTranslation('hosts')
  const noun = resourceNoun(kind)
  const [force, setForce] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function confirm() {
    setPending(true)
    setError(null)
    try {
      await onDelete(forceOption && force)
      onClose()
    } catch (e) {
      setError(describeDeleteError(e))
      setPending(false)
    }
  }

  return (
    <ConfirmDialog title={t('DeleteResourceDialog.deleteKind', { kind: noun })} confirmLabel={t('DeleteResourceDialog.delete')} danger pending={pending} error={error} onConfirm={() => void confirm()} onClose={onClose}>
      <Trans t={t} i18nKey="DeleteResourceDialog.deleteKindNameThisCannot" values={{ kind: noun, name }} components={{ strong: <strong /> }} />
      {warning && (
        <span className="delres__warning" role="note">
          {warning}
        </span>
      )}
      {forceOption && (
        <label className="delres__check">
          <input type="checkbox" checked={force} onChange={(e) => setForce(e.target.checked)} /> {t('DeleteResourceDialog.forceStopAndRemove')}
        </label>
      )}
    </ConfirmDialog>
  )
}
