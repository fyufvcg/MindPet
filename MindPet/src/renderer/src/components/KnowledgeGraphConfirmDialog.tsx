import React from 'react'
import { AlertTriangle, LoaderCircle } from 'lucide-react'

interface Props {
  title: string
  description: string
  errorMessage?: string
  confirmLabel: string
  intent?: 'default' | 'danger'
  onConfirm: () => Promise<boolean | void>
  onCancel: () => void
}

export function KnowledgeGraphConfirmDialog({
  title,
  description,
  errorMessage,
  confirmLabel,
  intent = 'default',
  onConfirm,
  onCancel
}: Props): React.JSX.Element {
  const dialogRef = React.useRef<HTMLDialogElement>(null)
  const [submitting, setSubmitting] = React.useState(false)
  const [failed, setFailed] = React.useState(false)
  const titleId = React.useId()
  const descriptionId = React.useId()
  const busy = submitting

  React.useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    if (!dialog.open) dialog.showModal()
    return () => {
      if (dialog.open) dialog.close()
    }
  }, [])

  const confirm = async (): Promise<void> => {
    if (busy) return
    setFailed(false)
    setSubmitting(true)
    try {
      const result = await onConfirm()
      if (result !== false) onCancel()
      else setFailed(true)
    } catch {
      setFailed(true)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <dialog
      ref={dialogRef}
      className="kg-confirm-dialog"
      role="alertdialog"
      aria-modal="true"
      aria-labelledby={titleId}
      aria-describedby={descriptionId}
      onCancel={(event) => {
        event.preventDefault()
        if (!busy) onCancel()
      }}
    >
      <div className={'kg-confirm-dialog__icon ' + (intent === 'danger' ? 'is-danger' : '')}>
        <AlertTriangle size={19} aria-hidden="true" />
      </div>
      <h2 id={titleId}>{title}</h2>
      <p id={descriptionId}>{description}</p>
      {(errorMessage || failed) && (
        <p className="kg-confirm-dialog__error" role="alert">
          {errorMessage || '操作未完成，请检查提示后重试。'}
        </p>
      )}
      <div className="kg-confirm-dialog__actions">
        <button
          type="button"
          className="kg-confirm-dialog__cancel"
          onClick={onCancel}
          disabled={busy}
          autoFocus
        >
          取消
        </button>
        <button
          type="button"
          className={
            'kg-confirm-dialog__confirm ' + (intent === 'danger' ? 'is-danger' : '')
          }
          onClick={() => void confirm()}
          disabled={busy}
        >
          {busy && <LoaderCircle size={14} className="kg-spin" aria-hidden="true" />}
          {busy ? '处理中' : confirmLabel}
        </button>
      </div>
    </dialog>
  )
}
