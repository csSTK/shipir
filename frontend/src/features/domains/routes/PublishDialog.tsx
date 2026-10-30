import { useId, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Badge, Button, Dialog, SearchField, Select, TextField } from '../../../shared/ui/index.ts'
import { useManagedProxy } from '../api.ts'
import { describeRouteError, type RouteError } from './routeErrors.ts'
import { checkHostname } from './hostname.ts'
import { usePublishRoute, useRouteCandidates, useUpdateRoute, type ProxyRoute, type RouteCandidate } from './api.ts'
import './PublishDialog.css'

export interface PublishDialogProps {
  hostId: string
  /** Start with this container chosen (and locked), e.g. from a container's Publish action or after a catalog install. */
  containerName?: string
  /** Start with this port; used when the container exposes several. */
  port?: number
  /** The route to edit; the dialog then saves instead of publishing. Settings beyond domain and port are kept. */
  route?: ProxyRoute
  onClose: () => void
  /** Called after a successful publish or save. */
  onDone?: () => void
}

const MAX_PORT = 65535

function parsePort(value: string): number | null {
  const n = Number(value)
  return /^\d+$/.test(value.trim()) && n >= 1 && n <= MAX_PORT ? n : null
}

function ContainerPicker({ candidates, value, onChange, error }: { candidates: RouteCandidate[]; value: string; onChange: (name: string) => void; error: string | null }) {
  const { t } = useTranslation('domains')
  const [query, setQuery] = useState('')
  const name = useId()
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase()
    return q ? candidates.filter((c) => `${c.containerName} ${c.image}`.toLowerCase().includes(q)) : candidates
  }, [candidates, query])
  return (
    <fieldset className="publish__group">
      <legend className="publish__legend">{t('PublishDialog.container')}</legend>
      <SearchField aria-label={t('PublishDialog.searchContainers')} placeholder={t('PublishDialog.searchPlaceholder')} value={query} onChange={(e) => setQuery(e.target.value)} />
      <div className="publish__options">
        {shown.length === 0 && <p className="publish__none">{candidates.length === 0 ? t('PublishDialog.noContainers') : t('PublishDialog.noMatch')}</p>}
        {shown.map((c) => (
          <label key={c.containerName} className="publish__option">
            <input type="radio" name={name} className="publish__radio" checked={value === c.containerName} onChange={() => onChange(c.containerName)} />
            <span className="publish__option-name">{c.containerName}</span>
            <span className="publish__option-meta">{c.image}</span>
            <span className="publish__option-badges">
              <Badge tone={c.state === 'running' ? 'ok' : 'muted'}>{c.state}</Badge>
              {c.published && <Badge tone="info" dot={false}>{t('PublishDialog.alreadyPublished')}</Badge>}
            </span>
          </label>
        ))}
      </div>
      {error && (
        <p role="alert" className="ui-field__error">
          {error}
        </p>
      )}
    </fieldset>
  )
}

/**
 * Simple publish dialog: pick a container, type a domain, done. The port is shown read-only when the container
 * exposes exactly one, a select when it exposes several and an input when none is known. Also edits a route.
 */
