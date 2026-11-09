import * as RadixDialog from '@radix-ui/react-dialog'
import { AnimatePresence, motion } from 'motion/react'
import { useLayoutEffect, useRef, type ReactNode } from 'react'
import { X } from 'lucide-react'
import { Button } from './Button.tsx'
import { springSmooth, useFadeMotion, usePopMotion } from './motion.ts'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'
import './Dialog.css'

interface DialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: string
  children?: ReactNode
  /** Action buttons, right-aligned. */
  footer?: ReactNode
  /** Width: `md` for confirmations (default), `lg` for forms, `xl` for diffs and editors. */
  size?: 'md' | 'lg' | 'xl'
  /** `false` while the dialog must not be left (e.g. a request is running): the X looks and acts disabled, Esc and the scrim do nothing. */
  dismissible?: boolean
}

/** Modal dialog: scrim, focus trapped and restored, Esc/scrim close, spring (fade under reduced motion). */
export function Dialog({ open, onOpenChange, title, description, children, footer, size = 'md', dismissible = true }: DialogProps) {
  const { t } = useTranslation('common')
  const pop = usePopMotion(springSmooth, 0.96)
  const fade = useFadeMotion()
  // Controlled dialogs have no Radix Trigger, so remember the opener to restore focus on close.
  const opener = useRef<HTMLElement | null>(null)
  useLayoutEffect(() => {
    if (open) opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
  }, [open])
  return (
    <RadixDialog.Root open={open} onOpenChange={(next) => (dismissible || next) && onOpenChange(next)}>
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
                <motion.div className={`ui-dialog ui-dialog--${size}`} {...pop}>
                  <div className="ui-dialog__head">
                    <RadixDialog.Title className="ui-dialog__title">{title}</RadixDialog.Title>
                    <RadixDialog.Close className="ui-dialog__close" aria-label={t('Dialog.close')} disabled={!dismissible}>
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
  /** Inline error shown below the content (role=alert). */
  error?: string | null
  /** Rendered below the text (e.g. a diff). */
  details?: ReactNode
  size?: 'md' | 'lg' | 'xl'
}

/** Confirmation dialog; `children` is the message, `details` extra content, `error` an inline failure. */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel = i18n.t('common:Dialog.confirm'),
  cancelLabel = i18n.t('common:Dialog.cancel'),
  danger = false,
  loading = false,
  onConfirm,
  children,
  error = null,
  details,
  size,
}: ConfirmDialogProps) {
  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title={title}
      description={description}
      size={size}
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
      {children ? <div className="ui-dialog-text">{children}</div> : null}
      {details}
      {error && (
        <p role="alert" className="ui-error">
          {error}
        </p>
      )}
    </Dialog>
  )
}
