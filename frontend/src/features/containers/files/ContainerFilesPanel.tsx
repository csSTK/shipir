import { useQueryClient } from '@tanstack/react-query'
import { Folder } from 'lucide-react'
import { useRef, useState } from 'react'
import { Button, EmptyState } from '../../../shared/ui/index.ts'
import { formatSize } from '../../hosts/format.ts'
import {
  describeListError,
  describeUploadError,
  downloadUrl,
  filesKey,
  uploadFile,
  useContainerFiles,
  type ContainerFileEntry,
} from './api.ts'
import './ContainerFilesPanel.css'

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

function crumbs(path: string): { label: string; path: string }[] {
  const parts = path.split('/').filter(Boolean)
  return [{ label: '/', path: '/' }, ...parts.map((p, i) => ({ label: p, path: `/${parts.slice(0, i + 1).join('/')}` }))]
}

function sorted(entries: ContainerFileEntry[]): ContainerFileEntry[] {
  const dir = (e: ContainerFileEntry) => (e.type === 'DIRECTORY' ? 0 : 1)
  return [...entries].sort((a, b) => dir(a) - dir(b) || a.name.localeCompare(b.name))
}

/** File browser of a running or stopped container: navigate, download, upload into the current directory. ADMIN only. */
export function ContainerFilesPanel({ hostId, containerId }: { hostId: string; containerId: string }) {
  const [path, setPath] = useState('/')
  const { data, isPending, error, refetch } = useContainerFiles(hostId, containerId, path)
  const queryClient = useQueryClient()
  const input = useRef<HTMLInputElement>(null)
  const abort = useRef<(() => void) | null>(null)
  const [progress, setProgress] = useState<number | null>(null)
  const [uploadName, setUploadName] = useState('')
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)

  async function onPick(file: File | undefined) {
    if (!file) return
    setUploadError(null)
    setDone(null)
    setUploadName(file.name)
    setProgress(0)
    const target = path
    const up = uploadFile(hostId, containerId, target, file, setProgress)
    abort.current = up.abort
    try {
      await up.promise
      setDone(`Uploaded ${file.name} to ${target}`)
      await queryClient.invalidateQueries({ queryKey: filesKey(hostId, containerId, target) })
    } catch (e) {
      setUploadError(describeUploadError(e))
    } finally {
      abort.current = null
      setProgress(null)
      if (input.current) input.current.value = ''
    }
  }

  const entries = Array.isArray(data) ? sorted(data) : []
  const uploading = progress !== null

  return (
    <section className="cfiles" aria-label="Files">
      <div className="cfiles__top">
        <input
          ref={input}
          type="file"
          className="cfiles__input"
          aria-label="File to upload"
          disabled={uploading}
          onChange={(e) => void onPick(e.target.files?.[0])}
        />
        <Button disabled={uploading} onClick={() => input.current?.click()}>
          Upload here
        </Button>
      </div>
      <nav aria-label="Path" className="cfiles__crumbs">
        {crumbs(path).map((c, i, all) =>
          i === all.length - 1 ? (
            <span key={c.path} aria-current="page" className="cfiles__crumb cfiles__crumb--current">
              {c.label}
            </span>
          ) : (
            <button key={c.path} type="button" className="cfiles__crumb" onClick={() => setPath(c.path)}>
              {c.label}
            </button>
          ),
        )}
      </nav>
      {uploading && (
        <div className="cfiles__upload" role="status">
          <span>
            Uploading {uploadName} … {Math.round((progress ?? 0) * 100)}%
          </span>
          <progress max={1} value={progress ?? 0} aria-label="Upload progress" />
          <Button size="sm" onClick={() => abort.current?.()}>
            Cancel
          </Button>
        </div>
      )}
      {uploadError && (
        <p role="alert" className="sk-error">
          {uploadError}
        </p>
      )}
      {done && <p role="status" className="cfiles__done">{done}</p>}
      {isPending && <p role="status">Loading…</p>}
      {error && (
        <div role="alert" className="sk-error">
          <p>{describeListError(error)}</p>
          <Button variant="ghost" size="sm" onClick={() => void refetch()}>
            Retry
          </Button>
        </div>
      )}
      {data && entries.length === 0 && <EmptyState icon={Folder} title="This directory is empty." />}
      {entries.length > 0 && (
        <ul className="cfiles__list">
          {entries.map((e) => (
            <li key={e.path} className="cfiles__item">
              {e.type === 'DIRECTORY' ? (
                <button type="button" className="cfiles__name cfiles__name--dir" onClick={() => setPath(e.path)}>
                  {e.name}/
                </button>
              ) : (
                <span className="cfiles__name">
                  {e.name}
                  {e.type === 'SYMLINK' && e.linkTarget && <span className="cfiles__muted"> → {e.linkTarget}</span>}
                </span>
              )}
              <span className="cfiles__meta">{e.type === 'FILE' ? formatSize(e.size) : ''}</span>
              <span className="cfiles__meta">{e.modified ? dateFormat.format(new Date(e.modified)) : ''}</span>
              {(e.type === 'FILE' || e.type === 'DIRECTORY') && (
                <a
                  className="ui-btn ui-btn--ghost ui-btn--sm"
                  href={downloadUrl(hostId, containerId, e.path)}
                  download
                  aria-label={`Download ${e.name}`}
                >
                  Download
                </a>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
