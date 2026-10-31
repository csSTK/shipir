import { useRef, useState, type ChangeEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '../../shared/ui/index.ts'
import type { EnvEntry } from './api.ts'
import { parseEnv } from './editor/index.ts'
import './ImportFileFields.css'

/** Client-side limit for both files; nothing is uploaded before "Create deployment". */
export const MAX_IMPORT_BYTES = 512 * 1024

export interface ImportedCompose {
  fileName: string
  text: string
}
export interface ImportedEnv {
  fileName: string
  entries: EnvEntry[]
}

/** The file is read in the browser with `File.text()`; a NUL or replacement character means it is not text. */
const looksBinary = (text: string) => text.includes('\u0000') || text.includes('�')

type Kind = 'compose' | 'env'

function FilePicker({
  label,
  buttonLabel,
  accept,
  fileName,
  onFile,
}: {
  label: string
  buttonLabel: string
  accept?: string
  fileName: string | null
  onFile: (file: File) => void
}) {
  const { t } = useTranslation('deployments')
  const input = useRef<HTMLInputElement>(null)
  function change(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (file) onFile(file)
    e.target.value = '' // picking the same file again must fire a change
  }
  return (
    <div className="ui-field">
      <span className="ui-field__label">{label}</span>
      <div className="if-picker">
        <input ref={input} type="file" className="if-picker__input" aria-label={label} accept={accept} tabIndex={-1} onChange={change} />
        <Button aria-label={t('ImportFileFields.chooseFileFor', { field: label })} onClick={() => input.current?.click()}>{buttonLabel}</Button>
        <span className="if-picker__name">{fileName ?? t('ImportFileFields.noFileChosen')}</span>
      </div>
    </div>
  )
}

/**
 * "Import file" source of the add dialog: a Compose file and an optional env file, read locally.
 * A rejected file shows an inline error and is not reported to the dialog, so earlier imports stay as they were.
 */
export function ImportFileFields({
  onCompose,
  onEnv,
}: {
  onCompose: (file: ImportedCompose) => void
  onEnv: (file: ImportedEnv) => void
}) {
  const { t } = useTranslation('deployments')
  const [composeName, setComposeName] = useState<string | null>(null)
  const [envName, setEnvName] = useState<string | null>(null)
  const [error, setError] = useState<{ kind: Kind; message: string } | null>(null)

  async function read(kind: Kind, file: File): Promise<string | null> {
    if (file.size > MAX_IMPORT_BYTES) {
      setError({ kind, message: t('ImportFileFields.tooLarge', { name: file.name, limit: MAX_IMPORT_BYTES / 1024 }) })
      return null
    }
    let text: string
    try {
      text = await file.text()
    } catch {
      setError({ kind, message: t('ImportFileFields.unreadable', { name: file.name }) })
      return null
    }
    if (looksBinary(text)) {
      setError({ kind, message: t('ImportFileFields.notText', { name: file.name }) })
      return null
    }
    return text
  }

  async function pickCompose(file: File) {
    setError(null)
    if (!/\.ya?ml$/i.test(file.name)) {
      setError({ kind: 'compose', message: t('ImportFileFields.wrongExtension', { name: file.name }) })
      return
    }
    const text = await read('compose', file)
    if (text === null) return
    setComposeName(file.name)
    onCompose({ fileName: file.name, text })
  }

  async function pickEnv(file: File) {
    setError(null)
    const text = await read('env', file)
    if (text === null) return
    const { entries, invalidLines } = parseEnv(text)
    if (invalidLines.length > 0) {
      setError({ kind: 'env', message: t('ImportFileFields.invalidEnvLines', { name: file.name, lines: invalidLines.slice(0, 5).join(', ') }) })
      return
    }
    setEnvName(file.name)
    onEnv({ fileName: file.name, entries })
  }

  return (
    <div className="if-fields">
      <FilePicker
        label={t('ImportFileFields.composeFile')}
        buttonLabel={t('ImportFileFields.chooseFile')}
        accept=".yml,.yaml"
        fileName={composeName}
        onFile={(f) => void pickCompose(f)}
      />
      <FilePicker
        label={t('ImportFileFields.envFile')}
        buttonLabel={t('ImportFileFields.chooseFile')}
        fileName={envName}
        onFile={(f) => void pickEnv(f)}
      />
      {error && (
        <p role="alert" className="ui-error">
          {error.message}
        </p>
      )}
    </div>
  )
}
