import * as RadixDialog from '@radix-ui/react-dialog'
import { AnimatePresence, motion } from 'motion/react'
import { useLayoutEffect, useRef, type ReactNode } from 'react'
import { X } from 'lucide-react'
import { Button } from './Button.tsx'
import { springSmooth, useFadeMotion, usePopMotion } from './motion.ts'
import './Dialog.css'

interface DialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: string
  children?: ReactNode
  /** Action buttons, right-aligned. */
  footer?: ReactNode
}

/** Modal dialog: scrim, focus trapped and restored, Esc/scrim close, spring (fade under reduced motion). */
export function Dialog({ open, onOpenChange, title, description, children, footer }: DialogProps) {
  const pop = usePopMotion(springSmooth, 0.96)
  const fade = useFadeMotion()
  // Controlled dialogs have no Radix Trigger, so remember the opener to restore focus on close.
  const opener = useRef<HTMLElement | null>(null)
  useLayoutEffect(() => {
    if (open) opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
  }, [open])
  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <AnimatePresence>
        {open && (
          <RadixDialog.Portal forceMount>
            <RadixDialog.Overlay asChild forceMount>
              <motion.div className="ui-dialog__scrim" {...fade} />
            </RadixDialog.Overlay>
            <div className="ui-dialog__wrap">
              <RadixDialog.Content
                asChild
                forceMount
                onCloseAutoFocus={(e) => {
                  e.preventDefault()
                  opener.current?.focus()
                }}
              >
                <motion.div className="ui-dialog" {...pop}>
                  <div className="ui-dialog__head">
                    <RadixDialog.Title className="ui-dialog__title">{title}</RadixDialog.Title>
                    <RadixDialog.Close className="ui-dialog__close" aria-label="Close">
                      <X size={20} strokeWidth={1.75} aria-hidden="true" />
                    </RadixDialog.Close>
                  </div>
                  {description ? (
                    <RadixDialog.Description className="ui-dialog__desc">{description}</RadixDialog.Description>
                  ) : null}
                  {children && <div className="ui-dialog__body">{children}</div>}
                  {footer && <div className="ui-dialog__footer">{footer}</div>}
                </motion.div>
              </RadixDialog.Content>
            </div>
          </RadixDialog.Portal>
        )}
      </AnimatePresence>
    </RadixDialog.Root>
  )
}

interface ConfirmDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: string
  confirmLabel?: string
  cancelLabel?: string
  danger?: boolean
  loading?: boolean
  onConfirm: () => void
  children?: ReactNode
}

export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  danger = false,
  loading = false,
  onConfirm,
  children,
}: ConfirmDialogProps) {
  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title={title}
      description={description}
      footer={
        <>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            {cancelLabel}
          </Button>
          <Button variant={danger ? 'danger' : 'primary'} loading={loading} onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      {children}
    </Dialog>
  )
}
