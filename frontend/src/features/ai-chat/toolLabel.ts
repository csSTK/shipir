/** `list_stacks` -> `list stacks` */
export function toolLabel(name: string): string {
  return name.replaceAll('_', ' ')
}
