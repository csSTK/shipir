import { useState, type FormEvent } from 'react'
import { describeCreateError } from '../hosts/createResource.ts'
import { validateVolumeName as validateName } from '../volumes/api.ts'
import { useCreateNetwork, validateSubnet, type NetworkDriver } from './api.ts'
import { Button, Dialog } from '../../shared/ui/index.ts'

/** Creates a network. Drivers other than bridge are offered to ADMIN only (the server enforces it too). */
export function CreateNetworkDialog({ hostId, isAdmin, onClose }: { hostId: string; isAdmin: boolean; onClose: () => void }) {
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
    <Dialog open onOpenChange={(open) => !open && onClose()} title="Create network">
      <form className="ui-form" onSubmit={submit} noValidate>
        <label className="ui-form-field">
          Name
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

        {isAdmin ? (
          <label className="ui-form-field">
            Driver
            <select value={driver} onChange={(e) => setDriver(e.target.value as NetworkDriver)}>
              <option value="bridge">bridge</option>
              <option value="overlay">overlay</option>
              <option value="macvlan">macvlan</option>
              <option value="ipvlan">ipvlan</option>
            </select>
          </label>
        ) : (
          <p className="ui-hint">Driver: bridge</p>
        )}
        <label className="ui-form-field">
          Subnet (optional)
          <input
            value={subnet}
            onChange={(e) => setSubnet(e.target.value)}
            placeholder="172.30.0.0/16"
            aria-invalid={touched && subnetError ? true : undefined}
            autoComplete="off"
            spellCheck={false}
          />
        </label>
        {touched && subnetError && (
          <span role="alert" className="ui-error">
            {subnetError}
          </span>
        )}

        <label>
          <input type="checkbox" checked={internal} onChange={(e) => setInternal(e.target.checked)} /> Internal (no outside access)
        </label>
        {error && (
          <p role="alert" className="ui-error">
            {error}
          </p>
        )}
        <div className="ui-dialog-actions">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" disabled={create.isPending}>
            {create.isPending ? 'Creating…' : 'Create'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
