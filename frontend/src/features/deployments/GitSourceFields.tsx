import { Select, TextField } from '../../shared/ui/index.ts'
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
  const credentialHelp = credentials.isError
    ? t('GitSourceFields.couldNotLoadTheCredentials')
    : credentials.data?.length === 0
      ? t('GitSourceFields.noCredentialsYetAddOne')
      : undefined
  return (
    <Select
      label={t('GitSourceFields.credential')}
      help={credentialHelp}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      disabled={credentials.isPending}
    >
        <option value="">{t('GitSourceFields.nonePublicRepository')}</option>
        {credentials.data?.map((c) => (
          <option key={c.id} value={c.id}>
            {c.name}
          </option>
        ))}
    </Select>
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
      <TextField
        label={t('GitSourceFields.repositoryUrl')}
        value={value.repoUrl} onChange={(e) => set({ repoUrl: e.target.value })} placeholder="https://github.com/owner/repo.git" autoComplete="off" spellCheck={false} inputMode="url" />
      <div className="ui-form-row">
        <TextField label={t('GitSourceFields.branchOrTag')} value={value.ref} onChange={(e) => set({ ref: e.target.value })} autoComplete="off" spellCheck={false} />
        <TextField label={t('GitSourceFields.filePathInRepository')} value={value.composePath} onChange={(e) => set({ composePath: e.target.value })} autoComplete="off" spellCheck={false} />
      </div>
      {isAdmin ? (
        <CredentialSelect value={value.credentialId} onChange={(credentialId) => set({ credentialId })} />
      ) : (
        <p className="ui-hint">{t('GitSourceFields.onlyAdministratorsCanPickA')}</p>
      )}
    </div>
  )
}
