import { ArrowRight, Box, Layers, Search, Server, type LucideIcon } from 'lucide-react'
import { motion } from 'motion/react'
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import type { CurrentUser } from '../../features/auth/index.ts'
import { MIN_QUERY_LENGTH, routeFor, useSearch, type SearchResult } from './useSearch.ts'
import { springSmooth, usePopMotion } from '../../shared/ui/motion.ts'
import './CommandPalette.css'

interface Props {
  role: CurrentUser['role']
  open: boolean
  onClose: () => void
}

interface Item {
  icon: LucideIcon
  key: string
  label: string
  meta?: string
  to: string
}

interface Group {
  label: string
  items: Item[]
}

const NAV: { label: string; to: string; admin?: boolean }[] = [
  { label: 'Environments', to: '/' },
  { label: 'Assistant', to: '/assistant' },
  { label: 'Notifications', to: '/notifications' },
  { label: 'Account', to: '/account' },
  { label: 'Users', to: '/settings/users', admin: true },
  { label: 'Notification webhooks', to: '/settings/notifications', admin: true },
  { label: 'Retention', to: '/settings/retention', admin: true },
  { label: 'Git credentials', to: '/settings/git', admin: true },
  { label: 'Registries', to: '/settings/registries', admin: true },
  { label: 'AI assistant settings', to: '/settings/ai', admin: true },
  { label: 'Shared chats', to: '/settings/ai/shared', admin: true },
  { label: 'Audit log', to: '/settings/audit-log', admin: true },
]

const TYPE_GROUPS: { type: SearchResult['type']; label: string; icon: LucideIcon }[] = [
  { type: 'HOST', label: 'Environments', icon: Server },
  { type: 'DEPLOYMENT', label: 'Deployments', icon: Layers },
  { type: 'CONTAINER', label: 'Containers', icon: Box },
]

function toItem(r: SearchResult, index: number, icon: LucideIcon): Item {
  const meta = [r.type === 'HOST' ? null : r.detail, r.type === 'HOST' ? null : r.hostName]
    .filter(Boolean)
    .join(' · ')
  return { icon, key: `${r.type}-${r.id ?? r.name}-${r.hostId}-${index}`, label: r.name, meta: meta || undefined, to: routeFor(r) }
}

/** Cmd/Ctrl+K palette: navigation commands plus live search over environments, deployments and containers. */
export function CommandPalette({ role, open, onClose }: Props) {
  if (!open) return null
  return <PaletteDialog role={role} onClose={onClose} />
}

function PaletteDialog({ role, onClose }: { role: Props['role']; onClose: () => void }) {
  const navigate = useNavigate()
  const pop = usePopMotion(springSmooth, 0.97)
  const id = useId()
  const listId = `${id}-list`
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)
  const search = useSearch(query)

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    inputRef.current?.focus()
    return () => previous?.focus?.()
  }, [])

  const groups = useMemo<Group[]>(() => {
    const needle = query.trim().toLowerCase()
    const commands = NAV.filter((c) => (!c.admin || role === 'ADMIN') && c.label.toLowerCase().includes(needle))
    const out: Group[] = []
    if (commands.length) {
      out.push({ label: 'Go to', items: commands.map((c) => ({ icon: ArrowRight, key: `nav-${c.to}`, label: c.label, to: c.to })) })
    }
    const results = search.data?.results ?? []
    for (const { type, label, icon } of TYPE_GROUPS) {
      const items = results.map((r, i) => toItem(r, i, icon)).filter((_, i) => results[i].type === type)
      if (items.length) out.push({ label, items })
    }
    return out
  }, [query, role, search.data])

  const flat = groups.flatMap((g) => g.items)
  const current = Math.min(active, Math.max(flat.length - 1, 0))
  const optionId = (key: string) => `${id}-opt-${key}`

  const choose = (item: Item | undefined) => {
    if (!item) return
    navigate(item.to)
    onClose()
  }

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key === 'Escape') {
      e.preventDefault()
      onClose()
    } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      if (!flat.length) return
      const step = e.key === 'ArrowDown' ? 1 : -1
      setActive((current + step + flat.length) % flat.length)
    } else if (e.key === 'Enter') {
      e.preventDefault()
      choose(flat[current])
    } else if (e.key === 'Tab') {
      // The input is the only tab stop; keep focus trapped in the dialog.
      e.preventDefault()
      inputRef.current?.focus()
    }
  }

  const needsMore = query.trim().length > 0 && query.trim().length < MIN_QUERY_LENGTH
  const noResults = !flat.length && !search.isSearching && !search.isError
  const activeItem = flat[current]

  useEffect(() => {
    if (activeItem) document.getElementById(optionId(activeItem.key))?.scrollIntoView?.({ block: 'nearest' })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeItem?.key])

  return (
    <motion.div className="palette__scrim" initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.12 }} onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <motion.div className="palette" role="dialog" aria-modal="true" aria-label="Command palette" onKeyDown={onKeyDown} initial={pop.initial} animate={pop.animate} transition={pop.transition}>
        <div className="palette__field">
          <Search size={20} strokeWidth={1.75} aria-hidden="true" />
        <input
          ref={inputRef}
          className="palette__input"
          role="combobox"
          aria-expanded="true"
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={activeItem ? optionId(activeItem.key) : undefined}
          aria-label="Search or jump to"
          placeholder="Search environments, deployments, containers…"
          autoComplete="off"
          spellCheck={false}
          value={query}
          onChange={(e) => {
            setQuery(e.target.value)
            setActive(0)
          }}
        />
        </div>
        <div id={listId} role="listbox" aria-label="Results" className="palette__list">
          {groups.map((group) => (
            <div key={group.label} role="group" aria-label={group.label} className="palette__group">
              <div className="palette__heading" aria-hidden="true">
                {group.label}
              </div>
              {group.items.map((item) => (
                <div
                  key={item.key}
                  id={optionId(item.key)}
                  role="option"
                  aria-selected={item === activeItem}
                  className="palette__option"
                  onMouseMove={() => setActive(flat.indexOf(item))}
                  onClick={() => choose(item)}
                >
                  <item.icon size={16} strokeWidth={1.75} aria-hidden="true" className="palette__icon" />
                  <span className="palette__label">{item.label}</span>
                  {item.meta && (
                    <>
                      {' '}
                      <span className="palette__meta">{item.meta}</span>
                    </>
                  )}
                </div>
              ))}
            </div>
          ))}
        </div>
        <div className="palette__status" aria-live="polite">
          {search.isError ? (
            <span role="alert">Search failed. Try again.</span>
          ) : search.isSearching ? (
            'Searching…'
          ) : needsMore ? (
            'Type at least 2 characters to search.'
          ) : noResults ? (
            'No results.'
          ) : search.data?.truncated ? (
            'Showing the first results. Refine your search.'
          ) : null}
        </div>
      </motion.div>
    </motion.div>
  )
}
