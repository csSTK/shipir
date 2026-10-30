import { lazy, Suspense, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../../shared/api/client.ts'
import { Button, Card, ConfirmDialog, Tabs } from '../../../shared/ui/index.ts'
import { formatDateTime } from '../../../shared/i18n/format.ts'
import { useCurrentUser } from '../../auth/index.ts'
import { YamlDiffEditor } from '../../deployments/editor/index.ts'
import { useAdvancedMode } from '../advancedMode.ts'
import { describeConfigError } from './configErrors.ts'
import { useCustomConfigVersions, useProxyConfig, useRestoreCustomConfig, useSaveCustomConfig, type CustomConfigVersion, type ProxyConfig } from './api.ts'
import './ProxyConfigEditor.css'

// Monaco is large: load it only when the editor is shown.
const MonacoEditor = lazy(() => import('../../deployments/editor/MonacoYamlEditor.tsx'))

/** Plain text on purpose: the Compose schema of the YAML mode would flag every valid proxy file. */
const LANGUAGE = 'plaintext'

function Pane({ value, onChange, readOnly, label, path }: { value: string; onChange?: (v: string) => void; readOnly?: boolean; label: string; path: string }) {
  const { t } = useTranslation('domains')
  return (
    <div className="ed-yaml__frame pce__editor">
      <Suspense fallback={<div className="ed-yaml__loading" role="status">{t('ProxyConfigEditor.loadingEditor')}</div>}>
        <MonacoEditor value={value} onChange={onChange ?? (() => {})} readOnly={readOnly} label={label} height="360px" language={LANGUAGE} path={path} />
      </Suspense>
    </div>
  )
}

function Editor({ hostId, config }: { hostId: string; config: ProxyConfig }) {
  const { t } = useTranslation('domains')
  const versions = useCustomConfigVersions(hostId, true)
  const save = useSaveCustomConfig(hostId)
  const restore = useRestoreCustomConfig(hostId)
  const [draft, setDraft] = useState<string | null>(null)
  const [reviewing, setReviewing] = useState(false)
  const [restoring, setRestoring] = useState<CustomConfigVersion | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  const value = draft ?? config.custom
  const dirty = draft !== null && draft !== config.custom
  const typeKey = config.type === 'TRAEFIK' ? 'traefik' : 'caddy'

  function confirmSave() {
    setProblem(null)
    setSaved(false)
    save.mutate(value, {
      onSuccess: () => {
        setDraft(null)
        setSaved(true)
        setReviewing(false)
      },
      onError: (error) => {
        // Close the dialog: the message belongs next to the editor, where the draft can be fixed.
        setProblem(describeConfigError(error))
        setReviewing(false)
      },
      // The request holds the file content; do not keep it around.
      onSettled: () => save.reset(),
    })
  }

  function confirmRestore() {
    if (!restoring) return
    setProblem(null)
    setSaved(false)
    restore.mutate(restoring.id, {
      onSuccess: () => {
        setDraft(null)
        setSaved(true)
        setRestoring(null)
      },
      onError: (error) => {
        setProblem(describeConfigError(error))
        setRestoring(null)
        if (error instanceof ApiError && error.code === 'PROXY_CONFIG_VERSION_NOT_FOUND') void versions.refetch()
      },
      onSettled: () => restore.reset(),
    })
  }

  const generated = (
    <div className="pce__stack">
      <p className="pce__note">{t('ProxyConfigEditor.generatedNote', { file: config.customFileName })}</p>
      {config.managed.map((file) => (
        <div key={file.name} className="pce__stack">
          <h4 className="pce__file">{file.name}</h4>
          <Pane value={file.content} readOnly label={t('ProxyConfigEditor.generatedLabel', { name: file.name })} path={`inmemory://shipir/proxy/${file.name}`} />
        </div>
      ))}
    </div>
  )

  const custom = (
    <div className="pce__stack">
      <p className="pce__note">{t(`ProxyConfigEditor.customNote_${typeKey}`, { file: config.customFileName })}</p>
      <Pane value={value} onChange={(v) => { setDraft(v); setSaved(false) }} label={config.customFileName} path={`inmemory://shipir/proxy/custom/${config.customFileName}`} />
      {problem && (
        <p role="alert" className="pce__error">
          {problem}
        </p>
      )}
      {saved && !problem && (
        <p role="status" className="pce__saved">
          {t('ProxyConfigEditor.saved')}
        </p>
      )}
      <div className="pce__actions">
        {dirty && <span className="pce__dirty">{t('ProxyConfigEditor.unsaved')}</span>}
        <Button variant="primary" disabled={!dirty} onClick={() => { setProblem(null); setReviewing(true) }}>
          {t('ProxyConfigEditor.review')}
        </Button>
      </div>

      <h4 className="pce__file">{t('ProxyConfigEditor.versions')}</h4>
      {versions.isPending ? (
        <p className="pce__note">{t('ProxyConfigEditor.loadingVersions')}</p>
      ) : versions.isError ? (
        <p role="alert" className="pce__error">
          {t('ProxyConfigEditor.versionsFailed')}
        </p>
      ) : versions.data.length === 0 ? (
        <p className="pce__note">{t('ProxyConfigEditor.noVersions')}</p>
      ) : (
        <ul className="pce__versions" aria-label={t('ProxyConfigEditor.versionsLabel')}>
          {versions.data.map((v) => (
            <li key={v.id} className="pce__version">
              <span>
                {formatDateTime(v.createdAt)} <span className="pce__muted">· {v.createdBy}</span>
              </span>
              <Button variant="secondary" size="sm" aria-label={t('ProxyConfigEditor.restoreVersion', { date: formatDateTime(v.createdAt) })} onClick={() => setRestoring(v)}>
                {t('ProxyConfigEditor.restore')}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )

  return (
    <>
      <Tabs
        label={t('ProxyConfigEditor.title')}
        items={[
          { value: 'custom', label: t('ProxyConfigEditor.tabCustom'), content: custom },
          { value: 'generated', label: t('ProxyConfigEditor.tabGenerated'), content: generated },
        ]}
      />
      <ConfirmDialog
        open={reviewing}
        onOpenChange={(open) => !open && setReviewing(false)}
        title={t('ProxyConfigEditor.reviewTitle')}
        description={t('ProxyConfigEditor.reviewText')}
        confirmLabel={t('ProxyConfigEditor.save')}
        loading={save.isPending}
        onConfirm={confirmSave}
        size="xl"
        details={<YamlDiffEditor original={config.custom} modified={value} label={t('ProxyConfigEditor.diffLabel')} sideBySide />}
      />
      <ConfirmDialog
        open={restoring !== null}
        onOpenChange={(open) => !open && setRestoring(null)}
        title={t('ProxyConfigEditor.restoreTitle')}
        description={restoring ? t('ProxyConfigEditor.restoreText', { date: formatDateTime(restoring.createdAt) }) : undefined}
        confirmLabel={t('ProxyConfigEditor.restore')}
        loading={restore.isPending}
        onConfirm={confirmRestore}
      />
    </>
  )
}

function Loaded({ hostId }: { hostId: string }) {
  const { t } = useTranslation('domains')
  const config = useProxyConfig(hostId, true)
  return (
    <Card className="pce">
      <h2 className="pce__title">{t('ProxyConfigEditor.title')}</h2>
      <p className="pce__intro">{t('ProxyConfigEditor.intro')}</p>
      {config.isPending ? (
        <p role="status" className="pce__note">
          {t('ProxyConfigEditor.loading')}
        </p>
      ) : config.isError ? (
        <div className="pce__stack">
          <p role="alert" className="pce__error">
            {describeConfigError(config.error)}
          </p>
          <div>
            <Button onClick={() => void config.refetch()}>{t('DomainsPage.retry')}</Button>
          </div>
        </div>
      ) : (
        <Editor hostId={hostId} config={config.data} />
      )}
    </Card>
  )
}

/**
 * Raw proxy configuration for administrators in advanced mode: the generated files (read-only) and the custom file
 * that is merged in. Renders nothing for other roles or in simple mode, and then loads nothing. The content is only
 * held in component state and the short-lived query cache, never in browser storage or logs.
 */
export function ProxyConfigEditor({ hostId }: { hostId: string }) {
  const { data: user } = useCurrentUser()
  const [advanced] = useAdvancedMode()
  if (!advanced || user?.role !== 'ADMIN') return null
  return <Loaded hostId={hostId} />
}
