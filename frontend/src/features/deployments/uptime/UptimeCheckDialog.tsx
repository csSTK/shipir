import { useState } from 'react'
import { ApiError } from '../../../shared/api/client.ts'
import { Button, Dialog, Switch, TextField } from '../../../shared/ui/index.ts'
import { useSaveUptimeCheck, type UptimeCheck, type UptimeCheckInput } from './api.ts'

interface Form {
  name: string
  url: string
  expectedStatus: string
  intervalSeconds: string
  timeoutSeconds: string
  enabled: boolean
}
type Errors = Partial<Record<keyof Form, string>>

function initial(check: UptimeCheck | null): Form {
  return {
    name: check?.name ?? '',
    url: check?.url ?? '',
    expectedStatus: check ? (check.expectedStatus?.toString() ?? '') : '200',
    intervalSeconds: String(check?.intervalSeconds ?? 60),
    timeoutSeconds: String(check?.timeoutSeconds ?? 10),
    enabled: check?.enabled ?? true,
  }
}

function inRange(value: string, min: number, max: number): boolean {
  return /^\d+$/.test(value.trim()) && Number(value) >= min && Number(value) <= max
}

/** Mirrors the server validation (UptimeCheckRequest and the URL rules of the service). */
export function validate(form: Form): Errors {
  const errors: Errors = {}
  if (!form.name.trim()) errors.name = 'Enter a name.'
  else if (form.name.length > 100) errors.name = 'At most 100 characters.'
  try {
    const url = new URL(form.url.trim())
    if (url.protocol !== 'http:' && url.protocol !== 'https:') errors.url = 'The URL must use http or https.'
    else if (url.username || url.password) errors.url = 'The URL must not contain a username or password.'
  } catch {
    errors.url = 'Enter a valid http or https URL.'
  }
  if (form.url.length > 2048) errors.url = 'At most 2048 characters.'
  if (form.expectedStatus.trim() && !inRange(form.expectedStatus, 100, 599)) errors.expectedStatus = 'Use a status between 100 and 599, or leave empty for any 2xx.'
  if (!inRange(form.intervalSeconds, 30, 86400)) errors.intervalSeconds = 'Use a value between 30 and 86400 seconds.'
  if (!inRange(form.timeoutSeconds, 1, 30)) errors.timeoutSeconds = 'Use a value between 1 and 30 seconds.'
  return errors
}

function describe(error: unknown): { field?: keyof Form; message: string } {
  if (error instanceof ApiError) {
    if (error.code === 'EGRESS_DENIED')
      return {
        field: 'url',
        message:
          'Shipir may not call this address. Private and internal addresses are blocked unless the server sets shipir.egress.allow-private-ranges.',
      }
    if (error.code === 'UPTIME_CHECK_LIMIT') return { message: 'This deployment already has the maximum of 10 uptime checks.' }
    if (error.code === 'UPTIME_CHECK_NOT_FOUND') return { message: 'This check no longer exists.' }
    if (error.status === 403) return { message: 'You are not allowed to change uptime checks.' }
    if (error.status === 400 || error.status === 422) return { message: error.details?.join(' ') || error.message }
  }
  return { message: 'Could not save the check. Please try again.' }
}

export function UptimeCheckDialog({
  hostId,
  deploymentId,
  check,
  onClose,
}: {
  hostId: string
  deploymentId: string
  check: UptimeCheck | null
  onClose: () => void
}) {
  const [form, setForm] = useState<Form>(() => initial(check))
  const [errors, setErrors] = useState<Errors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const save = useSaveUptimeCheck(hostId, deploymentId, check?.id ?? null)
  const set = <K extends keyof Form>(key: K, value: Form[K]) => setForm((f) => ({ ...f, [key]: value }))

  function onSave() {
    setFormError(null)
    const found = validate(form)
    setErrors(found)
    if (Object.keys(found).length > 0) return
    const input: UptimeCheckInput = {
      name: form.name.trim(),
      url: form.url.trim(),
      expectedStatus: form.expectedStatus.trim() ? Number(form.expectedStatus) : null,
      intervalSeconds: Number(form.intervalSeconds),
      timeoutSeconds: Number(form.timeoutSeconds),
      enabled: form.enabled,
    }
    save.mutate(input, {
      onSuccess: onClose,
      onError: (err) => {
        const { field, message } = describe(err)
        if (field) setErrors({ [field]: message })
        else setFormError(message)
      },
    })
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={check ? 'Edit uptime check' : 'Add uptime check'}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" loading={save.isPending} onClick={onSave}>
            Save
          </Button>
        </>
      }
    >
      <div className="uptime-form">
        <TextField label="Name" value={form.name} error={errors.name} onChange={(e) => set('name', e.target.value)} />
        <TextField
          label="URL"
          type="url"
          inputMode="url"
          value={form.url}
          error={errors.url}
          help="Shipir requests this URL from the server. Query strings are kept, credentials are not allowed."
          onChange={(e) => set('url', e.target.value)}
        />
        <TextField
          label="Expected status"
          inputMode="numeric"
          value={form.expectedStatus}
          error={errors.expectedStatus}
          help="Leave empty to accept any 2xx."
          onChange={(e) => set('expectedStatus', e.target.value)}
        />
        <TextField
          label="Interval (seconds)"
          inputMode="numeric"
          value={form.intervalSeconds}
          error={errors.intervalSeconds}
          onChange={(e) => set('intervalSeconds', e.target.value)}
        />
        <TextField
          label="Timeout (seconds)"
          inputMode="numeric"
          value={form.timeoutSeconds}
          error={errors.timeoutSeconds}
          onChange={(e) => set('timeoutSeconds', e.target.value)}
        />
        <Switch label="Enabled" checked={form.enabled} onChange={(e) => set('enabled', e.target.checked)} />
        {formError && (
          <p role="alert" className="ui-error">
            {formError}
          </p>
        )}
      </div>
    </Dialog>
  )
}
