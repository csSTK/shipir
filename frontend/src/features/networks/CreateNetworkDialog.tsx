import { useId, useState, type FormEvent } from 'react'
import { DeploymentDialog } from '../deployments/DeploymentDialog.tsx'
import { describeCreateError } from '../hosts/createResource.ts'
import { validateVolumeName as validateName } from '../volumes/api.ts'
import { useCreateNetwork, validateSubnet, type NetworkDriver } from './api.ts'

/** Creates a network. Drivers other than bridge are offered to ADMIN only (the server enforces it too). */
export function CreateNetworkDialog({ hostId, isAdmin, onClose }: { hostId: string; isAdmin: boolean; onClose: () => void }) {
  const titleId = useId()
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
    <DeploymentDialog title="Create network" titleId={titleId} onClose={onClose}>
      <form className="sk-form" onSubmit={submit} noValidate>
        <label className="sk-field">
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
          <span role="alert" className="sk-error">
            {nameError}
          </span>
        )}

        {isAdmin ? (
          <label className="sk-field">
            Driver
            <select value={driver} onChange={(e) => setDriver(e.target.value as NetworkDriver)}>
              <option value="bridge">bridge</option>
              <option value="overlay">overlay</option>
              <option value="macvlan">macvlan</option>
              <option value="ipvlan">ipvlan</option>
            </select>
          </label>
        ) : (
          <p className="sk-hint">Driver: bridge</p>
        )}
        <label className="sk-field">
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
          <span role="alert" className="sk-error">
            {subnetError}
          </span>
        )}

        <label>
          <input type="checkbox" checked={internal} onChange={(e) => setInternal(e.target.checked)} /> Internal (no outside access)
        </label>
        {error && (
          <p role="alert" className="sk-error">
            {error}
          </p>
        )}
        <div className="sk-dialog__actions">
          <button type="button" className="sk-btn sk-btn--plain" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="sk-btn sk-btn--primary" disabled={create.isPending}>
            {create.isPending ? 'Creating…' : 'Create'}
          </button>
        </div>
      </form>
    </DeploymentDialog>
  )
}
