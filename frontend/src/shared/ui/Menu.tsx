import * as DropdownMenu from '@radix-ui/react-dropdown-menu'
import { AnimatePresence, motion } from 'motion/react'
import { useState, type ReactElement, type ReactNode } from 'react'
import { springSnappy, usePopMotion } from './motion.ts'
import './Menu.css'

export type MenuEntry =
  | { type?: 'item'; label: string; onSelect: () => void; icon?: ReactNode; danger?: boolean; disabled?: boolean }
  | { type: 'separator' }

/**
 * Dropdown menu: Radix handles keyboard (arrows, typeahead, Esc, focus return to the trigger),
 * motion springs it open from the trigger side. `trigger` is one focusable element.
 */
export function Menu({ trigger, items, align = 'end' }: { trigger: ReactElement; items: MenuEntry[]; align?: 'start' | 'center' | 'end' }) {
  const [open, setOpen] = useState(false)
  const pop = usePopMotion(springSnappy, 0.92)
  return (
    <DropdownMenu.Root open={open} onOpenChange={setOpen}>
      <DropdownMenu.Trigger asChild>{trigger}</DropdownMenu.Trigger>
      <AnimatePresence>
        {open && (
          <DropdownMenu.Portal forceMount>
            <DropdownMenu.Content asChild forceMount align={align} sideOffset={6} collisionPadding={8}>
              <motion.div className="ui-menu" style={{ transformOrigin: 'var(--radix-dropdown-menu-content-transform-origin)' }} {...pop}>
                {items.map((entry, i) =>
                  entry.type === 'separator' ? (
                    <DropdownMenu.Separator key={`sep-${i}`} className="ui-menu__sep" />
                  ) : (
                    <DropdownMenu.Item
                      key={entry.label}
                      className={`ui-menu__item${entry.danger ? ' ui-menu__item--danger' : ''}`}
                      disabled={entry.disabled}
                      onSelect={entry.onSelect}
                    >
                      {entry.icon && <span className="ui-menu__icon" aria-hidden="true">{entry.icon}</span>}
                      {entry.label}
                    </DropdownMenu.Item>
                  ),
                )}
              </motion.div>
            </DropdownMenu.Content>
          </DropdownMenu.Portal>
        )}
      </AnimatePresence>
    </DropdownMenu.Root>
  )
}
