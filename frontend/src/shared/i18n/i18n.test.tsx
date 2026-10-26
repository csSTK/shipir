import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { i18n } from './index.ts'
import { Trans } from './Trans.tsx'

describe('i18n setup', () => {
  it('runs in English', () => {
    expect(i18n.language).toBe('en')
  })

  it('does not let an interpolated value break out of the mapped markup', () => {
    i18n.addResource('en', 'common', 'test.delete', 'Delete <strong>{{name}}</strong>?')
    const { container } = render(
      <Trans t={i18n.getFixedT('en', 'common')} i18nKey="test.delete" values={{ name: '</strong><strong>EVIL</strong>' }} components={{ strong: <strong /> }} />,
    )
    expect(container.querySelectorAll('strong')).toHaveLength(1)
    expect(container.querySelector('strong')).toHaveTextContent('</strong><strong>EVIL</strong>')
  })

  it('renders an ampersand in a value as an ampersand', () => {
    i18n.addResource('en', 'common', 'test.amp', 'Delete <strong>{{name}}</strong>?')
    const { container } = render(<Trans t={i18n.getFixedT('en', 'common')} i18nKey="test.amp" values={{ name: 'a & b' }} components={{ strong: <strong /> }} />)
    expect(container).toHaveTextContent('Delete a & b?')
    expect(i18n.t('common:test.amp', { name: 'a & b' })).toContain('a & b')
  })
})
