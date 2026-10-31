import { useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../../shared/api/client.ts'
import { useCurrentUser } from '../auth/index.ts'
import { useCreateDeployment, type EnvEntry } from './api.ts'
import { EnvEditor, toPayload, validateEnv, YamlEditor } from './editor/index.ts'
import { describeDeploymentError } from './deploymentErrors.ts'
import { GitSourceFields, type GitFormValue } from './GitSourceFields.tsx'
import { ImportFileFields, type ImportedCompose, type ImportedEnv } from './ImportFileFields.tsx'
import { fromYaml } from './wizard/fromYaml.ts'
import { ComposeWizard, applyFreePorts, preferredHostPorts, referencedEnvKeys, specToState, type WizardState } from './wizard/index.ts'
import { TemplatePicker, type AppTemplate } from './templates/index.ts'
import { InstallTemplateDialog, type InstallSeed } from './templates/InstallTemplateDialog.tsx'
import { fetchFreePorts } from './templates/api.ts'
import './wizard/wizard.css'
import './templates/templates.css'
import './ImportFileFields.css'
import { Button, Dialog, TextField } from '../../shared/ui/index.ts'
import { useTranslation } from 'react-i18next'

const EMPTY_GIT_FORM: GitFormValue = { repoUrl: '', ref: 'main', composePath: 'compose.yaml', credentialId: '' }
const NAME_PATTERN = /^[a-z0-9][a-z0-9_-]*$/
const GENERIC_FILE_NAMES = ['compose', 'docker-compose', 'stack']

/** Deployment name suggested by the compose file name ("shop.yml" -> "shop"); generic names suggest nothing. */
function suggestName(fileName: string): string {
  const base = fileName.replace(/\.ya?ml$/i, '').toLowerCase()
  if (GENERIC_FILE_NAMES.includes(base)) return ''
  return base.replace(/[^a-z0-9_-]+/g, '-').replace(/^[^a-z0-9]+|-+$/g, '').slice(0, 63)
}

/**
 * Create form: name, then either the Compose file (Monaco) or the form wizard, plus environment variables.
 * The wizard hands its generated YAML to the editor ("Continue in editor"); it is one-way.
 */
export function AddDeploymentDialog({ hostId, onClose }: { hostId: string; onClose: () => void }) {
  const { t: tr } = useTranslation('deployments')
  const navigate = useNavigate()
  const create = useCreateDeployment(hostId)
  const [name, setName] = useState('')
  const [yaml, setYaml] = useState('')
  const [env, setEnv] = useState<EnvEntry[]>([])
  const { data: user } = useCurrentUser()
  const [source, setSource] = useState<'file' | 'git' | 'import'>('file')
  const [git, setGit] = useState<GitFormValue>(EMPTY_GIT_FORM)
  const [mode, setMode] = useState<'template' | 'yaml' | 'wizard'>('yaml')
  const [template, setTemplate] = useState<AppTemplate | null>(null)
  const [seed, setSeed] = useState<{ key: number; state?: WizardState }>({ key: 0 })
  const [installing, setInstalling] = useState<AppTemplate | null>(null)
  const latestPick = useRef(0)
  const [checkingPorts, setCheckingPorts] = useState(false)
  const [portNotices, setPortNotices] = useState<{ from: number; to: number }[]>([])
  const [problem, setProblem] = useState<string | null>(null)
  /** Latest picked files; a ref so two picks in quick succession both see each other. */
  const picked = useRef<{ yaml: string | null; env: EnvEntry[] | null }>({ yaml: null, env: null })
  const [imported, setImported] = useState(false)
  const [importHint, setImportHint] = useState<string | null>(null)
  const [yamlError, setYamlError] = useState<{ message: string; details?: string[] } | null>(null)

  /** Adds an empty row per key that has none yet; existing values stay. */
  function addEnvKeys(keys: string[]) {
    setEnv((current) => [...current, ...keys.filter((k, i) => keys.indexOf(k) === i && !current.some((r) => r.key === k)).map((key) => ({ key, value: '' }))])
  }

  function continueInEditor(generated: string, envKeys: string[]) {
    setYaml(generated)
    setYamlError(null)
    addEnvKeys(envKeys)
    setMode('yaml')
  }

  /** Fills the wizard from the template and pre-creates the env rows it needs; the wizard then works as usual. */
  async function pickTemplate(t: AppTemplate) {
    const pick = ++latestPick.current
    let state = specToState(t.spec)
    let replaced: { from: number; to: number }[] = []
    const preferred = preferredHostPorts(state)
    if (preferred.length > 0) {
      setCheckingPorts(true)
      try {
        ;({ state, replaced } = applyFreePorts(state, await fetchFreePorts(hostId, preferred)))
      } catch {
        // Lookup failed: keep the template's own ports, the user can still edit them.
      }
    }
    if (pick !== latestPick.current) return // a newer pick took over and owns the flag
    setCheckingPorts(false)
    setPortNotices(replaced)
    setTemplate(t)
    setSeed((current) => ({ key: current.key + 1, state }))
    addEnvKeys([...t.env.map((h) => h.key), ...referencedEnvKeys(state)])
    setName((current) => (current.trim() === '' ? t.id : current))
    setMode('wizard')
  }

  /** "Advanced" in the install dialog: continue in the wizard with the name, ports and variables already chosen. */
  function continueFromInstall({ template: t, name: installName, state, env: values }: InstallSeed) {
    setInstalling(null)
    setTemplate(t)
    setPortNotices([])
    setSeed((current) => ({ key: current.key + 1, state }))
    setEnv(values)
    setName(installName)
    setMode('wizard')
  }

  /** Puts the picked files into the editors: the wizard when it can represent the compose file, else YAML mode with the reason. */
  function applyImport() {
    const { yaml: text, env: entries } = picked.current
    if (text === null) return
    const rows = entries ?? []
    setImported(true)
    setYaml(text)
    setYamlError(null)
    setEnv(rows)
    const result = fromYaml(text, rows.map((e) => e.key))
    if (result.ok) {
      setImportHint(null)
      setTemplate(null)
      setPortNotices([])
      setSeed((current) => ({ key: current.key + 1, state: result.state }))
      setMode('wizard')
    } else {
      setImportHint(result.reason)
      setMode('yaml')
    }
  }

  function importCompose({ fileName, text }: ImportedCompose) {
    const suggestion = suggestName(fileName)
    if (suggestion) setName((current) => (current.trim() === '' ? suggestion : current))
    picked.current.yaml = text
    applyImport()
  }

  function importEnv({ entries }: ImportedEnv) {
    picked.current.env = entries
    if (picked.current.yaml === null) setEnv(entries)
    else applyImport()
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    if (source !== 'git' && mode !== 'yaml') return // Enter inside the wizard must not submit
    const trimmed = name.trim()
    if (!NAME_PATTERN.test(trimmed) || trimmed.length > 63) {
      setProblem(tr('AddDeploymentDialog.useLowercaseLettersDigitsHyphens'))
      return
    }
    if (source === 'git') {
      const repoUrl = git.repoUrl.trim()
      const ref = git.ref.trim()
      const composePath = git.composePath.trim()
      if (!repoUrl) return setProblem(tr('AddDeploymentDialog.enterTheRepositoryUrl'))
      if (!ref) return setProblem(tr('AddDeploymentDialog.enterABranchOrTag'))
      if (!composePath) return setProblem(tr('AddDeploymentDialog.enterThePathOfThe'))
      if (validateEnv(env).size > 0) return setProblem(tr('AddDeploymentDialog.fixTheHighlightedVariablesFirst'))
      setProblem(null)
      create.mutate(
        { source: 'GIT', name: trimmed, repoUrl, ref, composePath, ...(git.credentialId ? { credentialId: git.credentialId } : {}), env: toPayload(env) },
        {
          onSuccess: (deployment) => navigate(`/environments/${hostId}/deployments/${deployment.id}`),
          onError: (err) => setProblem(describeDeploymentError(err, tr('AddDeploymentDialog.couldNotCreateTheDeployment'))),
        },
      )
      return
    }
    if (yaml.trim() === '') {
      setProblem(tr('AddDeploymentDialog.enterTheComposeFile'))
      return
    }
    if (validateEnv(env).size > 0) {
      setProblem(tr('AddDeploymentDialog.fixTheHighlightedVariablesFirst'))
      return
    }
    setProblem(null)
    setYamlError(null)
    const entries = toPayload(env)
    create.mutate(
      { name: trimmed, yaml, env: entries },
      {
        onSuccess: (deployment) => navigate(`/environments/${hostId}/deployments/${deployment.id}`),
        onError: (err) => {
          if (err instanceof ApiError && err.code === 'INVALID_COMPOSE') {
            setYamlError({ message: tr('AddDeploymentDialog.theComposeFileIsNot'), details: err.details })
          } else {
            setProblem(describeDeploymentError(err, tr('AddDeploymentDialog.couldNotCreateTheDeployment')))
          }
        },
      },
    )
  }

  const composeVisible = source === 'file' || (source === 'import' && imported)

  return (
    <Dialog size="lg" open onOpenChange={(open) => !open && onClose()} title={tr('AddDeploymentDialog.addDeployment')}>
      <form onSubmit={submit} className="ui-form">
        <TextField label={tr('AddDeploymentDialog.name')} value={name} onChange={(e) => setName(e.target.value)} autoFocus autoComplete="off" spellCheck={false} />
        <div className="wz-modes" role="radiogroup" aria-label={tr('AddDeploymentDialog.source')}>
          {([['file', tr('AddDeploymentDialog.newFile')], ['import', tr('AddDeploymentDialog.importFile')], ['git', tr('AddDeploymentDialog.gitRepository')]] as const).map(([value, label]) => (
            <label key={value} className="wz-mode">
              <input type="radio" name="deployment-source" checked={source === value} onChange={() => setSource(value)} />
              {label}
            </label>
          ))}
        </div>
        {source === 'git' && (
          <>
            <GitSourceFields value={git} onChange={setGit} isAdmin={user?.role === 'ADMIN'} />
            <EnvEditor value={env} onChange={setEnv} />
          </>
        )}
        <div hidden={source !== 'import'}>
          <ImportFileFields onCompose={importCompose} onEnv={importEnv} />
        </div>
        {source === 'import' && importHint && (
          <p className="if-hint" role="status">
            {tr('AddDeploymentDialog.importFallbackHint', { reason: importHint })}
          </p>
        )}
        <div className="wz-modes" role="radiogroup" aria-label={tr('AddDeploymentDialog.editingMode')} hidden={!composeVisible}>
          {([['template', tr('AddDeploymentDialog.fromTemplate')], ['wizard', tr('AddDeploymentDialog.formWizard')], ['yaml', 'YAML']] as const).map(([value, label]) => (
            <label key={value} className="wz-mode">
              <input type="radio" name="compose-mode" checked={mode === value} onChange={() => setMode(value)} />
              {label}
            </label>
          ))}
        </div>
        {composeVisible && mode === 'template' && <TemplatePicker onSelect={(t) => void pickTemplate(t)} onInstall={setInstalling} selectedId={template?.id} />}
        {composeVisible && mode === 'template' && checkingPorts && (
          <p className="tp__hint" role="status">
            {tr('AddDeploymentDialog.checkingFreePorts')}
          </p>
        )}
        <div hidden={!composeVisible || mode !== 'wizard'}>
          {portNotices.length > 0 && (
            <ul className="tp-notice" role="status">
              {portNotices.map((n) => (
                <li key={n.from}>{tr('AddDeploymentDialog.portTakenUsing', { from: n.from, to: n.to })}</li>
              ))}
            </ul>
          )}
          {template && template.env.length > 0 && (
            <div className="tp-vars">
              <p className="tp-vars__title">{tr('AddDeploymentDialog.variablesForName', { name: template.name })}</p>
              <ul className="tp-vars__list">
                {template.env.map((h) => (
                  <li key={h.key}>
                    <code>{h.key}</code>: {h.description}
                    {h.secret ? tr('AddDeploymentDialog.secret') : ''}
                  </li>
                ))}
              </ul>
              <p className="tp-vars__note">{tr('AddDeploymentDialog.fillInTheirValuesAfter')}</p>
            </div>
          )}
          <ComposeWizard hostId={hostId} active={composeVisible && mode === 'wizard'} key={seed.key} initial={seed.state} onContinue={continueInEditor} />
        </div>
        <div className="ui-form" hidden={!composeVisible || mode !== 'yaml'}>
          <div className="ui-field">
            <span className="ui-field__label">{tr('AddDeploymentDialog.composeFile')}</span>
            <YamlEditor label={tr('AddDeploymentDialog.composeFile2')} value={yaml} onChange={setYaml} height="260px" error={yamlError} />
          </div>
          <EnvEditor value={env} onChange={setEnv} />
        </div>
        {problem && (
          <p role="alert" className="ui-error">
            {problem}
          </p>
        )}
        <div className="ui-dialog-actions">
          <Button variant="ghost" onClick={onClose}>
            {tr('AddDeploymentDialog.cancel')}
          </Button>
          <Button variant="primary" type="submit" disabled={create.isPending} hidden={(source === 'import' && !imported) || (composeVisible && mode !== 'yaml')}>
            {tr('AddDeploymentDialog.createDeployment')}
          </Button>
        </div>
      </form>
      {installing && <InstallTemplateDialog hostId={hostId} template={installing} onClose={() => setInstalling(null)} onAdvanced={continueFromInstall} />}
    </Dialog>
  )
}
