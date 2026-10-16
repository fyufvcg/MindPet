import { useEffect, useState } from 'react'
import { Eye, EyeOff, KeyRound, LoaderCircle, ShieldCheck, Trash2 } from 'lucide-react'

interface Props {
  id: string
  label: string
  value: string
  hasSavedKey: boolean
  busy: boolean
  onChange: (value: string) => void
  onSave: () => void | Promise<void>
  onClear?: () => void | Promise<void>
  saveLabel?: string
  allowEmptySave?: boolean
  hint: string
}

/** Shows a new credential draft; saved credentials never enter this component. */
export function SecretKeyField({ id, label, value, hasSavedKey, busy, onChange,
  onSave, onClear, saveLabel = '安全保存', allowEmptySave = false, hint }: Props): React.JSX.Element {
  const [visible, setVisible] = useState(false)
  useEffect(() => { if (!value) setVisible(false) }, [value])
  return (
    <div className="secret-key-field" onBlur={event => {
      if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setVisible(false)
    }}>
      <div className="secret-key-heading">
        <label htmlFor={id} className="form-label">{label}</label>
        <span className={`secret-key-state${hasSavedKey ? ' is-saved' : ''}`} role="status">
          {hasSavedKey ? <ShieldCheck size={14} aria-hidden="true" /> : <KeyRound size={14} aria-hidden="true" />}
          {value ? '新密钥待保存' : hasSavedKey ? '已安全保存' : '尚未配置'}
        </span>
      </div>
      <div className="secret-key-controls">
        <div className="secret-key-input-wrap">
          <KeyRound className="secret-key-leading" size={16} aria-hidden="true" />
          <input id={id} className="form-input secret-key-input" type={visible ? 'text' : 'password'}
            value={value} placeholder={hasSavedKey ? '输入新密钥以替换，留空保留原密钥' : '粘贴服务商提供的 API Key'}
            autoComplete="new-password" autoCapitalize="none" spellCheck={false} disabled={busy}
            aria-describedby={`${id}-hint`} onChange={event => onChange(event.target.value)}
            onKeyDown={event => {
              if (event.key === 'Enter' && !busy && (allowEmptySave || value.trim())) {
                event.preventDefault(); void onSave()
              }
            }} />
          <button className="secret-key-reveal" type="button" disabled={!value || busy}
            onClick={() => setVisible(!visible)} aria-pressed={visible}
            aria-label={visible ? '隐藏正在输入的密钥' : '显示正在输入的密钥'}
            title={visible ? '隐藏正在输入的密钥' : '显示正在输入的密钥'}>
            {visible ? <EyeOff size={17} aria-hidden="true" /> : <Eye size={17} aria-hidden="true" />}
          </button>
        </div>
        <div className="secret-key-actions">
          <button className="btn-primary" type="button" disabled={busy || (!allowEmptySave && !value.trim())}
            onClick={() => void onSave()}>
            {busy && <LoaderCircle size={15} className="secret-key-spinner" aria-hidden="true" />}
            {busy ? '处理中…' : saveLabel}
          </button>
          {hasSavedKey && onClear && <button className="btn-secondary secret-key-clear" type="button"
            disabled={busy} onClick={() => void onClear()} aria-label={`清除${label}`}>
            <Trash2 size={15} aria-hidden="true" /><span>清除</span>
          </button>}
        </div>
      </div>
      <div id={`${id}-hint`} className="secret-key-hint">{hint}</div>
    </div>
  )
}
