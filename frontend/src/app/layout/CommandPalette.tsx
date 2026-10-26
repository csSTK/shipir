import { ArrowRight, Box, Layers, Search, Server, type LucideIcon } from 'lucide-react'
import { motion } from 'motion/react'
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import type { CurrentUser } from '../../features/auth/index.ts'
import { MIN_QUERY_LENGTH, routeFor, useSearch, type SearchResult } from './useSearch.ts'
import { springSmooth, usePopMotion } from '../../shared/ui/motion.ts'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'
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
  { get label() { return i18n.t('app:CommandPalette.environments') }, to: '/' },
  { get label() { return i18n.t('app:CommandPalette.assistant') }, to: '/assistant' },
  { get label() { return i18n.t('app:CommandPalette.notifications') }, to: '/notifications' },
  { get label() { return i18n.t('app:CommandPalette.account') }, to: '/account' },
  { get label() { return i18n.t('app:CommandPalette.users') }, to: '/settings/users', admin: true },
  { get label() { return i18n.t('app:CommandPalette.teams') }, to: '/settings/teams', admin: true },
  { get label() { return i18n.t('app:CommandPalette.notificationWebhooks') }, to: '/settings/notifications', admin: true },
  { get label() { return i18n.t('app:CommandPalette.retention') }, to: '/settings/retention', admin: true },
  { get label() { return i18n.t('app:CommandPalette.gitCredentials') }, to: '/settings/git', admin: true },
  { get label() { return i18n.t('app:CommandPalette.registries') }, to: '/settings/registries', admin: true },
  { get label() { return i18n.t('app:CommandPalette.aiAssistantSettings') }, to: '/settings/ai', admin: true },
  { get label() { return i18n.t('app:CommandPalette.sharedChats') }, to: '/settings/ai/shared', admin: true },
  { get label() { return i18n.t('app:CommandPalette.auditLog') }, to: '/settings/audit-log', admin: true },
]

const TYPE_GROUPS: { type: SearchResult['type']; label: string; icon: LucideIcon }[] = [
  { type: 'HOST', get label() { return i18n.t('app:CommandPalette.environments') }, icon: Server },
  { type: 'DEPLOYMENT', get label() { return i18n.t('app:CommandPalette.deployments') }, icon: Layers },
  { type: 'CONTAINER', get label() { return i18n.t('app:CommandPalette.containers') }, icon: Box },
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
  const { t } = useTranslation('app')
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
      out.push({ label: t('CommandPalette.goTo'), items: commands.map((c) => ({ icon: ArrowRight, key: `nav-${c.to}`, label: c.label, to: c.to })) })
    }
    const results = search.data?.results ?? []
    for (const { type, label, icon } of TYPE_GROUPS) {
      const items = results.map((r, i) => toItem(r, i, icon)).filter((_, i) => results[i].type === type)
      if (items.length) out.push({ label, items })
    }
    return out
  }, [query, role, search.data, t])

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
      <motion.div className="palette" role="dialog" aria-modal="true" aria-label={t('CommandPalette.commandPalette')} onKeyDown={onKeyDown} initial={pop.initial} animate={pop.animate} transition={pop.transition}>
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
          aria-label={t('CommandPalette.searchOrJumpTo')}
          placeholder={t('CommandPalette.searchEnvironmentsDeploymentsContainers')}
          autoComplete="off"
          spellCheck={false}
          value={query}
          onChange={(e) => {
            setQuery(e.target.value)
            setActive(0)
          }}
        />
        </div>
        <div id={listId} role="listbox" aria-label={t('CommandPalette.results')} className="palette__list">
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
            <span role="alert">{t('CommandPalette.searchFailedTryAgain')}</span>
          ) : search.isSearching ? (
            t('CommandPalette.searching')
          ) : needsMore ? (
            t('CommandPalette.typeAtLeast2Characters')
          ) : noResults ? (
            t('CommandPalette.noResults')
          ) : search.data?.truncated ? (
            t('CommandPalette.showingTheFirstResultsRefine')
          ) : null}
        </div>
      </motion.div>
    </motion.div>
  )
}
