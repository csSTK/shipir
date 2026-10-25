import { useState } from 'react'
import { Button, TextField } from '../../shared/ui/index.ts'
import './OneTimeSecret.css'

/**
 * A secret shown exactly once (API token, webhook URL): read-only field, Copy button and a manual-copy fallback
 * when the clipboard is unavailable. The value lives only in the caller's state; nothing is persisted here.
 */
export function OneTimeSecret({ label, value }: { label: string; value: string }) {
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle')

  async function copy() {
    try {
      await navigator.clipboard.writeText(value)
      setState('copied')
    } catch {
      setState('failed')
    }
  }

  return (
    <div className="otsecret">
      <div className="otsecret__row">
        <TextField
          label={label}
          value={value}
          readOnly
          spellCheck={false}
          autoComplete="off"
          className="otsecret__input"
          onFocus={(e) => e.currentTarget.select()}
        />
        <Button className="otsecret__copy" onClick={() => void copy()}>
          {state === 'copied' ? 'Copied' : 'Copy'}
        </Button>
      </div>
      <p className="otsecret__hint" role="status">
        {state === 'failed' && 'Could not copy automatically. Select the text and copy it manually.'}
      </p>
    </div>
  )
}
