import { Plus, X } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { DeployOutput } from '../deployments/deploy-output/DeployOutput.tsx'
import { useGitCredentials } from '../settings/api.ts'
import { Button, Dialog, IconButton, Select, Switch, TextField } from '../../shared/ui/index.ts'
import { describeBuildError, useBuildImage, useRefreshAfterPull, type BuildInput } from './api.ts'
import {
  MAX_BUILD_ARGS,
  MAX_BUILD_CONTEXT_BYTES,
  MAX_BUILD_CONTEXT_LABEL,
  hasContextExtension,
  isValidBuildArgKey,
  isValidReference,
} from './buildRules.ts'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'
import './ImagesPage.css'

interface Props {
  hostId: string
  onClose: () => void
}

type Source = 'upload' | 'git'

/** Only mounted for the Git source, so the (ADMIN) credential list is requested on demand. */
function CredentialSelect({ value, onChange }: { value: string; onChange: (id: string) => void }) {
  const { t } = useTranslation('images')
  const credentials = useGitCredentials()
  return (
    <Select
      label={t('BuildImageDialog.credential')}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      disabled={credentials.isPending}
      help={
        credentials.isError
          ? t('BuildImageDialog.couldNotLoadTheCredentials')
          : credentials.data?.length === 0
            ? t('BuildImageDialog.noCredentialsYetAddOne')
            : undefined
      }
    >
      <option value="">{t('BuildImageDialog.nonePublicRepository')}</option>
      {credentials.data?.map((c) => (
        <option key={c.id} value={c.id}>
          {c.name}
        </option>
      ))}
    </Select>
  )
}

function fileError(file: File | null): string | null {
  if (!file) return null
  if (!hasContextExtension(file.name)) return i18n.t('images:BuildImageDialog.chooseATarTarGz')
  if (file.size > MAX_BUILD_CONTEXT_BYTES) return i18n.t('images:BuildImageDialog.theFileIsLargerThan', { MAX_BUILD_CONTEXT_LABEL })
  return null
}

