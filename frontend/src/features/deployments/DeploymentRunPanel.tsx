import { DeployOutput } from './deploy-output/DeployOutput.tsx'
import type { RunResult } from './deploy-output/useRunStream.ts'
import { useTranslation } from 'react-i18next'

export interface StartedRun {
  /** Machine key of the action ('deploy', 'rollback', ...) or a free label such as 'Restart web' from the services card. */
  kind: string
  runId: string
}

/** Live output of a started deploy or rollback (STOMP `/topic/deployments/{id}`). */
export function DeploymentRunPanel({
  run,
  onFinished,
}: {
  run: StartedRun
  onFinished?: (result: RunResult) => void
}) {
  const { t } = useTranslation('deployments')
  const title = run.kind === 'rollback' ? t('DeploymentRunPanel.rollback') : t('DeploymentRunPanel.deployment')
  return <DeployOutput title={title} runId={run.runId} onFinished={onFinished} />
}
