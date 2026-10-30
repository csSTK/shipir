import { useCallback, useSyncExternalStore } from 'react'
import { useCurrentUser } from '../auth/index.ts'

const PREFIX = 'shipir.domains.advanced.'
const listeners = new Set<() => void>()
/** Fallback when the browser storage is blocked: the choice then lasts until the page is reloaded. */
const memory = new Map<string, boolean>()

function read(key: string): boolean {
  try {
    const stored = localStorage.getItem(key)
    return stored === '1'
  } catch {
    // storage blocked: use the in-memory value
    return memory.get(key) ?? false
  }
}

function write(key: string, on: boolean) {
  memory.set(key, on)
  try {
    localStorage.setItem(key, on ? '1' : '0')
  } catch {
    // storage blocked: the in-memory value is enough for this session
  }
  listeners.forEach((l) => l())
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/**
 * The "Advanced mode" switch of the Domains page, remembered per user in the browser (a per-viewer convenience, not
 * data). Shared between the page and the dialogs, so changing it needs no props. Never throws without storage. The third value says whether the user is known (otherwise nothing is read or stored).
 */
export function useAdvancedMode(): [boolean, (on: boolean) => void, boolean] {
  const { data: user } = useCurrentUser()
  const username = user?.username ?? null
  const key = username === null ? null : PREFIX + username
  const on = useSyncExternalStore(subscribe, () => (key === null ? false : read(key)), () => false)
  // Until the user is known there is no key to store under: the setter does nothing and `ready` is false.
  const set = useCallback((value: boolean) => (key === null ? undefined : write(key, value)), [key])
  return [on, set, key !== null]
}
