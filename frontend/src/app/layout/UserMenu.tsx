import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import type { CurrentUser } from '../../features/auth/index.ts'
import './UserMenu.css'

interface Props {
  user: CurrentUser
  onSignOut: () => void
  signingOut?: boolean
}

/** Top bar user button with an accessible menu (Settings, Sign out). */
export function UserMenu({ user, onSignOut, signingOut = false }: Props) {
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()
  const root = useRef<HTMLDivElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const items = useRef<(HTMLButtonElement | null)[]>([])

  const close = (returnFocus: boolean) => {
    setOpen(false)
    if (returnFocus) trigger.current?.focus()
  }

  useEffect(() => {
    if (open) items.current[0]?.focus()
  }, [open])

  useEffect(() => {
    if (!open) return
    const onPointerDown = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('pointerdown', onPointerDown)
    return () => document.removeEventListener('pointerdown', onPointerDown)
  }, [open])

  const focusItem = (index: number) => {
    const list = items.current.filter(Boolean) as HTMLButtonElement[]
    list[(index + list.length) % list.length]?.focus()
  }

  const onMenuKeyDown = (event: KeyboardEvent) => {
    const list = items.current.filter(Boolean) as HTMLButtonElement[]
    const current = list.indexOf(document.activeElement as HTMLButtonElement)
    switch (event.key) {
      case 'Escape':
        event.preventDefault()
        close(true)
        break
      case 'ArrowDown':
        event.preventDefault()
        focusItem(current + 1)
        break
      case 'ArrowUp':
        event.preventDefault()
        focusItem(current - 1)
        break
      case 'Home':
        event.preventDefault()
        focusItem(0)
        break
      case 'End':
        event.preventDefault()
        focusItem(list.length - 1)
        break
      case 'Tab':
        setOpen(false)
        break
    }
  }

  const onTriggerKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'ArrowDown' && !open) {
      event.preventDefault()
      setOpen(true)
    }
  }

  return (
    <div className="user-menu" ref={root}>
      <button
        ref={trigger}
        type="button"
        className="user-menu__trigger"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls="user-menu-list"
        onClick={() => setOpen((value) => !value)}
        onKeyDown={onTriggerKeyDown}
      >
        <span className="user-menu__name">{user.username}</span>
        <span className="user-menu__role">{user.role}</span>
      </button>
      {open && (
        <div
          id="user-menu-list"
          className="user-menu__list"
          role="menu"
          aria-label="User menu"
          onKeyDown={onMenuKeyDown}
        >
          <button
            ref={(el) => {
              items.current[0] = el
            }}
            type="button"
            role="menuitem"
            className="user-menu__item"
            onClick={() => {
              close(false)
              navigate('/account')
            }}
          >
            Settings
          </button>
          <button
            ref={(el) => {
              items.current[1] = el
            }}
            type="button"
            role="menuitem"
            className="user-menu__item"
            disabled={signingOut}
            onClick={() => {
              close(false)
              onSignOut()
            }}
          >
            Sign out
          </button>
        </div>
      )}
    </div>
  )
}
