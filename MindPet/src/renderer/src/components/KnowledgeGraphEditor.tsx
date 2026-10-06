import React from 'react'
import { ArrowRight, LoaderCircle, Trash2, X } from 'lucide-react'

export interface EditableGraphEntity {
  id: string
  label: string
  type: string
  summary?: string
  importance?: number
}

export type GraphEdit =
  | { kind: 'entity'; entity?: EditableGraphEntity }
  | {
      kind: 'relation'
      id?: string
      source: string
      target: string
      label?: string
      importance?: number
    }

interface Props {
  edit: GraphEdit
  entities: EditableGraphEntity[]
  typeNames: Record<string, string>
  relationNames: Record<string, string>
  onClose: () => void
  onSaved: (focusId?: string) => Promise<void>
}

export function KnowledgeGraphEditor({
  edit,
  entities,
  typeNames,
  relationNames,
  onClose,
  onSaved
}: Props): React.JSX.Element {
  const [label, setLabel] = React.useState(
    edit.kind === 'entity' ? edit.entity?.label || '' : edit.label || 'related_to'
  )
  const [type, setType] = React.useState(
    edit.kind === 'entity' ? edit.entity?.type || 'topic' : 'topic'
  )
  const [summary, setSummary] = React.useState(
    edit.kind === 'entity' ? edit.entity?.summary || '' : ''
  )
  const [importance, setImportance] = React.useState(
    Math.max(
      20,
      Math.round(
        (edit.kind === 'entity' ? edit.entity?.importance || 0.65 : edit.importance || 0.65) * 100
      )
    )
  )
  const [source, setSource] = React.useState(edit.kind === 'relation' ? edit.source : '')
  const [target, setTarget] = React.useState(edit.kind === 'relation' ? edit.target : '')
  const [options, setOptions] = React.useState(entities)
  const [optionsLoading, setOptionsLoading] = React.useState(edit.kind === 'relation')
  const [error, setError] = React.useState('')
  const [saving, setSaving] = React.useState(false)
  const isEntity = edit.kind === 'entity'
  const isExisting = isEntity ? Boolean(edit.entity) : Boolean(edit.id)
  const lockedUser = isEntity && edit.entity?.label.toLowerCase() === 'user'
  const title = isEntity
    ? isExisting
      ? '编辑实体'
      : '新增实体'
    : isExisting
      ? '编辑关系'
      : '建立关系'

  React.useEffect(() => {
    if (edit.kind !== 'relation') return
    let cancelled = false
    window.api
      .listKnowledgeGraphEntities()
      .then((result) => {
        if (cancelled) return
        if (result.status === 'ok' && result.entities) setOptions(result.entities)
        else setError(result.message || '无法读取节点列表，请关闭后重试')
      })
      .catch(() => {
        if (!cancelled) setError('无法读取节点列表，请关闭后重试')
      })
      .finally(() => {
        if (!cancelled) setOptionsLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [edit.kind])

  const save = async (event: React.FormEvent): Promise<void> => {
    event.preventDefault()
    setError('')
    if (!isEntity && source === target) {
      setError('请选择两个不同节点')
      return
    }
    setSaving(true)
    try {
      const result =
        edit.kind === 'entity'
          ? await window.api.saveKnowledgeGraphEntity(
              { label: label.trim(), type, summary: summary.trim(), importance: importance / 100 },
              edit.entity?.id
            )
          : await window.api.saveKnowledgeGraphRelation(
              { source, target, label, importance: importance / 100 },
              edit.id
            )
      if (result.status !== 'ok') {
        setError(result.message || '保存失败，请重试')
        return
      }
      await onSaved(isEntity ? result.id : source)
    } catch {
      setError('保存失败，请确认本地后端已启动后重试')
    } finally {
      setSaving(false)
    }
  }

  const remove = async (): Promise<void> => {
    if (
      edit.kind !== 'relation' ||
      !edit.id ||
      !confirm('删除这条事实关系及其来源记录吗？节点会保留。')
    )
      return
    setSaving(true)
    setError('')
    try {
      const result = await window.api.deleteKnowledgeGraphRelation(edit.id)
      if (result.status !== 'ok' || !result.deleted) {
        setError(result.message || '删除失败，请重试')
        return
      }
      await onSaved(source)
    } catch {
      setError('删除失败，请确认本地后端已启动后重试')
    } finally {
      setSaving(false)
    }
  }

  return (
    <aside className="knowledge-evidence-panel kg-editor" aria-label={title}>
      <div className="kg-editor-heading">
        <h3>{title}</h3>
        <button
          type="button"
          className="kg-icon-button"
          aria-label="关闭编辑器"
          onClick={onClose}
          disabled={saving}
        >
          <X size={16} />
        </button>
      </div>
      <p className="kg-editor-description">
        {isEntity
          ? '留下一个明确的名称和说明，让这颗星有迹可循。'
          : '连接两个实体，确认箭头方向和它们之间的事实。'}
      </p>
      <form onSubmit={(event) => void save(event)}>
        <fieldset disabled={saving}>
          {isEntity ? (
            <>
              <label className="kg-edit-field">
                名称
                <input
                  autoFocus
                  required
                  maxLength={256}
                  value={label}
                  onChange={(event) => setLabel(event.target.value)}
                  readOnly={lockedUser}
                  placeholder="例如：摄影计划"
                />
              </label>
              <label className="kg-edit-field">
                实体类型
                <select
                  value={type}
                  onChange={(event) => setType(event.target.value)}
                  disabled={lockedUser}
                >
                  {Object.entries(typeNames).map(([key, name]) => (
                    <option key={key} value={key}>
                      {name}
                    </option>
                  ))}
                </select>
              </label>
              {lockedUser && (
                <p className="kg-edit-help">User 用于识别中心用户，可以修改说明和重要度。</p>
              )}
              <label className="kg-edit-field">
                说明
                <textarea
                  rows={4}
                  maxLength={500}
                  value={summary}
                  onChange={(event) => setSummary(event.target.value)}
                  placeholder="这是什么，对你有什么意义？"
                />
                <small>{summary.length} / 500</small>
              </label>
            </>
          ) : (
            <>
              <label className="kg-edit-field">
                起点
                <select
                  autoFocus
                  required
                  value={source}
                  onChange={(event) => setSource(event.target.value)}
                  disabled={optionsLoading}
                >
                  <option value="">选择起点节点</option>
                  {options.map((entity) => (
                    <option key={entity.id} value={entity.id}>
                      {entity.label} · {typeNames[entity.type] || entity.type}
                    </option>
                  ))}
                </select>
              </label>
              <div className="kg-edit-direction">
                <ArrowRight size={16} /> 从起点指向终点{' '}
                <button
                  type="button"
                  onClick={() => {
                    setSource(target)
                    setTarget(source)
                  }}
                >
                  交换方向
                </button>
              </div>
              <label className="kg-edit-field">
                终点
                <select
                  required
                  value={target}
                  onChange={(event) => setTarget(event.target.value)}
                  disabled={optionsLoading}
                >
                  <option value="">选择终点节点</option>
                  {options.map((entity) => (
                    <option key={entity.id} value={entity.id} disabled={entity.id === source}>
                      {entity.label} · {typeNames[entity.type] || entity.type}
                    </option>
                  ))}
                </select>
              </label>
              <label className="kg-edit-field">
                事实关系
                <select value={label} onChange={(event) => setLabel(event.target.value)}>
                  {Object.entries(relationNames).map(([key, name]) => (
                    <option key={key} value={key}>
                      {name}
                    </option>
                  ))}
                </select>
              </label>
              <p className="kg-relation-preview">
                {options.find((entity) => entity.id === source)?.label || '起点'}{' '}
                <b>{relationNames[label]}</b>{' '}
                {options.find((entity) => entity.id === target)?.label || '终点'}
              </p>
            </>
          )}
          <label className="kg-edit-field kg-edit-importance">
            <span>
              重要度 <b>{importance}%</b>
            </span>
            <input
              type="range"
              min={20}
              max={100}
              step={5}
              value={importance}
              onChange={(event) => setImportance(Number(event.target.value))}
            />
          </label>
          <p className="kg-edit-help">
            保存到本地图谱，保留手动确认来源。重要度影响现有记忆保留规则。
          </p>
          {error && (
            <p className="kg-edit-error" role="alert">
              {error}
            </p>
          )}
          <div className="kg-edit-footer">
            <button type="button" className="kg-tool-button" onClick={onClose}>
              取消
            </button>
            <button type="submit" className="kg-edit-save" disabled={optionsLoading}>
              {saving && <LoaderCircle size={14} className="kg-spin" />}
              {saving ? '保存中' : isEntity ? '保存节点' : '确认关系'}
            </button>
          </div>
          {edit.kind === 'relation' && edit.id && (
            <button type="button" className="kg-edit-delete" onClick={() => void remove()}>
              <Trash2 size={14} />
              删除这条关系
            </button>
          )}
        </fieldset>
      </form>
    </aside>
  )
}
