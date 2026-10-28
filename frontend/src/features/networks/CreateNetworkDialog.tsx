import { useState, type FormEvent } from 'react'
import { describeCreateError } from '../hosts/createResource.ts'
import { validateVolumeName as validateName } from '../volumes/api.ts'
import { useCreateNetwork, validateSubnet, type NetworkDriver } from './api.ts'
import { Button, Dialog, Select, Switch, TextField } from '../../shared/ui/index.ts'
import { useTranslation } from 'react-i18next'

/** Creates a network. Drivers other than bridge are offered to ADMIN only (the server enforces it too). */
export function CreateNetworkDialog({ hostId, isAdmin, onClose }: { hostId: string; isAdmin: boolean; onClose: () => void }) {
  const { t } = useTranslation('networks')
  const [name, setName] = useState('')
  const [driver, setDriver] = useState<NetworkDriver>('bridge')
  const [internal, setInternal] = useState(false)
  const [subnet, setSubnet] = useState('')
  const [touched, setTouched] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const create = useCreateNetwork(hostId)
  const nameError = validateName(name)
  const subnetError = validateSubnet(subnet)

  function submit(e: FormEvent) {
    e.preventDefault()
    setTouched(true)
    if (nameError || subnetError) return
    setError(null)
    create.mutate(
      { name, driver, internal, ...(subnet.trim() ? { subnet: subnet.trim() } : {}) },
      { onSuccess: onClose, onError: (err) => setError(describeCreateError(err, 'network', 'INVALID_NETWORK_SPEC')) },
    )
  }

  return (
    <Dialog size="lg" open onOpenChange={(open) => !open && onClose()} title={t('CreateNetworkDialog.createNetwork')}>
      <form className="ui-form" onSubmit={submit} noValidate>
        <TextField
          label={t('CreateNetworkDialog.name')}
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={touched ? nameError : null}
          autoComplete="off"
          autoCapitalize="off"
          spellCheck={false}
          autoFocus
        />

        {isAdmin ? (
          <Select label={t('CreateNetworkDialog.driver')} value={driver} onChange={(e) => setDriver(e.target.value as NetworkDriver)}>
            <option value="bridge">{t('CreateNetworkDialog.bridge')}</option>
            <option value="overlay">{t('CreateNetworkDialog.overlay')}</option>
            <option value="macvlan">{t('CreateNetworkDialog.macvlan')}</option>
            <option value="ipvlan">{t('CreateNetworkDialog.ipvlan')}</option>
          </Select>
        ) : (
          <p className="ui-hint">{t('CreateNetworkDialog.driverBridge')}</p>
        )}
        <TextField
          label={t('CreateNetworkDialog.subnetOptional')}
          value={subnet}
          onChange={(e) => setSubnet(e.target.value)}
          placeholder="172.30.0.0/16"
          error={touched ? subnetError : null}
          autoComplete="off"
          spellCheck={false}
        />

        <Switch label={t('CreateNetworkDialog.internalNoOutsideAccess')} checked={internal} onChange={(e) => setInternal(e.target.checked)} />
        {error && (
          <p role="alert" className="ui-error">
            {error}
          </p>
        )}
        <div className="ui-dialog-actions">
          <Button variant="ghost" onClick={onClose}>
            {t('CreateNetworkDialog.cancel')}
          </Button>
          <Button variant="primary" type="submit" disabled={create.isPending}>
            {create.isPending ? t('CreateNetworkDialog.creating') : t('CreateNetworkDialog.create')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
