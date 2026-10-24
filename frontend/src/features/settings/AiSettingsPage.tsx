import { useState, type FormEvent } from "react";
import { Badge, Button, Card, Skeleton, Switch, TextField } from "../../shared/ui/index.ts";
import { ApiError } from "../../shared/api/client.ts";
import {
  useAiSettings,
  useSaveAiSettings,
  useTestAiConnection,
  type AiConnectionStatus,
  type AiProvider,
  type AiProviderConfig,
  type AiSettings,
} from "./api.ts";
import { SettingsPage } from "./SettingsPage.tsx";
import "./SettingsPage.css";
import "./AiSettingsPage.css";

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.code === "INVALID_BASE_URL")
      return "This is not a valid base URL.";
    if (error.code === "ENCRYPTION_KEY_MISSING")
      return "The server has no encryption key. Set SHIPIR_ENCRYPTION_KEY to store the API key.";
    if (error.code === "API_KEY_REQUIRED")
      return "Save an API key first, then test the connection.";
    if (error.status === 400 && error.details?.length)
      return error.details.join(" ");
    if (error.status === 400) return "Check the entered values.";
  }
  return fallback;
}

const TEST_MESSAGES: Record<AiConnectionStatus, { ok: boolean; text: string }> =
  {
    OK: { ok: true, text: "Connection successful." },
    AUTH_FAILED: { ok: false, text: "The provider rejected the API key." },
    UNREACHABLE: { ok: false, text: "The provider could not be reached." },
  };

const PROVIDER_LABELS: Record<AiProvider, string> = {
  OPENAI_COMPATIBLE: "OpenAI-compatible",
  GEMINI: "Google Gemini",
};

interface Draft {
  baseUrl: string;
  model: string;
  apiKey: string;
  clearKey: boolean;
}

function draftOf(config: AiProviderConfig): Draft {
  return {
    baseUrl: config.baseUrl,
    model: config.model,
    apiKey: "",
    clearKey: false,
  };
}

