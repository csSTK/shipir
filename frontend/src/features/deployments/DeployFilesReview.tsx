import { useTranslation } from 'react-i18next'
import { useConfigTree } from './config-files/api.ts'
import './DeployFilesReview.css'

/** The config tree stops at this many entries (ConfigFileService.MAX_TREE_ENTRIES). */
const TREE_LIMIT = 5000

/**
 * Lists the files in the deployment directory (include / extends / env_file targets and other extras; compose.yaml, .env
 * and Shipir's own paths are not part of the tree). An ADMIN's deploy approves whatever is on disk, so they see it first.
 */
export function DeployFilesReview({ hostId, deploymentId }: { hostId: string; deploymentId: string }) {
  const { t } = useTranslation('deployments')
  const tree = useConfigTree(hostId, deploymentId)

  if (tree.isPending) {
    return (
      <p role="status" className="dfr-note">
        {t('DeployFilesReview.loadingFiles')}
      </p>
    )
  }
  if (tree.error) {
    return (
      <p role="alert" className="dfr-note dfr-note--error">
        {t('DeployFilesReview.couldNotLoadTheFiles')}
      </p>
    )
  }
  const files = tree.data.filter((entry) => !entry.directory)
  return (
    <section className="dfr" aria-label={t('DeployFilesReview.extraFiles')}>
      <h3 className="dfr-heading">{t('DeployFilesReview.extraFiles')}</h3>
      <p className="dfr-note">{t('DeployFilesReview.deployingApprovesTheseFilesAs')}</p>
      <p className="dfr-note">{t('DeployFilesReview.symbolicLinksAreApprovedToo')}</p>
      {tree.data.length >= TREE_LIMIT && (
        <p role="alert" className="dfr-note dfr-note--error">
          {t('DeployFilesReview.theListIsCutOff')}
        </p>
      )}
      {files.length === 0 ? (
        <p className="dfr-note">{t('DeployFilesReview.noExtraFiles')}</p>
      ) : (
        <ul className="dfr-list">
          {files.map((file) => (
            <li key={file.path}>{file.path}</li>
          ))}
        </ul>
      )}
    </section>
  )
}
