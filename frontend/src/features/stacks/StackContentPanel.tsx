import type { ManagedStackDetail } from './api.ts'

/**
 * Seam for shipr-e42.14: shows the stack's Compose file and variable names read-only.
 * The Monaco YAML editor and the env variable editor replace this component.
 */
export function StackContentPanel({ stack }: { stack: ManagedStackDetail }) {
  return (
    <div className="sd-panel">
      <h3 className="sd-panel__title">Compose file</h3>
      <pre className="sd-yaml" tabIndex={0} aria-label="Compose file">
        {stack.yaml}
      </pre>
      <h3 className="sd-panel__title">Environment variables</h3>
      {stack.envKeys.length === 0 ? (
        <p className="sd-muted">No variables.</p>
      ) : (
        <ul className="sd-keys" aria-label="Environment variable names">
          {stack.envKeys.map((k) => (
            <li key={k}>
              <code>{k}</code>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
