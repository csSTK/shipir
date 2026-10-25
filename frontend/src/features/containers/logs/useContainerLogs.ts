import { useLogStream, type TailOption } from './logStream.ts'

export { MAX_LOG_LINES } from './logStream.ts'
export type { LogEnd, LogLine, TailOption } from './logStream.ts'

/** Live log of one container: options as in {@link useLogStream}; a STATUS frame ends the stream. */
export function useContainerLogs(hostId: string, containerId: string, tail: TailOption, timestamps: boolean) {
  return useLogStream(`/topic/hosts/${hostId}/containers/${containerId}/logs`, tail, timestamps, false)
}
