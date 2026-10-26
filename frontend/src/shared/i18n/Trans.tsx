import { Trans as I18nTrans, type TransProps } from 'react-i18next'

/**
 * `Trans` with interpolated values HTML-escaped before the markup is parsed, so a value (a resource name,
 * a username) can never close or open mapped tags such as `<strong>`. `shouldUnescape` turns entities back
 * into text, so `&` still renders as `&`. Use this instead of importing `Trans` from react-i18next.
 */
export function Trans(props: TransProps<string>) {
  return <I18nTrans shouldUnescape tOptions={{ interpolation: { escapeValue: true } }} {...props} />
}