function AiForm({ initial }: { initial: AiSettings }) {
  const [selected, setSelected] = useState<AiProvider>(initial.activeProvider);
  const [drafts, setDrafts] = useState<Record<AiProvider, Draft>>(() => {
    const byProvider = {} as Record<AiProvider, Draft>;
    for (const config of initial.providers)
      byProvider[config.provider] = draftOf(config);
    return byProvider;
  });
  const [enabled, setEnabled] = useState(initial.enabled);
  const [problem, setProblem] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const save = useSaveAiSettings();
  const test = useTestAiConnection();
  const [testProblem, setTestProblem] = useState<string | null>(null);
  const testResult =
    test.isPending || !test.data ? null : TEST_MESSAGES[test.data.status];

  const config = initial.providers.find((p) => p.provider === selected)!;
  const draft = drafts[selected];
  const { baseUrl, model, apiKey, clearKey } = draft;

  const update = (patch: Partial<Draft>) => {
    setDrafts((d) => ({ ...d, [selected]: { ...d[selected], ...patch } }));
    setSaved(false);
  };

  function choose(provider: AiProvider) {
    setSelected(provider);
    setProblem(null);
    setTestProblem(null);
    setSaved(false);
    test.reset();
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    const url = baseUrl.trim();
    const m = model.trim();
    setSaved(false);
    if (url === "" || m === "")
      return setProblem("Enter the base URL and the model.");
    setProblem(null);
    const key = apiKey.trim();
    const body = {
      enabled,
      activeProvider: selected,
      provider: selected,
      baseUrl: url,
      model: m,
      ...(clearKey ? { apiKey: "" } : key !== "" ? { apiKey: key } : {}),
    };
    save.mutate(body, {
      onSuccess: (result) => {
        setSaved(true);
        const savedConfig = result.providers.find(
          (p) => p.provider === selected,
        );
        setDrafts((d) => ({
          ...d,
          [selected]: {
            ...d[selected],
            apiKey: "",
            clearKey: false,
            ...(savedConfig
              ? { baseUrl: savedConfig.baseUrl, model: savedConfig.model }
              : {}),
          },
        }));
        test.reset();
      },
      onError: (err) =>
        setProblem(
          errorMessage(err, "Could not save the AI settings. Try again."),
        ),
    });
  }

  function runTest() {
    setTestProblem(null);
    test.mutate(selected, {
      onError: (err) =>
        setTestProblem(errorMessage(err, "Could not run the test. Try again.")),
    });
  }

  const keyHelp = clearKey
    ? 'The stored key is removed when you save.'
    : config.keyConfigured
      ? `Current key: ${config.apiKeyHint ?? 'stored'}`
      : 'No key stored.'

  return (
    <Card>
      <form className="settings__form" onSubmit={submit} noValidate>
        <div className="ai-provider">
          <div role="radiogroup" aria-label="Provider" className="ai-segmented">
            {initial.providers.map((p) => (
              <button
                key={p.provider}
                type="button"
                role="radio"
                aria-checked={p.provider === selected}
                className="ai-segment"
                onClick={() => choose(p.provider)}
              >
                {PROVIDER_LABELS[p.provider]}
                {p.provider === initial.activeProvider && <Badge tone="ok">Active</Badge>}
              </button>
            ))}
          </div>
          <p className="settings__muted">Saving makes {PROVIDER_LABELS[selected]} the active provider.</p>
        </div>
        <TextField
          label="Base URL"
          type="url"
          value={baseUrl}
          onChange={(e) => update({ baseUrl: e.target.value })}
          autoComplete="off"
          placeholder={selected === 'GEMINI' ? 'https://generativelanguage.googleapis.com' : 'https://api.openai.com/v1'}
        />
        <TextField label="Model" value={model} onChange={(e) => update({ model: e.target.value })} autoComplete="off" />
        <TextField
          label="API key"
          type="password"
          value={apiKey}
          disabled={clearKey}
          onChange={(e) => update({ apiKey: e.target.value })}
          autoComplete="off"
          placeholder={config.keyConfigured ? 'Leave empty to keep the current key' : ''}
          help={keyHelp}
        />
        {(config.keyConfigured || clearKey) && (
          <div>
            <Button size="sm" variant="ghost" onClick={() => update({ clearKey: !clearKey, apiKey: '' })}>
              {clearKey ? 'Keep stored key' : 'Remove stored key'}
            </Button>
          </div>
        )}
        <Switch
          label="Enable assistant"
          checked={enabled}
          onChange={(e) => {
            setEnabled(e.target.checked)
            setSaved(false)
          }}
        />
        {problem && (
          <p role="alert" className="settings__error">
            {problem}
          </p>
        )}
        {saved && (
          <p role="status" className="settings__ok">
            AI settings saved.
          </p>
        )}
        <div className="settings__actions">
          <Button onClick={runTest} loading={test.isPending}>
            Test connection
          </Button>
          <Button variant="primary" type="submit" loading={save.isPending}>
            Save
          </Button>
        </div>
        <p className="settings__muted">The test uses the saved settings of the selected provider. Save changes first.</p>
        {testResult && (
          <p role="status" className={testResult.ok ? 'settings__ok' : 'settings__error'}>
            {testResult.text}
          </p>
        )}
        {testProblem && (
          <p role="alert" className="settings__error">
            {testProblem}
          </p>
        )}
      </form>
    </Card>
  )
}

/** Settings > AI assistant: ADMIN configures the AI providers. The caller restricts access to ADMIN. */
export function AiSettingsPage() {
  const { data, isPending, isError } = useAiSettings()

  return (
    <SettingsPage
      title="AI assistant"
      subtitle="Connect an OpenAI-compatible provider or Google Gemini. The API key is stored encrypted and never shown again."
    >
      {isPending && <Skeleton width="100%" height="var(--space-8)" />}
      {isError && (
        <p role="alert" className="settings__error">
          Could not load the AI settings.
        </p>
      )}
      {data && <AiForm initial={data} />}
    </SettingsPage>
  )
}
