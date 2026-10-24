import { DeployOutput } from './deploy-output/DeployOutput.tsx'
import type { RunResult } from './deploy-output/useRunStream.ts'

export interface StartedRun {
  kind: 'Deploy' | 'Rollback' | 'Pull & redeploy' | 'Pull & update'
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
  const title = run.kind === 'Rollback' ? 'Rollback' : 'Deployment'
  return <DeployOutput title={title} runId={run.runId} onFinished={onFinished} />
}
