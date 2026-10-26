import { ChevronDown } from 'lucide-react'
import { useMatch, useNavigate } from 'react-router-dom'
import { useHostsOverview } from '../../features/hosts/index.ts'
import type { HostOverview } from '../../features/hosts/index.ts'
import { Menu, StatusDot, type MenuEntry } from '../../shared/ui/index.ts'
import './EnvironmentSwitcher.css'

/** Top bar environment picker (environment routes only) with a status dot; keeps the current resource page when switching. */
export function EnvironmentSwitcher() {
  const match = useMatch('/environments/:id/*')
  // Mounted only on environment routes so other pages do not fetch the overview just for the bar.
  return match ? <Switcher currentId={match.params.id} rest={match.params['*'] ?? ''} /> : null
}

function Switcher({ currentId, rest }: { currentId?: string; rest: string }) {
  const { data: envs } = useHostsOverview()
  const navigate = useNavigate()
  if (!envs || envs.length === 0) return null
  const current = envs.find((e) => e.id === currentId)
  return (
    <Menu
      align="start"
      trigger={
        <button type="button" className="env-switcher" aria-label={`Environment: ${current?.name ?? 'none selected'}`}>
          {current && <StatusDot tone={current.status === 'UP' ? 'ok' : current.status === 'DOWN' ? 'bad' : 'muted'} />}
          <span className="env-switcher__name">{current?.name ?? 'Choose environment'}</span>
          <ChevronDown size={16} strokeWidth={1.75} aria-hidden="true" />
        </button>
      }
      items={groupByTag(envs).flatMap((group, i): MenuEntry[] => [
        ...(i > 0 ? [{ type: 'separator' as const }] : []),
        ...group.map((env) => ({
          label: env.name,
          icon: <StatusDot tone={env.status === 'UP' ? 'ok' : env.status === 'DOWN' ? 'bad' : 'muted'} />,
          onSelect: () => navigate(`/environments/${env.id}${rest ? `/${rest}` : ''}`),
        })),
      ])}
    />
  )
}

/**
 * Groups environments by their first tag (alphabetical), untagged ones last. Each environment
 * sits in exactly one group, so a multi-tag environment is listed under its first tag only.
 */
function groupByTag(envs: HostOverview[]): HostOverview[][] {
  const groups = new Map<string | null, HostOverview[]>()
  for (const env of envs) {
    // Overview rows from an older API may lack `tags`; treat them as untagged.
    const key = env.tags?.[0] ?? null
    groups.set(key, [...(groups.get(key) ?? []), env])
  }
  const keys = [...groups.keys()].sort((a, b) => {
    if (a === b) return 0
    if (a === null) return 1
    if (b === null) return -1
    return a.localeCompare(b)
  })
  return keys.map((key) => groups.get(key) ?? [])
}
