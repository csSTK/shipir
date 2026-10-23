export interface StartedDeployment {
  kind: 'Deploy' | 'Rollback'
  deploymentId: string
}

/**
 * Seam for shipr-e42.16: the live output panel replaces the body of this component
 * and subscribes to `/topic/deployments/{deploymentId}`. For now it only confirms the start.
 */
export function DeploymentPanel({ deployment }: { deployment: StartedDeployment }) {
  return (
    <div className="sd-deploy" role="status">
      <strong>{deployment.kind === 'Deploy' ? 'Deployment started' : 'Rollback started'}</strong>
      <span className="sd-muted">
        Deployment ID <code>{deployment.deploymentId}</code>
      </span>
    </div>
  )
}
