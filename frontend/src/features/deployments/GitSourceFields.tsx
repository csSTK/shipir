import { useGitCredentials } from '../settings/api.ts'

export interface GitFormValue {
  repoUrl: string
  ref: string
  composePath: string
  credentialId: string
}

/** Credential list is ADMIN-only on the server, so other roles get a hint instead of a failing request. */
function CredentialSelect({ value, onChange }: { value: string; onChange: (id: string) => void }) {
  const credentials = useGitCredentials()
  return (
    <label className="sk-field">
      <span>Credential</span>
      <select value={value} onChange={(e) => onChange(e.target.value)} disabled={credentials.isPending}>
        <option value="">None (public repository)</option>
        {credentials.data?.map((c) => (
          <option key={c.id} value={c.id}>
            {c.name}
          </option>
        ))}
      </select>
      {credentials.isError && <small className="sk-hint">Could not load the credentials. Public repositories still work.</small>}
      {credentials.data?.length === 0 && <small className="sk-hint">No credentials yet. Add one under Settings, Git credentials.</small>}
    </label>
  )
}

/** Repository fields of the "Git repository" source in the add dialog. */
export function GitSourceFields({
  value,
  onChange,
  isAdmin,
}: {
  value: GitFormValue
  onChange: (next: GitFormValue) => void
  isAdmin: boolean
}) {
  const set = (patch: Partial<GitFormValue>) => onChange({ ...value, ...patch })
  return (
    <div className="sk-form">
      <label className="sk-field">
        <span>Repository URL</span>
        <input value={value.repoUrl} onChange={(e) => set({ repoUrl: e.target.value })} placeholder="https://github.com/owner/repo.git" autoComplete="off" spellCheck={false} inputMode="url" />
      </label>
      <div className="sk-row">
        <label className="sk-field">
          <span>Branch or tag</span>
          <input value={value.ref} onChange={(e) => set({ ref: e.target.value })} autoComplete="off" spellCheck={false} />
        </label>
        <label className="sk-field">
          <span>File path in repository</span>
          <input value={value.composePath} onChange={(e) => set({ composePath: e.target.value })} autoComplete="off" spellCheck={false} />
        </label>
      </div>
      {isAdmin ? (
        <CredentialSelect value={value.credentialId} onChange={(credentialId) => set({ credentialId })} />
      ) : (
        <p className="sk-hint">Only administrators can pick a credential. Public repositories work without one.</p>
      )}
    </div>
  )
}
