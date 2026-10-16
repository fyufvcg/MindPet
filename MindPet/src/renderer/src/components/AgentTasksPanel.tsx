import React, { useCallback, useEffect, useRef, useState } from 'react'
import { RefreshCw, Play, Square } from 'lucide-react'

const labels: Record<string, string> = {
  running: '执行中', completed: '已完成', failed: '执行失败', interrupted: '已中断',
  cancelled: '已停止', budget_exhausted: '预算已用完', needs_input: '需要补充或核验'
}
const resumable = new Set(['failed', 'interrupted', 'cancelled', 'budget_exhausted', 'needs_input'])
interface Task { id: string; goal: string; status: string; session_id: string; updated_at: number }
export function AgentTasksPanel({ servers }: { servers: Array<{ id: string; name?: string }> }): React.JSX.Element {
  const [tasks, setTasks] = useState<Task[]>([])
  const [selected, setSelected] = useState('')
  const [detail, setDetail] = useState<any>(null)
  const [instruction, setInstruction] = useState('')
  const [allowed, setAllowed] = useState<string[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [reply, setReply] = useState('')
  const generation = useRef(0)
  const refresh = useCallback(async (): Promise<void> => {
    try { setTasks(await window.api.listAgentTasks()); setError('') }
    catch (e) { setError(String(e)) }
    finally { setLoading(false) }
  }, [])
  useEffect(() => {
    let disposed = false
    let timer: ReturnType<typeof setTimeout>
    const poll = async (): Promise<void> => {
      if (disposed) return
      await refresh()
      if (!disposed) timer = setTimeout(() => { void poll() }, 5000)
    }
    void poll()
    return () => { disposed = true; clearTimeout(timer) }
  }, [refresh])
  useEffect(() => {
    const current = ++generation.current
    setDetail(null); setReply(''); setInstruction('')
    if (!selected) return
    let initial = true
    let timer: ReturnType<typeof setTimeout>
    const load = async (): Promise<void> => {
      try {
        const row = await window.api.getAgentTask(selected)
        if (generation.current !== current) return
        setDetail(row)
        if (initial) {
          setAllowed((row.snapshot?.selectedMcpServerIds || []).filter((id: string) => servers.some(s => s.id === id)))
          initial = false
        }
      } catch (e) { if (generation.current === current) setError(String(e)) }
      finally { if (generation.current === current) timer = setTimeout(() => { void load() }, 5000) }
    }
    void load()
    return () => { generation.current++; clearTimeout(timer) }
  }, [selected])
  const selectedRow = tasks.find(task => task.id === selected)
  const act = async (action: 'cancel' | 'resume'): Promise<void> => {
    if (!selected || busy) return
    const id = selected
    setBusy(id); setError('')
    try {
      if (action === 'cancel') {
        const result = await window.api.cancelAgentTask(id)
        if (!result.cancelled) throw new Error('任务当前无法取消，请刷新状态后重试。')
      } else {
        const result = await window.api.resumeAgentTask(id, instruction.trim() || '继续', allowed)
        if (selected === id) setReply(result.reply)
      }
      await refresh()
      const updated = await window.api.getAgentTask(id)
      if (generation.current && selected === id) setDetail(updated)
    } catch (e) { setError(String(e)) }
    finally { setBusy('') }
  }
  return <section className="agent-tasks-panel" aria-label="任务记录">
    <div className="agent-tasks-toolbar"><div><h3>任务记录</h3><p>查看最近 100 个任务，核对结果或从中断处继续。</p></div>
      <button type="button" className="btn-secondary" onClick={() => { void refresh() }}><RefreshCw size={14} />刷新</button></div>
    {error && <div className="agent-load-error" role="alert">{error}<button type="button" onClick={() => { void refresh() }}>重试</button></div>}
    {loading && <p role="status">正在加载任务记录…</p>}
    {!loading && tasks.length === 0 && !error && <p className="agent-tasks-empty">还没有任务记录。开始一次对话后，这里会显示执行状态。</p>}
    <div className="agent-tasks-layout">
      <div className="agent-tasks-list" aria-label="最近任务">{tasks.map(task => <button type="button" key={task.id}
        className={`agent-task-row ${selected === task.id ? 'selected' : ''}`} aria-pressed={selected === task.id}
        disabled={Boolean(busy) && selected !== task.id} onClick={() => setSelected(task.id)}>
        <span className="agent-task-goal">{task.goal}</span>
        <span className="agent-task-meta"><span data-state={task.status}>{labels[task.status] || task.status}</span><time>{new Date(task.updated_at).toLocaleString()}</time></span>
      </button>)}</div>
      {selected && <div className="agent-task-detail" aria-busy={Boolean(busy)}>
        {!detail ? <p role="status">正在读取执行记录…</p> : <>
          <h4>{detail.goal}</h4><p className="agent-task-meta">{labels[selectedRow?.status || detail.status] || detail.status}</p>
          {detail.snapshot?.plan && <><h5>执行计划</h5><p className="agent-task-pre">{detail.snapshot.plan}</p></>}
          <h5>完成证据</h5><p className="agent-task-pre">{detail.snapshot?.completionEvidence || '尚未记录完成验证，请结合执行记录核对实际结果。'}</p>
          <details><summary>最近的工具执行记录</summary><ol className="agent-task-journal">{(detail.snapshot?.journal || []).slice(-30).map((item, index) => <li key={item.callId || index}>
            <strong>{item.tool}</strong> · {item.outcome === 'unknown' ? '结果未知，需核对' : item.success ? '调用成功' : '调用失败'}<p>{item.summary}</p>
          </li>)}</ol></details>
          {resumable.has(selectedRow?.status || detail.status) && <div className="agent-task-resume">
            <label htmlFor="agent-resume-instruction">补充要求 <span>（可选）</span></label>
            <textarea id="agent-resume-instruction" rows={3} value={instruction} disabled={Boolean(busy)} onChange={e => setInstruction(e.target.value)} placeholder="例如：继续处理，输出改为 Excel，并先核对已执行的操作。" />
            <fieldset disabled={Boolean(busy)}><legend>本次允许使用的 MCP</legend>
              {servers.length === 0 ? <p>未配置外部 MCP，Desktop 基础工具仍可用。</p> : servers.map(server => <label key={server.id}>
                <input type="checkbox" checked={allowed.includes(server.id)} onChange={e => setAllowed(prev => e.target.checked ? [...prev, server.id] : prev.filter(id => id !== server.id))} />{server.name || server.id}
              </label>)}</fieldset>
            <button type="button" className="btn-primary" disabled={Boolean(busy)} onClick={() => { void act('resume') }}><Play size={14} />{busy ? '正在执行…' : '继续任务'}</button>
          </div>}
          {(selectedRow?.status === 'running' || busy === selected) && <button type="button" className="btn-secondary" onClick={() => {
            void window.api.cancelAgentTask(selected).then(result => { if (!result.cancelled) setError('取消请求未生效，请核对任务状态。'); void refresh() }).catch(e => setError(String(e)))
          }}><Square size={14} />停止执行</button>}
          {reply && <div role="status"><h5>本次结果</h5><p className="agent-task-pre">{reply}</p></div>}
        </>}
      </div>}
    </div>
  </section>
}
