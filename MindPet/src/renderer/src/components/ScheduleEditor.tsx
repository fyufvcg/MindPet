import React, { useEffect, useRef, useState } from 'react'
import { X } from 'lucide-react'
import type { CronTask } from '../hooks/useAppStore'

interface Props {
  task: CronTask | null
  servers: Array<{ id: string; name?: string }>
  onSave: (updates: Pick<CronTask, 'name' | 'interval' | 'action' | 'selectedMcpServerIds'>) => Promise<void>
  onClose: () => void
}

/** The editor owns only the draft; runtime status and logs remain in the backend store. */
export function ScheduleEditor({ task, servers, onSave, onClose }: Props): React.JSX.Element {
  const [name, setName] = useState(task?.name || '')
  const [action, setAction] = useState(task?.action || '')
  const [interval, setInterval] = useState(task?.interval || 60)
  const [allowed, setAllowed] = useState((task?.selectedMcpServerIds || []).filter(id => servers.some(server => server.id === id)))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const dialog = useRef<HTMLDialogElement>(null)
  const maintenance = task?.kind === 'memory-prune'
  useEffect(() => {
    dialog.current?.showModal()
    dialog.current?.querySelector<HTMLInputElement>('input:not(:disabled)')?.focus()
    return () => { dialog.current?.close() }
  }, [])
  const save = async (event: React.FormEvent): Promise<void> => {
    event.preventDefault()
    if (busy) return
    setBusy(true); setError('')
    try {
      await onSave({ name: name.trim(), action: action.trim(), interval,
        selectedMcpServerIds: allowed.filter(id => servers.some(server => server.id === id)) })
      onClose()
    } catch (e) { setError(e instanceof Error ? e.message : String(e)) }
    finally { setBusy(false) }
  }
  return <dialog ref={dialog} className="schedule-editor mcp-modal-card" aria-labelledby="schedule-editor-title"
    onCancel={event => { event.preventDefault(); if (!busy) onClose() }}>
    <form onSubmit={event => { void save(event) }}>
      <div className="mcp-modal-header"><h3 id="schedule-editor-title" className="mcp-modal-title">{task ? '编辑定时任务' : '新增定时任务'}</h3>
        <button type="button" className="mcp-modal-close-btn" disabled={busy} onClick={onClose} aria-label="关闭"><X size={18} /></button></div>
      <div className="mcp-modal-body">
        <label className="mcp-form-label" htmlFor="schedule-name">任务名称</label>
        <input id="schedule-name" className="mcp-input-fancy" required maxLength={200} value={name} disabled={busy || maintenance} onChange={e => setName(e.target.value)} autoFocus />
        <label className="mcp-form-label" htmlFor="schedule-interval">执行间隔（秒）</label>
        <input id="schedule-interval" className="mcp-input-fancy" type="number" required min={5} max={31536000} step={1}
          value={interval} disabled={busy} onChange={e => setInterval(Number(e.target.value))} />
        <p className="form-desc-text">应用运行期间调度；每次结束后按间隔计时，失败或中断后暂停并等待核对。</p>
        <label className="mcp-form-label" htmlFor="schedule-action">动作指令</label>
        <textarea id="schedule-action" className="mcp-input-fancy" rows={4} required value={action} disabled={busy || maintenance}
          onChange={e => setAction(e.target.value)} placeholder="例如：检查项目目录，汇总新增的文档。" />
        {!maintenance && <fieldset disabled={busy}><legend>本任务允许使用的 MCP</legend>
          <p className="form-desc-text">Desktop 基础工具始终可用；仅选择本任务需要的外部服务。</p>
          {servers.length === 0 ? <p className="form-desc-text">还没有配置外部 MCP 服务。</p> : servers.map(server => <label key={server.id}>
            <input type="checkbox" checked={allowed.includes(server.id)} onChange={e => setAllowed(previous => e.target.checked ? [...previous, server.id] : previous.filter(id => id !== server.id))} />{server.name || server.id}
          </label>)}
        </fieldset>}
        {error && <p className="agent-load-error" role="alert">保存失败：{error}。请保留当前内容后重试。</p>}
      </div>
      <div className="mcp-modal-footer"><button type="button" className="btn-secondary" disabled={busy} onClick={onClose}>取消</button>
        <button type="submit" className="btn-primary" disabled={busy || !name.trim() || !action.trim()}>{busy ? '正在保存…' : '保存任务'}</button></div>
    </form>
  </dialog>
}
