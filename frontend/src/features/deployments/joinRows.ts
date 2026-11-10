import type { DeploymentSummary, ManagedDeployment } from './api.ts'

/** A live Compose project, a Shipir-managed deployment, or both (joined by name or `managedDeploymentId`). */
export interface Row {
  name: string
  live: DeploymentSummary | null
  managed: ManagedDeployment | null
}

export function joinRows(live: DeploymentSummary[], managed: ManagedDeployment[]): Row[] {
  const byId = new Map(managed.map((m) => [m.id, m]))
  const byName = new Map(managed.map((m) => [m.name, m]))
  const used = new Set<string>()
  const rows: Row[] = live.map((s) => {
    const m = (s.managedDeploymentId ? byId.get(s.managedDeploymentId) : undefined) ?? byName.get(s.name) ?? null
    if (m) used.add(m.id)
    return { name: s.name, live: s, managed: m }
  })
  // Managed deployments never deployed have no containers, so the live list does not show them.
  for (const m of managed) if (!used.has(m.id)) rows.push({ name: m.name, live: null, managed: m })
  return rows
}
