import { Trash2 } from 'lucide-react'
import type { MenuEntry } from '../../shared/ui/index.ts'

/** Overflow-menu entry "Delete" for `ResourceTable` `rowActions` (ADMIN/OPERATOR pages only). */
export function deleteMenu(onRequest: () => void): MenuEntry[] {
  return [{ label: 'Delete', icon: <Trash2 size={16} strokeWidth={1.75} />, danger: true, onSelect: onRequest }]
}
