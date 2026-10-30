import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ActionConfirmCard } from './ActionConfirmCard.tsx'
import type { ToolCall } from './api.ts'

function card(args: unknown) {
  const call = { id: 'c1', name: 'rename_host', arguments: args } as unknown as ToolCall
  render(<ActionConfirmCard call={call} status="PENDING" />)
}

describe('ActionConfirmCard arguments', () => {
  it('lists parsed arguments', () => {
    card('{"name":"web","count":2}')
    expect(screen.getByText('name')).toBeInTheDocument()
    expect(screen.getByText('web')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
  })

  it('shows the raw text when the arguments are not valid JSON', () => {
    card('{"name": broken')
    expect(screen.getByText('{"name": broken')).toBeInTheDocument()
  })

  it('shows the raw text when the arguments are valid JSON but not an object', () => {
    card('"just text"')
    expect(screen.getByText('"just text"')).toBeInTheDocument()
  })
})
