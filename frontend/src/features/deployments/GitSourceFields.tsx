import { useGitCredentials } from '../settings/api.ts'
import { useTranslation } from 'react-i18next'

export interface GitFormValue {
  repoUrl: string
  ref: string
  composePath: string
  credentialId: string
}

/** Credential list is ADMIN-only on the server, so other roles get a hint instead of a failing request. */
function CredentialSelect({ value, onChange }: { value: string; onChange: (id: string) => void }) {
  const { t } = useTranslation('deployments')
  const credentials = useGitCredentials()
  return (
    <label className="ui-form-field">
      <span>{t('GitSourceFields.credential')}</span>
      <select value={value} onChange={(e) => onChange(e.target.value)} disabled={credentials.isPending}>
        <option value="">{t('GitSourceFields.nonePublicRepository')}</option>
        {credentials.data?.map((c) => (
          <option key={c.id} value={c.id}>
            {c.name}
          </option>
        ))}
      </select>
      {credentials.isError && <small className="ui-hint">{t('GitSourceFields.couldNotLoadTheCredentials')}</small>}
      {credentials.data?.length === 0 && <small className="ui-hint">{t('GitSourceFields.noCredentialsYetAddOne')}</small>}
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
  const { t } = useTranslation('deployments')
  const set = (patch: Partial<GitFormValue>) => onChange({ ...value, ...patch })
  return (
    <div className="ui-form">
      <label className="ui-form-field">
        <span>{t('GitSourceFields.repositoryUrl')}</span>
        <input value={value.repoUrl} onChange={(e) => set({ repoUrl: e.target.value })} placeholder="https://github.com/owner/repo.git" autoComplete="off" spellCheck={false} inputMode="url" />
      </label>
      <div className="ui-form-row">
        <label className="ui-form-field">
          <span>{t('GitSourceFields.branchOrTag')}</span>
          <input value={value.ref} onChange={(e) => set({ ref: e.target.value })} autoComplete="off" spellCheck={false} />
        </label>
        <label className="ui-form-field">
          <span>{t('GitSourceFields.filePathInRepository')}</span>
          <input value={value.composePath} onChange={(e) => set({ composePath: e.target.value })} autoComplete="off" spellCheck={false} />
        </label>
      </div>
      {isAdmin ? (
        <CredentialSelect value={value.credentialId} onChange={(credentialId) => set({ credentialId })} />
      ) : (
        <p className="ui-hint">{t('GitSourceFields.onlyAdministratorsCanPickA')}</p>
      )}
    </div>
  )
}
