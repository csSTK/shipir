import { useState } from 'react'
import { ApiError } from '../../../shared/api/client.ts'
import { Button, Dialog, Switch, TextField } from '../../../shared/ui/index.ts'
import { useSaveUptimeCheck, type UptimeCheck, type UptimeCheckInput } from './api.ts'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'

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
  if (!form.name.trim()) errors.name = i18n.t('deployments:UptimeCheckDialog.enterAName')
  else if (form.name.length > 100) errors.name = i18n.t('deployments:UptimeCheckDialog.atMost100Characters')
  try {
    const url = new URL(form.url.trim())
    if (url.protocol !== 'http:' && url.protocol !== 'https:') errors.url = i18n.t('deployments:UptimeCheckDialog.theUrlMustUseHttp')
    else if (url.username || url.password) errors.url = i18n.t('deployments:UptimeCheckDialog.theUrlMustNotContain')
  } catch {
    errors.url = i18n.t('deployments:UptimeCheckDialog.enterAValidHttpOr')
  }
  if (form.url.length > 2048) errors.url = i18n.t('deployments:UptimeCheckDialog.atMost2048Characters')
  if (form.expectedStatus.trim() && !inRange(form.expectedStatus, 100, 599)) errors.expectedStatus = i18n.t('deployments:UptimeCheckDialog.useAStatusBetween100')
  if (!inRange(form.intervalSeconds, 30, 86400)) errors.intervalSeconds = i18n.t('deployments:UptimeCheckDialog.useAValueBetween30')
  if (!inRange(form.timeoutSeconds, 1, 30)) errors.timeoutSeconds = i18n.t('deployments:UptimeCheckDialog.useAValueBetween1')
  return errors
}

function describe(error: unknown): { field?: keyof Form; message: string } {
  if (error instanceof ApiError) {
    if (error.code === 'UPTIME_CHECK_LIMIT') return { message: i18n.t('deployments:UptimeCheckDialog.thisDeploymentAlreadyHasThe') }
    if (error.code === 'UPTIME_CHECK_NOT_FOUND') return { message: i18n.t('deployments:UptimeCheckDialog.thisCheckNoLongerExists') }
    if (error.status === 403) return { message: i18n.t('deployments:UptimeCheckDialog.youAreNotAllowedTo') }
    if (error.status === 400 || error.status === 422) return { message: [i18n.t('deployments:UptimeCheckDialog.theCheckIsInvalid'), ...(error.details ?? [])].join(' ') }
  }
  return { message: i18n.t('deployments:UptimeCheckDialog.couldNotSaveTheCheck') }
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
  const { t } = useTranslation('deployments')
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
      title={check ? t('UptimeCheckDialog.editUptimeCheck') : t('UptimeCheckDialog.addUptimeCheck')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t('UptimeCheckDialog.cancel')}
          </Button>
          <Button variant="primary" loading={save.isPending} onClick={onSave}>
            {t('UptimeCheckDialog.save')}
          </Button>
        </>
      }
    >
      <div className="uptime-form">
        <TextField label={t('UptimeCheckDialog.name')} value={form.name} error={errors.name} onChange={(e) => set('name', e.target.value)} />
        <TextField
          label={t('UptimeCheckDialog.url')}
          type="url"
          inputMode="url"
          value={form.url}
          error={errors.url}
          help={t('UptimeCheckDialog.shipirRequestsThisUrlFrom')}
          onChange={(e) => set('url', e.target.value)}
        />
        <TextField
          label={t('UptimeCheckDialog.expectedStatus')}
          inputMode="numeric"
          value={form.expectedStatus}
          error={errors.expectedStatus}
          help={t('UptimeCheckDialog.leaveEmptyToAcceptAny')}
          onChange={(e) => set('expectedStatus', e.target.value)}
        />
        <TextField
          label={t('UptimeCheckDialog.intervalSeconds')}
          inputMode="numeric"
          value={form.intervalSeconds}
          error={errors.intervalSeconds}
          onChange={(e) => set('intervalSeconds', e.target.value)}
        />
        <TextField
          label={t('UptimeCheckDialog.timeoutSeconds')}
          inputMode="numeric"
          value={form.timeoutSeconds}
          error={errors.timeoutSeconds}
          onChange={(e) => set('timeoutSeconds', e.target.value)}
        />
        <Switch label={t('UptimeCheckDialog.enabled')} checked={form.enabled} onChange={(e) => set('enabled', e.target.checked)} />
        {formError && (
          <p role="alert" className="ui-error">
            {formError}
          </p>
        )}
      </div>
    </Dialog>
  )
}
