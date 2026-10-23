import { useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import {
  useAiSettings,
  useSaveAiSettings,
  useTestAiConnection,
  type AiConnectionStatus,
  type AiSettings,
} from './api.ts'
import './AiSettingsPage.css'

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.code === 'INVALID_BASE_URL') return 'This is not a valid base URL.'
    if (error.code === 'ENCRYPTION_KEY_MISSING')
      return 'The server has no encryption key. Set SHIPIR_ENCRYPTION_KEY to store the API key.'
    if (error.code === 'API_KEY_REQUIRED') return 'Save an API key first, then test the connection.'
    if (error.status === 400 && error.details?.length) return error.details.join(' ')
    if (error.status === 400) return 'Check the entered values.'
  }
  return fallback
}

const TEST_MESSAGES: Record<AiConnectionStatus, { ok: boolean; text: string }> = {
  OK: { ok: true, text: 'Connection successful.' },
  AUTH_FAILED: { ok: false, text: 'The provider rejected the API key.' },
  UNREACHABLE: { ok: false, text: 'The provider could not be reached.' },
}

function AiForm({ initial }: { initial: AiSettings }) {
  const [baseUrl, setBaseUrl] = useState(initial.baseUrl)
  const [model, setModel] = useState(initial.model)
  const [enabled, setEnabled] = useState(initial.enabled)
  const [apiKey, setApiKey] = useState('')
  const [clearKey, setClearKey] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const save = useSaveAiSettings()
  const test = useTestAiConnection()
  const [testProblem, setTestProblem] = useState<string | null>(null)
  const testResult = test.isPending || !test.data ? null : TEST_MESSAGES[test.data.status]

  const touch = () => setSaved(false)

  function submit(e: FormEvent) {
    e.preventDefault()
    const url = baseUrl.trim()
    const m = model.trim()
    setSaved(false)
    if (url === '' || m === '') return setProblem('Enter the base URL and the model.')
    setProblem(null)
    const key = apiKey.trim()
    const body = {
      baseUrl: url,
      model: m,
      enabled,
      ...(clearKey ? { apiKey: '' } : key !== '' ? { apiKey: key } : {}),
    }
    save.mutate(body, {
      onSuccess: () => {
        setSaved(true)
        setApiKey('')
        setClearKey(false)
        test.reset()
      },
      onError: (err) => setProblem(errorMessage(err, 'Could not save the AI settings. Try again.')),
    })
  }

  function runTest() {
    setTestProblem(null)
    test.mutate(undefined, {
      onError: (err) => setTestProblem(errorMessage(err, 'Could not run the test. Try again.')),
    })
  }

  return (
    <form className="ai-form" onSubmit={submit} noValidate>
      <label className="ai-field">
        <span>Base URL</span>
        <input
          type="url"
          value={baseUrl}
          onChange={(e) => {
            setBaseUrl(e.target.value)
            touch()
          }}
          autoComplete="off"
          placeholder="https://api.openai.com/v1"
        />
      </label>
      <label className="ai-field">
        <span>Model</span>
        <input
          value={model}
          onChange={(e) => {
            setModel(e.target.value)
            touch()
          }}
          autoComplete="off"
        />
      </label>
      <label className="ai-field">
        <span>API key</span>
        <input
          type="password"
          value={apiKey}
          disabled={clearKey}
          onChange={(e) => {
            setApiKey(e.target.value)
            touch()
          }}
          autoComplete="off"
          placeholder={initial.keyConfigured ? 'Leave empty to keep the current key' : ''}
        />
      </label>
      <div className="ai-keyline">
        <small className="ai-muted">
          {clearKey
            ? 'The stored key is removed when you save.'
            : initial.keyConfigured
              ? `Current key: ${initial.apiKeyHint ?? 'stored'}`
              : 'No key stored.'}
        </small>
        {(initial.keyConfigured || clearKey) && (
          <button
            type="button"
            className="ai-btn ai-btn--text"
            onClick={() => {
              setClearKey(!clearKey)
              setApiKey('')
              touch()
            }}
          >
            {clearKey ? 'Keep stored key' : 'Remove stored key'}
          </button>
        )}
      </div>
      <label className="ai-toggle">
        <span>Enable assistant</span>
        <input
          type="checkbox"
          role="switch"
          checked={enabled}
          onChange={(e) => {
            setEnabled(e.target.checked)
            touch()
          }}
          aria-label="Enable assistant"
        />
      </label>
      {problem && (
        <p role="alert" className="ai-error">
          {problem}
        </p>
      )}
      {saved && (
        <p role="status" className="ai-ok">
          AI settings saved.
        </p>
      )}
      <div className="ai-actions">
        <button type="button" className="ai-btn" onClick={runTest} disabled={test.isPending}>
          {test.isPending ? 'Testing…' : 'Test connection'}
        </button>
        <button type="submit" className="ai-btn ai-btn--primary" disabled={save.isPending}>
          {save.isPending ? 'Saving…' : 'Save'}
        </button>
      </div>
      <small className="ai-muted">The test uses the saved settings. Save changes first.</small>
      {testResult && (
        <p role="status" className={testResult.ok ? 'ai-ok' : 'ai-error'}>
          {testResult.text}
        </p>
      )}
      {testProblem && (
        <p role="alert" className="ai-error">
          {testProblem}
        </p>
      )}
    </form>
  )
}

/** Settings > AI assistant: ADMIN configures the OpenAI-compatible provider. The caller restricts access to ADMIN. */
export function AiSettingsPage() {
  const { data, isPending, isError } = useAiSettings()

  return (
    <section className="ai">
      <h2 className="ai-title">AI assistant</h2>
      <p className="ai-muted">
        Connect an OpenAI-compatible provider. The API key is stored encrypted and never shown again.
      </p>
      {isPending && <div aria-busy="true" className="ai-muted">Loading…</div>}
      {isError && (
        <p role="alert" className="ai-error">
          Could not load the AI settings.
        </p>
      )}
      {data && <AiForm initial={data} />}
    </section>
  )
}
