import { useTranslation } from 'react-i18next'
import i18n from 'i18next'
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
      return i18n.t('settings:AiSettingsPage.thisIsNotAValid');
    if (error.code === "ENCRYPTION_KEY_MISSING")
      return i18n.t('settings:AiSettingsPage.theServerHasNoEncryption');
    if (error.code === "API_KEY_REQUIRED")
      return i18n.t('settings:AiSettingsPage.saveAnApiKeyFirst');
    if (error.status === 400 && error.details?.length)
      return error.details.join(" ");
    if (error.status === 400) return i18n.t('settings:AiSettingsPage.checkTheEnteredValues');
  }
  return fallback;
}

const TEST_MESSAGES: Record<AiConnectionStatus, { ok: boolean; text: string }> =
  {
    OK: { ok: true, get text() { return i18n.t('settings:AiSettingsPage.connectionSuccessful') } },
    AUTH_FAILED: { ok: false, get text() { return i18n.t('settings:AiSettingsPage.theProviderRejectedTheApi') } },
    UNREACHABLE: { ok: false, get text() { return i18n.t('settings:AiSettingsPage.theProviderCouldNotBe') } },
  };

const PROVIDER_LABELS: Record<AiProvider, string> = {
  get OPENAI_COMPATIBLE() { return i18n.t('settings:AiSettingsPage.openaiCompatible') },
  get GEMINI() { return i18n.t('settings:AiSettingsPage.googleGemini') },
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
  const { t } = useTranslation('settings')
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
      return setProblem(t('AiSettingsPage.enterTheBaseUrlAnd'));
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
          errorMessage(err, t('AiSettingsPage.couldNotSaveTheAi')),
        ),
    });
  }

  function runTest() {
    setTestProblem(null);
    test.mutate(selected, {
      onError: (err) =>
        setTestProblem(errorMessage(err, t('AiSettingsPage.couldNotRunTheTest'))),
    });
  }

  const keyHelp = clearKey
    ? t('AiSettingsPage.theStoredKeyIsRemoved')
    : config.keyConfigured
      ? t('AiSettingsPage.currentKeyApikeyhint', { apiKeyHint: config.apiKeyHint ?? 'stored' })
      : t('AiSettingsPage.noKeyStored')

  return (
    <Card>
      <form className="settings__form" onSubmit={submit} noValidate>
        <div className="ai-provider">
          <div role="radiogroup" aria-label={t('AiSettingsPage.provider')} className="ai-segmented">
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
                {p.provider === initial.activeProvider && <Badge tone="ok">{t('AiSettingsPage.active')}</Badge>}
              </button>
            ))}
          </div>
          <p className="settings__muted">{t('AiSettingsPage.savingMakesProviderLabelsThe', { PROVIDER_LABELS: PROVIDER_LABELS[selected] })}</p>
        </div>
        <TextField
          label={t('AiSettingsPage.baseUrl')}
          type="url"
          value={baseUrl}
          onChange={(e) => update({ baseUrl: e.target.value })}
          autoComplete="off"
          placeholder={selected === 'GEMINI' ? 'https://generativelanguage.googleapis.com' : 'https://api.openai.com/v1'}
        />
        <TextField label={t('AiSettingsPage.model')} value={model} onChange={(e) => update({ model: e.target.value })} autoComplete="off" />
        <TextField
          label={t('AiSettingsPage.apiKey')}
          type="password"
          value={apiKey}
          disabled={clearKey}
          onChange={(e) => update({ apiKey: e.target.value })}
          autoComplete="off"
          placeholder={config.keyConfigured ? t('AiSettingsPage.leaveEmptyToKeepThe') : ''}
          help={keyHelp}
        />
        {(config.keyConfigured || clearKey) && (
          <div>
            <Button size="sm" variant="ghost" onClick={() => update({ clearKey: !clearKey, apiKey: '' })}>
              {clearKey ? t('AiSettingsPage.keepStoredKey') : t('AiSettingsPage.removeStoredKey')}
            </Button>
          </div>
        )}
        <Switch
          label={t('AiSettingsPage.enableAssistant')}
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
            {t('AiSettingsPage.aiSettingsSaved')}
          </p>
        )}
        <div className="settings__actions">
          <Button onClick={runTest} loading={test.isPending}>
            {t('AiSettingsPage.testConnection')}
          </Button>
          <Button variant="primary" type="submit" loading={save.isPending}>
            {t('AiSettingsPage.save')}
          </Button>
        </div>
        <p className="settings__muted">{t('AiSettingsPage.theTestUsesTheSaved')}</p>
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
  const { t } = useTranslation('settings')
  const { data, isPending, isError } = useAiSettings()

  return (
    <SettingsPage
      title={t('AiSettingsPage.aiAssistant')}
      subtitle={t('AiSettingsPage.connectAnOpenaiCompatibleProvider')}
    >
      {isPending && <Skeleton width="100%" height="var(--space-8)" />}
      {isError && (
        <p role="alert" className="settings__error">
          {t('AiSettingsPage.couldNotLoadTheAi')}
        </p>
      )}
      {data && <AiForm initial={data} />}
    </SettingsPage>
  )
}
