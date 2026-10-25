import { useLogStream, type TailOption } from './logStream.ts'

/** Tail choices the combined stream accepts (per container). */
export type ProjectTailOption = Exclude<TailOption, '200'>

/** Live combined log of all containers of a Compose project (`project` = deployment name). */
export function useDeploymentLogs(hostId: string, project: string, tail: ProjectTailOption, timestamps: boolean) {
  return useLogStream(`/topic/hosts/${hostId}/deployments/${project}/logs`, tail, timestamps, true)
}
