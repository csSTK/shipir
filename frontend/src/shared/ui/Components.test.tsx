import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Trash2 } from 'lucide-react'
import { useState } from 'react'
import {
  Badge,
  Button,
  Card,
  ConfirmDialog,
  Dialog,
  EmptyState,
  IconButton,
  Menu,
  PageHeader,
  SearchField,
  Skeleton,
  Tabs,
  Toolbar,
  Tooltip,
} from './index.ts'

afterEach(cleanup)

describe('Button', () => {
  it('fires onClick and supports variants', () => {
    const onClick = vi.fn()
    render(<Button variant="primary" onClick={onClick}>Save</Button>)
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(onClick).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button')).toHaveClass('ui-btn--primary')
  })

  it('blocks clicks and sets aria-busy while loading', () => {
    const onClick = vi.fn()
    render(<Button loading onClick={onClick}>Save</Button>)
    const button = screen.getByRole('button', { name: 'Save' })
    expect(button).toHaveAttribute('aria-busy', 'true')
    expect(button).toBeDisabled()
    fireEvent.click(button)
    expect(onClick).not.toHaveBeenCalled()
  })

  it('does not submit forms by default', () => {
    expect(render(<Button>Go</Button>).getByRole('button')).toHaveAttribute('type', 'button')
  })
})

describe('IconButton', () => {
  it('is named by aria-label and clickable', () => {
    const onClick = vi.fn()
    render(<IconButton icon={Trash2} aria-label="Delete" onClick={onClick} />)
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(onClick).toHaveBeenCalled()
  })
})

describe('Tooltip', () => {
  it('shows on keyboard focus', async () => {
    render(<Tooltip content="More info"><button>Trigger</button></Tooltip>)
    screen.getByRole('button').focus()
    expect(await screen.findByRole('tooltip')).toHaveTextContent('More info')
  })
})

describe('Menu', () => {
  function setup(onSelect = vi.fn()) {
    render(
      <Menu
        trigger={<button>Actions</button>}
        items={[
          { label: 'Rename', onSelect },
          { type: 'separator' },
          { label: 'Delete', onSelect: vi.fn(), danger: true },
        ]}
      />,
    )
    return onSelect
  }

  it('opens from the keyboard, selects an item and closes', async () => {
    const onSelect = setup()
    const trigger = screen.getByRole('button', { name: 'Actions' })
    trigger.focus()
    fireEvent.keyDown(trigger, { key: 'Enter' })
    const item = await screen.findByRole('menuitem', { name: 'Rename' })
    fireEvent.click(item)
    expect(onSelect).toHaveBeenCalled()
    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument())
  })

  it('closes on Escape and returns focus to the trigger', async () => {
    setup()
    const trigger = screen.getByRole('button', { name: 'Actions' })
    trigger.focus()
    fireEvent.keyDown(trigger, { key: 'Enter' })
    const menu = await screen.findByRole('menu')
    fireEvent.keyDown(menu, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument())
    expect(trigger).toHaveFocus()
  })
})

