import { DeployOutput } from './deploy-output/DeployOutput.tsx'
import type { DeploymentResult } from './deploy-output/useDeploymentStream.ts'

export interface StartedDeployment {
  kind: 'Deploy' | 'Rollback' | 'Pull & redeploy'
  deploymentId: string
}

/** Live output of a started deploy or rollback (STOMP `/topic/deployments/{id}`). */
export function DeploymentPanel({
  deployment,
  onFinished,
}: {
  deployment: StartedDeployment
  onFinished?: (result: DeploymentResult) => void
}) {
  const title = deployment.kind === 'Rollback' ? 'Rollback' : 'Deployment'
  return <DeployOutput title={title} deploymentId={deployment.deploymentId} onFinished={onFinished} />
}
