import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, Card, TextField } from '../../shared/ui/index.ts'
import type { ProxySetupRequest, ProxyType } from './api.ts'
import './ProxySetupCard.css'

type Choice = 'automatic' | 'own'

interface Props {
  busy: boolean
  error: string | null
  onSubmit: (request: ProxySetupRequest) => void
}

interface CardOption<T extends string> {
  value: T
  title: string
  description: string
}

/** Large radio cards: native radios (keyboard, screen readers) restyled as pressable tiles. */
function ChoiceCards<T extends string>({
  legend,
  name,
  value,
  options,
  onChange,
}: {
  legend: string
  name: string
  value: T
  options: CardOption<T>[]
  onChange: (value: T) => void
}) {
  return (
    <fieldset className="proxy-setup__group">
      <legend className="proxy-setup__legend">{legend}</legend>
      <div className="proxy-setup__choices">
        {options.map((o) => (
          <label key={o.value} className="proxy-setup__choice">
            <input
              type="radio"
              className="proxy-setup__radio"
              name={name}
              value={o.value}
              checked={value === o.value}
              onChange={() => onChange(o.value)}
            />
            <span className="proxy-setup__choice-title">{o.title}</span>
            <span className="proxy-setup__choice-desc">{o.description}</span>
          </label>
        ))}
      </div>
    </fieldset>
  )
}

/** Simple setup of the managed reverse proxy: engine, e-mail, certificates. */
export function ProxySetupCard({ busy, error, onSubmit }: Props) {
  const { t } = useTranslation('domains')
  const [type, setType] = useState<ProxyType>('TRAEFIK')
  const [email, setEmail] = useState('')
  const [certs, setCerts] = useState<Choice>('automatic')
  const [emailError, setEmailError] = useState<string | null>(null)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const trimmed = email.trim()
    if (type === 'TRAEFIK' && !trimmed) {
      setEmailError(t('ProxySetupCard.emailRequired'))
      return
    }
    setEmailError(null)
    // The server rejects CUSTOM as the default mode: an own certificate is chosen per domain later.
    onSubmit({ type, ...(trimmed && { acmeEmail: trimmed }), defaultCertMode: 'ACME_HTTP' })
  }

  return (
    <Card className="proxy-setup">
      <h2 className="proxy-setup__title">{t('ProxySetupCard.title')}</h2>
      <p className="proxy-setup__intro">{t('ProxySetupCard.intro')}</p>
      <form className="proxy-setup__form" onSubmit={submit} noValidate>
        <ChoiceCards<ProxyType>
          legend={t('ProxySetupCard.engine')}
          name="proxy-type"
          value={type}
          onChange={setType}
          options={[
            { value: 'TRAEFIK', title: t('ProxySetupCard.traefik'), description: t('ProxySetupCard.traefikDesc') },
            { value: 'CADDY', title: t('ProxySetupCard.caddy'), description: t('ProxySetupCard.caddyDesc') },
          ]}
        />
        <TextField
          label={t('ProxySetupCard.email')}
          type="email"
          autoComplete="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          error={emailError}
          help={type === 'TRAEFIK' ? t('ProxySetupCard.emailHelpRequired') : t('ProxySetupCard.emailHelpOptional')}
        />
        <ChoiceCards<Choice>
          legend={t('ProxySetupCard.certificates')}
          name="proxy-certs"
          value={certs}
          onChange={setCerts}
          options={[
            { value: 'automatic', title: t('ProxySetupCard.automatic'), description: t('ProxySetupCard.automaticDesc') },
            { value: 'own', title: t('ProxySetupCard.own'), description: t('ProxySetupCard.ownDesc') },
          ]}
        />
        {certs === 'own' && (
          <p className="proxy-setup__note" role="note">
            {t('ProxySetupCard.ownNote')}
          </p>
        )}
        {error && (
          <p role="alert" className="proxy-setup__error">
            {error}
          </p>
        )}
        <div className="proxy-setup__actions">
          <Button type="submit" variant="primary" loading={busy}>
            {t('ProxySetupCard.submit')}
          </Button>
        </div>
      </form>
    </Card>
  )
}