describe('Dialog', () => {
  function Harness({ onChange }: { onChange?: (o: boolean) => void }) {
    const [open, setOpen] = useState(false)
    return (
      <>
        <button onClick={() => setOpen(true)}>Open</button>
        <Dialog open={open} onOpenChange={(o) => { setOpen(o); onChange?.(o) }} title="Edit name" description="Pick a new name" footer={<button>Save</button>}>
          <input aria-label="Name" />
        </Dialog>
      </>
    )
  }

  it('is a named, described modal; focuses inside; Esc closes and restores focus', async () => {
    render(<Harness />)
    const opener = screen.getByRole('button', { name: 'Open' })
    opener.focus()
    fireEvent.click(opener)
    const dialog = await screen.findByRole('dialog', { name: 'Edit name' })
    expect(dialog).toHaveAccessibleDescription('Pick a new name')
    expect(dialog.contains(document.activeElement)).toBe(true)
    fireEvent.keyDown(dialog, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await waitFor(() => expect(opener).toHaveFocus())
  })

  it('closes via the close button', async () => {
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)
    fireEvent.click(screen.getByRole('button', { name: 'Open' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Close' }))
    expect(onChange).toHaveBeenLastCalledWith(false)
  })
})

describe('Button asChild', () => {
  it('renders the child element with button classes and keeps its label', () => {
    render(
      <Button asChild variant="ghost-danger" size="sm">
        <a href="/x">Go</a>
      </Button>,
    )
    const link = screen.getByRole('link', { name: 'Go' })
    expect(link).toHaveAttribute('href', '/x')
    expect(link).toHaveClass('ui-btn', 'ui-btn--ghost-danger', 'ui-btn--sm')
    expect(screen.queryByRole('button')).toBeNull()
  })
})

describe('Button asChild handlers', () => {
  it('forwards onClick and ref, and ignores clicks while loading', () => {
    const fn = vi.fn()
    const ref = { current: null as HTMLButtonElement | null }
    const { rerender } = render(
      <Button asChild onClick={fn} ref={ref}>
        <a href="#x">Go</a>
      </Button>,
    )
    fireEvent.click(screen.getByRole('link', { name: 'Go' }))
    expect(fn).toHaveBeenCalledTimes(1)
    expect(ref.current).toBe(screen.getByRole('link', { name: 'Go' }))
    rerender(
      <Button asChild onClick={fn} loading>
        <a href="#x">Go</a>
      </Button>,
    )
    fireEvent.click(screen.getByRole('link', { name: 'Go' }))
    expect(fn).toHaveBeenCalledTimes(1)
  })
})

describe('Dialog size', () => {
  it('sets the size modifier class', async () => {
    render(<Dialog open onOpenChange={() => {}} title="Wide" size="lg">body</Dialog>)
    expect((await screen.findByRole('dialog')).className).toContain('ui-dialog--lg')
  })
})

describe('ConfirmDialog extras', () => {
  it('shows details and an inline error and disables confirm while loading', async () => {
    const onConfirm = vi.fn()
    render(
      <ConfirmDialog open onOpenChange={() => {}} title="Roll back?" confirmLabel="Roll back" loading error="Failed" details={<div>diff here</div>} onConfirm={onConfirm}>
        Sure?
      </ConfirmDialog>,
    )
    expect(await screen.findByText('diff here')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Failed')
    expect(screen.getByRole('button', { name: 'Roll back' })).toBeDisabled()
  })
})

describe('ConfirmDialog', () => {
  it('confirms and cancels', async () => {
    const onConfirm = vi.fn()
    const onOpenChange = vi.fn()
    render(<ConfirmDialog open onOpenChange={onOpenChange} title="Delete?" confirmLabel="Delete" danger onConfirm={onConfirm} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }))
    expect(onConfirm).toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

describe('Badge', () => {
  it('shows text with a decorative dot', () => {
    const { container } = render(<Badge tone="ok">Running</Badge>)
    expect(screen.getByText('Running')).toHaveClass('ui-badge--ok')
    expect(container.querySelector('.ui-dot')).toHaveAttribute('aria-hidden', 'true')
  })
})

describe('layout pieces', () => {
  it('PageHeader renders heading, subtitle and actions', () => {
    render(<PageHeader title="Containers" subtitle="On host a" actions={<button>Add</button>} />)
    expect(screen.getByRole('heading', { level: 1, name: 'Containers' })).toBeInTheDocument()
    expect(screen.getByText('On host a')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add' })).toBeInTheDocument()
  })

  it('Toolbar is a labelled toolbar and Card wraps content', () => {
    render(<><Toolbar label="Filters"><button>x</button></Toolbar><Card>Body</Card></>)
    expect(screen.getByRole('toolbar', { name: 'Filters' })).toBeInTheDocument()
    expect(screen.getByText('Body')).toHaveClass('ui-card')
  })

  it('SearchField is a named searchbox', () => {
    const onChange = vi.fn()
    render(<SearchField aria-label="Search containers" onChange={onChange} />)
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search containers' }), { target: { value: 'web' } })
    expect(onChange).toHaveBeenCalled()
  })

  it('EmptyState shows title, description and action', () => {
    render(<EmptyState icon={Trash2} title="Nothing here" description="Add one" action={<button>Add</button>} />)
    expect(screen.getByText('Nothing here')).toBeInTheDocument()
    expect(screen.getByText('Add one')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add' })).toBeInTheDocument()
  })

  it('Skeleton is hidden from assistive tech', () => {
    const { container } = render(<Skeleton width={40} />)
    expect(container.firstChild).toHaveAttribute('aria-hidden', 'true')
  })
})

describe('Tabs', () => {
  it('switches panels by click and exposes tab semantics', () => {
    render(<Tabs label="Sections" items={[{ value: 'a', label: 'Alpha', content: 'A body' }, { value: 'b', label: 'Beta', content: 'B body' }]} />)
    expect(screen.getByRole('tablist', { name: 'Sections' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Alpha' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('A body')).toBeVisible()
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Beta' }))
    expect(screen.getByRole('tab', { name: 'Beta' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('B body')).toBeInTheDocument()
  })

  it('moves with arrow keys', async () => {
    render(<Tabs label="Sections" items={[{ value: 'a', label: 'Alpha', content: 'A' }, { value: 'b', label: 'Beta', content: 'B' }]} />)
    const alpha = screen.getByRole('tab', { name: 'Alpha' })
    alpha.focus()
    fireEvent.keyDown(alpha, { key: 'ArrowRight' })
    await waitFor(() => expect(screen.getByRole('tab', { name: 'Beta' })).toHaveFocus())
  })
})
