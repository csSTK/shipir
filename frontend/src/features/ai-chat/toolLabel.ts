/** `list_deployments` -> `list deployments` */
export function toolLabel(name: string): string {
  return name.replaceAll('_', ' ')
}
