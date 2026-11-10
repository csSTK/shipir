import * as RadixTabs from '@radix-ui/react-tabs'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import './Tabs.css'

export interface TabItem {
  value: string
  label: string
  content: ReactNode
}

/** Tracks whether a horizontally scrolling element has more content hidden before and after the visible part. */
function useScrollEdges<T extends HTMLElement>() {
  const ref = useRef<T>(null)
  const [edges, setEdges] = useState({ start: false, end: false })
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const update = () => {
      const start = el.scrollLeft > 1
      const end = el.scrollLeft + el.clientWidth < el.scrollWidth - 1
      setEdges((prev) => (prev.start === start && prev.end === end ? prev : { start, end }))
    }
    update()
    el.addEventListener('scroll', update, { passive: true })
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(update)
    observer?.observe(el)
    return () => {
      el.removeEventListener('scroll', update)
      observer?.disconnect()
    }
  }, [])
  return { ref, edges }
}

/** Tabs with roving focus (arrow keys), automatic activation. A row wider than its box scrolls and fades out at the edge with more tabs. */
export function Tabs({ items, label, value, defaultValue, onValueChange }: { items: TabItem[]; label: string; value?: string; defaultValue?: string; onValueChange?: (v: string) => void }) {
  const { ref, edges } = useScrollEdges<HTMLDivElement>()
  return (
    <RadixTabs.Root className="ui-tabs" value={value} defaultValue={value === undefined ? (defaultValue ?? items[0]?.value) : undefined} onValueChange={onValueChange}>
      <RadixTabs.List ref={ref} className="ui-tabs__list" aria-label={label} data-fade-start={edges.start || undefined} data-fade-end={edges.end || undefined}>
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
