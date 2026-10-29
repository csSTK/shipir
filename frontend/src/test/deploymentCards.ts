/**
 * Answers the card endpoints of a managed deployment's detail page for a Vitest `apiFetch` mock, with empty defaults.
 * `path` is the apiFetch path, `deployment` the deployment's own path (e.g. `/hosts/h1/managed-deployments/s1`) and
 * `versions` the versions the test wants listed. Returns `undefined` for any other path: the test answers its own paths.
 * A new card's endpoint goes here, so tests need no edit for it.
 */
export function deploymentCardAnswer(path: string, deployment: string, versions: unknown[] = []): { body: unknown } | undefined {
  if (path === `${deployment}/services`) return { body: { services: ['web'], profiles: [] } }
  if (path === `${deployment}/uptime-checks`) return { body: [] }
  if (path === `${deployment}/webhook`) return { body: { enabled: false, createdAt: null, rotatedAt: null } }
  if (path === `${deployment}/versions`) return { body: versions }
  if (path.startsWith(`${deployment}/files`)) return { body: [] }
  return undefined
}
