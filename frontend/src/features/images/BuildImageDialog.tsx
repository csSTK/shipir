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
import './ImagesPage.css'

interface Props {
  hostId: string
  onClose: () => void
}

type Source = 'upload' | 'git'

/** Only mounted for the Git source, so the (ADMIN) credential list is requested on demand. */
function CredentialSelect({ value, onChange }: { value: string; onChange: (id: string) => void }) {
  const credentials = useGitCredentials()
  return (
    <Select
      label="Credential"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      disabled={credentials.isPending}
      help={
        credentials.isError
          ? 'Could not load the credentials. Public repositories still work.'
          : credentials.data?.length === 0
            ? 'No credentials yet. Add one under Settings, Git credentials.'
            : undefined
      }
    >
      <option value="">None (public repository)</option>
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
  if (!hasContextExtension(file.name)) return 'Choose a .tar, .tar.gz or .tgz file.'
  if (file.size > MAX_BUILD_CONTEXT_BYTES) return `The file is larger than ${MAX_BUILD_CONTEXT_LABEL}.`
  return null
}

/** Build dialog (ADMIN): upload a tar context or build from Git, then follow the live output. */
export function BuildImageDialog({ hostId, onClose }: Props) {
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
      ? 'This is not a valid image reference. Example: myapp:1.0 or ghcr.io/org/app:latest.'
      : null
  const uploadError = fileError(file)
  const argErrors = buildArgs.map((a) => {
    if (!a) return null
    const eq = a.indexOf('=')
    return eq < 1 || !isValidBuildArgKey(a.slice(0, eq)) ? 'Use KEY=VALUE; the key may contain letters, digits and underscores.' : null
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
    <Dialog open onOpenChange={(open) => !open && onClose()} title="Build image">
      {runId ? (
        <>
          <DeployOutput title={`Building ${tag.trim()}`} runId={runId} onFinished={() => void refresh()} />
          <div className="ui-dialog-actions">
            <Button variant="primary" onClick={onClose}>
              Close
            </Button>
          </div>
        </>
      ) : (
        <form className="ui-form" onSubmit={submit}>
          <fieldset className="build__source">
            <legend>Source</legend>
            <label className="build__radio">
              <input type="radio" name="build-source" checked={source === 'upload'} onChange={() => setSource('upload')} />
              Upload
            </label>
            <label className="build__radio">
              <input type="radio" name="build-source" checked={source === 'git'} onChange={() => setSource('git')} />
              Git repository
            </label>
          </fieldset>
          {source === 'upload' ? (
            <TextField
              type="file"
              label="Build context"
              accept=".tar,.tar.gz,.tgz"
              help={`A .tar or .tar.gz of the build directory, up to ${MAX_BUILD_CONTEXT_LABEL}.`}
              error={uploadError}
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
          ) : (
            <>
              <TextField
                label="Repository URL"
                value={gitUrl}
                onChange={(e) => setGitUrl(e.target.value)}
                placeholder="https://github.com/owner/repo.git"
                inputMode="url"
                autoComplete="off"
                spellCheck={false}
              />
              <TextField
                label="Branch, tag or commit"
                value={gitRef}
                onChange={(e) => setGitRef(e.target.value)}
                autoComplete="off"
                spellCheck={false}
              />
              <CredentialSelect value={gitCredentialId} onChange={setGitCredentialId} />
            </>
          )}
          <TextField
            label="Image tag"
            value={tag}
            onChange={(e) => setTag(e.target.value)}
            placeholder="myapp:1.0"
            error={tagError}
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
          />
          <TextField
            label="Dockerfile path"
            value={dockerfile}
            onChange={(e) => setDockerfile(e.target.value)}
            help="Relative to the context root."
            autoComplete="off"
            spellCheck={false}
          />
          <div className="build__args">
            <span className="build__args-title">Build arguments</span>
            {buildArgs.map((arg, n) => (
              <div key={n} className="build__arg-row">
                <TextField
                  label={`Build argument ${n + 1}`}
                  value={arg}
                  onChange={(e) => setArg(n, e.target.value)}
                  placeholder="KEY=VALUE"
                  error={argErrors[n]}
                  autoComplete="off"
                  spellCheck={false}
                />
                <IconButton
                  icon={X}
                  aria-label={`Remove build argument ${n + 1}`}
                  onClick={() => setBuildArgs((args) => args.filter((_, i) => i !== n))}
                />
              </div>
            ))}
            {buildArgs.length > 0 && (
              <p className="ui-hint" role="note">
                Build arguments are visible in the image history. Do not pass secrets such as passwords or tokens.
              </p>
            )}
            <Button
              variant="ghost"
              size="sm"
              disabled={buildArgs.length >= MAX_BUILD_ARGS}
              onClick={() => setBuildArgs((args) => [...args, ''])}
            >
              <Plus size={16} strokeWidth={1.75} aria-hidden="true" /> Add build argument
            </Button>
          </div>
          <Switch label="Build without cache" checked={noCache} onChange={(e) => setNoCache(e.target.checked)} />
          {error && (
            <p role="alert" className="ui-error">
              {error}
            </p>
          )}
          <div className="ui-dialog-actions">
            <Button variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button variant="primary" type="submit" loading={build.isPending} disabled={!canSubmit}>
              Build
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
