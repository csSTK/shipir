import * as RadixTabs from '@radix-ui/react-tabs'
import type { ReactNode } from 'react'
import './Tabs.css'

export interface TabItem {
  value: string
  label: string
  content: ReactNode
}

/** Tabs with roving focus (arrow keys), automatic activation. */
export function Tabs({ items, label, value, defaultValue, onValueChange }: { items: TabItem[]; label: string; value?: string; defaultValue?: string; onValueChange?: (v: string) => void }) {
  return (
    <RadixTabs.Root className="ui-tabs" value={value} defaultValue={value === undefined ? (defaultValue ?? items[0]?.value) : undefined} onValueChange={onValueChange}>
      <RadixTabs.List className="ui-tabs__list" aria-label={label}>
        {items.map((t) => (
          <RadixTabs.Trigger key={t.value} value={t.value} className="ui-tabs__tab">
            {t.label}
          </RadixTabs.Trigger>
        ))}
      </RadixTabs.List>
      {items.map((t) => (
        <RadixTabs.Content key={t.value} value={t.value} className="ui-tabs__panel">
          {t.content}
        </RadixTabs.Content>
      ))}
    </RadixTabs.Root>
  )
}
