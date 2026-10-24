import { useState, type FormEvent } from "react";
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

  return (
    <form className="ai-form" onSubmit={submit} noValidate>
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
            {p.provider === initial.activeProvider && (
              <span className="ai-active">Active</span>
            )}
          </button>
        ))}
      </div>
      <small className="ai-muted">
        Saving makes {PROVIDER_LABELS[selected]} the active provider.
      </small>
      <label className="ai-field">
        <span>Base URL</span>
        <input
          type="url"
          value={baseUrl}
          onChange={(e) => update({ baseUrl: e.target.value })}
          autoComplete="off"
          placeholder={
            selected === "GEMINI"
              ? "https://generativelanguage.googleapis.com"
              : "https://api.openai.com/v1"
          }
        />
      </label>
      <label className="ai-field">
        <span>Model</span>
        <input
          value={model}
          onChange={(e) => update({ model: e.target.value })}
          autoComplete="off"
        />
      </label>
      <label className="ai-field">
        <span>API key</span>
        <input
          type="password"
          value={apiKey}
          disabled={clearKey}
          onChange={(e) => update({ apiKey: e.target.value })}
          autoComplete="off"
          placeholder={
            config.keyConfigured ? "Leave empty to keep the current key" : ""
          }
        />
      </label>
      <div className="ai-keyline">
        <small className="ai-muted">
          {clearKey
            ? "The stored key is removed when you save."
            : config.keyConfigured
              ? `Current key: ${config.apiKeyHint ?? "stored"}`
              : "No key stored."}
        </small>
        {(config.keyConfigured || clearKey) && (
          <button
            type="button"
            className="ai-btn ai-btn--text"
            onClick={() => update({ clearKey: !clearKey, apiKey: "" })}
          >
            {clearKey ? "Keep stored key" : "Remove stored key"}
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
            setEnabled(e.target.checked);
            setSaved(false);
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
        <button
          type="button"
          className="ai-btn"
          onClick={runTest}
          disabled={test.isPending}
        >
          {test.isPending ? "Testing…" : "Test connection"}
        </button>
        <button
          type="submit"
          className="ai-btn ai-btn--primary"
          disabled={save.isPending}
        >
          {save.isPending ? "Saving…" : "Save"}
        </button>
      </div>
      <small className="ai-muted">
        The test uses the saved settings of the selected provider. Save changes
        first.
      </small>
      {testResult && (
        <p role="status" className={testResult.ok ? "ai-ok" : "ai-error"}>
          {testResult.text}
        </p>
      )}
      {testProblem && (
        <p role="alert" className="ai-error">
          {testProblem}
        </p>
      )}
    </form>
  );
}

/** Settings > AI assistant: ADMIN configures the AI providers. The caller restricts access to ADMIN. */
export function AiSettingsPage() {
  const { data, isPending, isError } = useAiSettings();

  return (
    <section className="ai">
      <h2 className="ai-title">AI assistant</h2>
      <p className="ai-muted">
        Connect an OpenAI-compatible provider or Google Gemini. The API key is
        stored encrypted and never shown again.
      </p>
      {isPending && (
        <div aria-busy="true" className="ai-muted">
          Loading…
        </div>
      )}
      {isError && (
        <p role="alert" className="ai-error">
          Could not load the AI settings.
        </p>
      )}
      {data && <AiForm initial={data} />}
    </section>
  );
}