export function PublishDialog({ hostId, containerName, port, route, onClose, onDone }: PublishDialogProps) {
  const { t } = useTranslation('domains')
  const editing = route !== undefined
  const publish = usePublishRoute(hostId)
  const update = useUpdateRoute(hostId)
  const candidatesQuery = useRouteCandidates(hostId)
  const proxy = useManagedProxy(hostId)
  const formId = useId()
  const lockedContainer = route?.containerName ?? containerName

  const [container, setContainer] = useState(lockedContainer ?? '')
  const [hostname, setHostname] = useState(route?.hostname ?? '')
  const [portValue, setPortValue] = useState(route ? String(route.targetPort) : port ? String(port) : '')
  const [serverPorts, setServerPorts] = useState<number[] | null>(null)
  const [submitted, setSubmitted] = useState(false)
  const [serverError, setServerError] = useState<RouteError | null>(null)
  const [portError, setPortError] = useState<string | null>(null)
  const [containerError, setContainerError] = useState<string | null>(null)
  const busy = publish.isPending || update.isPending

  const candidates = candidatesQuery.data
  const chosen = candidates?.find((c) => c.containerName === container)
  const defaultMode = editing ? route.certMode : proxy.data?.instance?.defaultCertMode
  const allowWildcard = defaultMode === 'ACME_DNS' || defaultMode === 'CUSTOM'

  const options = useMemo(() => {
    const base = serverPorts ?? chosen?.ports ?? []
    const current = parsePort(portValue)
    return editing && current !== null && !base.includes(current) ? [...base, current].sort((a, b) => a - b) : base
  }, [serverPorts, chosen, editing, portValue])
  type PortMode = 'none' | 'auto' | 'select' | 'input'
  const portMode: PortMode = !container
    ? 'none'
    : !editing && serverPorts === null && chosen?.suggestedPort != null
      ? 'auto'
      : options.length >= 2
        ? 'select'
        : 'input'

  const problem = hostname.trim() || submitted ? checkHostname(hostname, allowWildcard) : null
  const hostnameMessage = problem ? t(`PublishDialog.hostnameProblem.${problem}`) : serverError?.field === 'hostname' ? serverError.message : null

  function choose(name: string) {
    setContainer(name)
    setContainerError(null)
    setPortError(null)
    setServerPorts(null)
    setServerError(null)
    // A prefilled port only makes sense for the prefilled container.
    if (name !== containerName) setPortValue('')
  }

  function fail(error: unknown) {
    const described = describeRouteError(error)
    // Without a visible field (locked container, auto-detected port) the message goes to the form-level alert.
    const fieldHidden = (described.field === 'container' && lockedContainer !== undefined) || (described.field === 'port' && portMode === 'auto' && !described.portOptions)
    if (fieldHidden) {
      setServerError({ ...described, field: 'form' })
    } else if (described.field === 'port') {
      if (described.portOptions) setServerPorts(described.portOptions)
      setPortValue('')
      setPortError(described.message)
    } else if (described.field === 'container') {
      setContainerError(described.message)
    } else {
      setServerError(described)
    }
  }

  function submit() {
    setSubmitted(true)
    setServerError(null)
    setPortError(null)
    let valid = true
    if (!container) {
      setContainerError(t('PublishDialog.containerRequired'))
      valid = false
    }
    if (checkHostname(hostname, allowWildcard) !== null) valid = false
    let sentPort: number | undefined
    if (container && portMode !== 'auto') {
      const parsed = parsePort(portValue)
      if (parsed === null) {
        setPortError(portMode === 'select' ? t('PublishDialog.portChoose') : t('PublishDialog.portInvalid'))
        valid = false
      } else {
        sentPort = parsed
      }
    }
    if (!valid) return
    const callbacks = {
      onSuccess: () => {
        onDone?.()
        onClose()
      },
      onError: fail,
    }
    const name = hostname.trim()
    if (route) {
      update.mutate(
        {
          id: route.id,
          hostname: name,
          containerName: route.containerName,
          port: sentPort,
          aliases: route.aliases,
          backendScheme: route.backendScheme,
          pathPrefix: route.pathPrefix,
          entrypoint: route.entrypoint,
          certMode: route.certMode,
          certificateId: route.certificateId,
          redirectHttps: route.redirectHttps,
          responseHeaders: route.responseHeaders,
        },
        callbacks,
      )
    } else {
      publish.mutate({ containerName: container, hostname: name, ...(sentPort !== undefined && { port: sentPort }) }, callbacks)
    }
  }

  const formError = serverError && (serverError.field === 'form' || serverError.field === 'container' || serverError.field === 'port') ? serverError : null

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && !busy && onClose()}
      size="lg"
      title={editing ? t('PublishDialog.editTitle') : t('PublishDialog.title')}
      description={editing ? t('PublishDialog.editIntro') : t('PublishDialog.intro')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            {t('PublishDialog.cancel')}
          </Button>
          <Button variant="primary" type="submit" form={formId} loading={busy}>
            {editing ? t('PublishDialog.save') : t('PublishDialog.publish')}
          </Button>
        </>
      }
    >
      <form
        id={formId}
        className="ui-form publish"
        noValidate
        autoComplete="off"
        onSubmit={(e) => {
          e.preventDefault()
          submit()
        }}
      >
        {lockedContainer ? (
          <div className="publish__locked">
            <span className="publish__legend">{t('PublishDialog.container')}</span>
            <strong className="publish__locked-name">{lockedContainer}</strong>
          </div>
        ) : candidatesQuery.isPending ? (
          <p className="publish__none" role="status">
            {t('PublishDialog.loadingContainers')}
          </p>
        ) : candidatesQuery.isError ? (
          <p role="alert" className="ui-error">
            {t('PublishDialog.candidatesFailed')}
          </p>
        ) : (
          <ContainerPicker candidates={candidates ?? []} value={container} onChange={choose} error={containerError} />
        )}

        <TextField
          label={t('PublishDialog.domain')}
          help={t('PublishDialog.domainHelp')}
          error={hostnameMessage}
          value={hostname}
          onChange={(e) => {
            setHostname(e.target.value)
            if (serverError?.field === 'hostname') setServerError(null)
          }}
          placeholder="app.example.com"
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          inputMode="url"
          maxLength={253}
        />

        {portMode === 'auto' && chosen && (
          <div className="publish__port">
            <span className="publish__legend">{t('PublishDialog.port')}</span>
            <strong className="publish__port-value">{chosen.suggestedPort}</strong>
            <span className="publish__hint">{t('PublishDialog.portDetected')}</span>
          </div>
        )}
        {portMode === 'select' && (
          <Select label={t('PublishDialog.port')} help={t('PublishDialog.portSeveral')} error={portError} value={portValue} onChange={(e) => setPortValue(e.target.value)}>
            <option value="">{t('PublishDialog.portPlaceholder')}</option>
            {options.map((p) => (
              <option key={p} value={String(p)}>
                {p}
              </option>
            ))}
          </Select>
        )}
        {portMode === 'input' && (
          <TextField label={t('PublishDialog.port')} help={t('PublishDialog.portNone')} error={portError} value={portValue} onChange={(e) => setPortValue(e.target.value)} inputMode="numeric" />
        )}

        {formError && (
          <p role="alert" className="ui-error">
            {formError.message}
            {formError.proxyMissing && (
              <>
                {' '}
                <Link to={`/environments/${hostId}/domains`} onClick={onClose}>
                  {t('PublishDialog.openSetup')}
                </Link>
              </>
            )}
          </p>
        )}
      </form>
    </Dialog>
  )
}