/** Build dialog (ADMIN): upload a tar context or build from Git, then follow the live output. */
export function BuildImageDialog({ hostId, onClose }: Props) {
  const { t } = useTranslation('images')
  const [source, setSource] = useState<Source>('upload')
  const [file, setFile] = useState<File | null>(null)
  const [gitUrl, setGitUrl] = useState('')
  const [gitRef, setGitRef] = useState('')
  const [gitCredentialId, setGitCredentialId] = useState('')
  const [tag, setTag] = useState('')
  const [dockerfile, setDockerfile] = useState('Dockerfile')
  const [buildArgs, setBuildArgs] = useState<string[]>([])
  const [noCache, setNoCache] = useState(false)
  const [runId, setRunId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const build = useBuildImage(hostId)
  const refresh = useRefreshAfterPull(hostId)

  const tagError =
    tag.trim() && !isValidReference(tag.trim())
      ? t('BuildImageDialog.thisIsNotAValid')
      : null
  const uploadError = fileError(file)
  const argErrors = buildArgs.map((a) => {
    if (!a) return null
    const eq = a.indexOf('=')
    return eq < 1 || !isValidBuildArgKey(a.slice(0, eq)) ? t('BuildImageDialog.useKeyValueTheKey') : null
  })
  const sourceReady = source === 'upload' ? !!file && !uploadError : !!gitUrl.trim() && !!gitRef.trim()
  const canSubmit = sourceReady && !!tag.trim() && !tagError && !!dockerfile.trim() && buildArgs.every((a, n) => a && !argErrors[n])

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!canSubmit) return
    setError(null)
    const input: BuildInput = {
      source,
      file,
      gitUrl,
      gitRef,
      gitCredentialId,
      tag,
      dockerfile,
      buildArgs,
      noCache,
    }
    build.mutate(input, {
      onSuccess: (r) => setRunId(r.runId),
      onError: (err) => setError(describeBuildError(err)),
    })
  }

  const setArg = (n: number, value: string) => setBuildArgs((args) => args.map((a, i) => (i === n ? value : a)))

  return (
    <Dialog size="lg" open onOpenChange={(open) => !open && onClose()} title={t('BuildImageDialog.buildImage')}>
      {runId ? (
        <>
          <DeployOutput title={t('BuildImageDialog.buildingTrim', { tag: tag.trim() })} runId={runId} onFinished={() => void refresh()} />
          <div className="ui-dialog-actions">
            <Button variant="primary" onClick={onClose}>
              {t('BuildImageDialog.close')}
            </Button>
          </div>
        </>
      ) : (
        <form className="ui-form" onSubmit={submit}>
          <fieldset className="build__source">
            <legend>{t('BuildImageDialog.source')}</legend>
            <label className="build__radio">
              <input type="radio" name="build-source" checked={source === 'upload'} onChange={() => setSource('upload')} />
              {t('BuildImageDialog.upload')}
            </label>
            <label className="build__radio">
              <input type="radio" name="build-source" checked={source === 'git'} onChange={() => setSource('git')} />
              {t('BuildImageDialog.gitRepository')}
            </label>
          </fieldset>
          {source === 'upload' ? (
            <TextField
              type="file"
              label={t('BuildImageDialog.buildContext')}
              accept=".tar,.tar.gz,.tgz"
              help={t('BuildImageDialog.aTarOrTarGz', { MAX_BUILD_CONTEXT_LABEL })}
              error={uploadError}
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
          ) : (
            <>
              <TextField
                label={t('BuildImageDialog.repositoryUrl')}
                value={gitUrl}
                onChange={(e) => setGitUrl(e.target.value)}
                placeholder="https://github.com/owner/repo.git"
                inputMode="url"
                autoComplete="off"
                spellCheck={false}
              />
              <TextField
                label={t('BuildImageDialog.branchTagOrCommit')}
                value={gitRef}
                onChange={(e) => setGitRef(e.target.value)}
                autoComplete="off"
                spellCheck={false}
              />
              <CredentialSelect value={gitCredentialId} onChange={setGitCredentialId} />
            </>
          )}
          <TextField
            label={t('BuildImageDialog.imageTag')}
            value={tag}
            onChange={(e) => setTag(e.target.value)}
            placeholder="myapp:1.0"
            error={tagError}
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
          />
          <TextField
            label={t('BuildImageDialog.dockerfilePath')}
            value={dockerfile}
            onChange={(e) => setDockerfile(e.target.value)}
            help={t('BuildImageDialog.relativeToTheContextRoot')}
            autoComplete="off"
            spellCheck={false}
          />
          <div className="build__args">
            <span className="build__args-title">{t('BuildImageDialog.buildArguments')}</span>
            {buildArgs.map((arg, n) => (
              <div key={n} className="build__arg-row">
                <TextField
                  label={t('BuildImageDialog.buildArgumentValue', { value: n + 1 })}
                  value={arg}
                  onChange={(e) => setArg(n, e.target.value)}
                  placeholder={t('BuildImageDialog.keyValue')}
                  error={argErrors[n]}
                  autoComplete="off"
                  spellCheck={false}
                />
                <IconButton
                  icon={X}
                  aria-label={t('BuildImageDialog.removeBuildArgumentValue', { value: n + 1 })}
                  onClick={() => setBuildArgs((args) => args.filter((_, i) => i !== n))}
                />
              </div>
            ))}
            {buildArgs.length > 0 && (
              <p className="ui-hint" role="note">
                {t('BuildImageDialog.buildArgumentsAreVisibleIn')}
              </p>
            )}
            <Button
              variant="ghost"
              size="sm"
              disabled={buildArgs.length >= MAX_BUILD_ARGS}
              onClick={() => setBuildArgs((args) => [...args, ''])}
            >
              <Plus size={16} strokeWidth={1.75} aria-hidden="true" /> {t('BuildImageDialog.addBuildArgument')}
            </Button>
          </div>
          <Switch label={t('BuildImageDialog.buildWithoutCache')} checked={noCache} onChange={(e) => setNoCache(e.target.checked)} />
          {error && (
            <p role="alert" className="ui-error">
              {error}
            </p>
          )}
          <div className="ui-dialog-actions">
            <Button variant="ghost" onClick={onClose}>
              {t('BuildImageDialog.cancel')}
            </Button>
            <Button variant="primary" type="submit" loading={build.isPending} disabled={!canSubmit}>
              {t('BuildImageDialog.build')}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
