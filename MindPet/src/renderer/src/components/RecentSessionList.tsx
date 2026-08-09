import React, { useState, useMemo, useRef, useEffect } from 'react'
import { createPortal } from 'react-dom'
import { Virtuoso } from 'react-virtuoso'
import type { Session } from '../hooks/useAppStore'
import { BookOpen, ChevronDown, ChevronRight, Pencil, Pin, PinOff, Trash2, X } from 'lucide-react'
import { MarkdownText } from './ChatMessageItem'
import { getRemoteSessionChannel, isRemoteSessionId } from '../utils/sessionChannels'

// ── 分组定义 ──────────────────────────────────────────────────
type GroupKey = 'pinned' | 'today' | 'yesterday' | 'thisWeek' | 'earlier'

const GROUP_LABELS: Record<GroupKey, string> = {
  pinned: '置顶',
  today: '今天',
  yesterday: '昨天',
  thisWeek: '本周',
  earlier: '更早'
}

const GROUP_ORDER: GroupKey[] = ['pinned', 'today', 'yesterday', 'thisWeek', 'earlier']

// 将 "yyyy-MM-dd HH:mm:ss" 解析为当天 0 点的 Date
function parseSessionDate(time: string): Date | null {
  if (!time || time.length < 10) return null
  const d = new Date(time.replace(/-/g, '/'))
  if (isNaN(d.getTime())) return null
  return new Date(d.getFullYear(), d.getMonth(), d.getDate())
}

function getGroupKey(s: Session): GroupKey {
  if (s.pinned) return 'pinned'
  const d = parseSessionDate(s.createdAt || s.time)
  if (!d) return 'earlier'
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const diffDays = Math.round((today.getTime() - d.getTime()) / 86400000)
  if (diffDays <= 0) return 'today'
  if (diffDays === 1) return 'yesterday'
  if (diffDays <= 7) return 'thisWeek'
  return 'earlier'
}

function getSummaryMarkdown(s: Session): string {
  return (s.contextSummary || '')
    .replace(/\n*---\s*\n*<details>[\s\S]*?<\/details>/gi, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .trim()
}

function getSummaryPreview(s: Session): string {
  const markdown = getSummaryMarkdown(s)
  if (!markdown) return ''
  const sections = markdown.split(/(?=^##\s+\[)/m).filter(Boolean)
  const latest = sections[sections.length - 1] || markdown
  return latest
    .replace(/^##[^\n]*\n?/m, '')
    .replace(/```[\s\S]*?```/g, '[代码]')
    .replace(/[*_`#>\-]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

// 摘要存在时优先展示摘要，否则展示最后一条非系统消息。
function getPreview(s: Session): string {
  const summary = getSummaryPreview(s)
  if (summary) return summary.length > 40 ? summary.slice(0, 40) + '…' : summary
  const msgs = s.messages || []
  for (let i = msgs.length - 1; i >= 0; i--) {
    const m = msgs[i]
    if (m.sender === 'system' || m.isThinking) continue
    let text = (m.text || '').replace(/```[\s\S]*?```/g, '[代码]').replace(/[*_`#>\-]/g, '').replace(/\s+/g, ' ').trim()
    if (!text) {
      const fileNames = Array.isArray(m.fileInfos)
        ? m.fileInfos.map((file: { name?: string }) => file.name).filter(Boolean)
        : []
      if (fileNames.length > 0) return `附件：${fileNames.join('、')}`
      if (m.fileInfo?.name) return `附件：${m.fileInfo.name}`
      continue
    }
    return text.length > 40 ? text.slice(0, 40) + '…' : text
  }
  return ''
}

function checkIsThinking(s: Session): boolean {
  const msgs = s.messages || []
  for (let i = msgs.length - 1; i >= 0; i--) {
    const m = msgs[i]
    if (m.sender === 'agent') return !!m.isThinking
  }
  return false
}

// 扁平化的渲染单元
type RenderRow =
  | { type: 'header'; key: string; groupKey: GroupKey; label: string }
  | { type: 'item'; key: string; session: Session; groupKey?: GroupKey }

interface Props {
  sessions: Session[]
  activeSessionId: string
  onSelect: (id: string) => void
  onDelete: (id: string) => void
  onTogglePin: (id: string) => void
  onRename: (id: string, name: string) => void
  onReorder: (draggedId: string, targetId: string, placement?: 'before' | 'after') => void
}

type DragPlacement = 'before' | 'after'

export function RecentSessionList(props: Props): React.JSX.Element {
  const { sessions, activeSessionId, onSelect, onDelete, onTogglePin, onRename, onReorder } = props

  const [searchQuery, setSearchQuery] = useState('')
  const [collapsedGroups, setCollapsedGroups] = useState<Record<GroupKey, boolean>>({
    pinned: false,
    today: false,
    yesterday: true,
    thisWeek: true,
    earlier: true
  })

  const toggleGroup = (g: GroupKey) => {
    setCollapsedGroups(prev => ({
      ...prev,
      [g]: !prev[g]
    }))
  }

  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; sessionId: string } | null>(null)
  const [summarySessionId, setSummarySessionId] = useState<string | null>(null)
  const [renamingId, setRenamingId] = useState<string | null>(null)
  const [renamingValue, setRenamingValue] = useState('')
  const containerRef = useRef<HTMLDivElement>(null)
  const renameInputRef = useRef<HTMLInputElement>(null)
  const longPressTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const pointerStartRef = useRef<{ x: number; y: number; sessionId: string } | null>(null)
  const dragStateRef = useRef<{
    draggingId: string | null
    targetId: string | null
    placement: DragPlacement
  }>({ draggingId: null, targetId: null, placement: 'after' })
  const suppressClickRef = useRef(false)
  const [draggingSessionId, setDraggingSessionId] = useState<string | null>(null)
  const [dragOverSessionId, setDragOverSessionId] = useState<string | null>(null)
  const [dragPlacement, setDragPlacement] = useState<DragPlacement>('after')
  const [dragPoint, setDragPoint] = useState<{ x: number; y: number } | null>(null)

  const clearLongPress = (): void => {
    if (longPressTimerRef.current) {
      clearTimeout(longPressTimerRef.current)
      longPressTimerRef.current = null
    }
  }

  useEffect(() => {
    const handlePointerMove = (event: PointerEvent): void => {
      const start = pointerStartRef.current
      if (!start) return
      const distance = Math.hypot(event.clientX - start.x, event.clientY - start.y)
      if (!dragStateRef.current.draggingId && distance > 8) {
        clearLongPress()
        pointerStartRef.current = null
        return
      }
      if (!dragStateRef.current.draggingId) return
      setDragPoint({ x: event.clientX, y: event.clientY })
      const draggingId = dragStateRef.current.draggingId
      const sessionElements = Array.from(
        containerRef.current?.querySelectorAll<HTMLElement>('[data-session-id]') || []
      )
        .filter(element => element.dataset.sessionId !== draggingId)
        .map(element => ({ element, rect: element.getBoundingClientRect() }))
        .sort((a, b) => a.rect.top - b.rect.top)

      // A row's midpoint is an invisible insertion boundary. When the pointer
      // is in a gap, the next row wins; when it is over a row, that row itself
      // decides before/after so the behavior matches the visible card.
      const hovered = sessionElements.find(({ rect }) =>
        event.clientX >= rect.left && event.clientX <= rect.right
        && event.clientY >= rect.top && event.clientY <= rect.bottom
      )
      let targetInfo = hovered || sessionElements.find(({ rect }) => event.clientY < rect.top + rect.height / 2)
      let placement: DragPlacement = targetInfo && event.clientY >= targetInfo.rect.top + targetInfo.rect.height / 2
        ? 'after'
        : 'before'
      if (!targetInfo && sessionElements.length > 0) {
        targetInfo = sessionElements[sessionElements.length - 1]
        placement = 'after'
      }
      const targetId = targetInfo?.element.dataset.sessionId || null
      dragStateRef.current.targetId = targetId
      dragStateRef.current.placement = placement
      setDragOverSessionId(targetId)
      setDragPlacement(placement)
    }

    const handlePointerUp = (): void => {
      const { draggingId, targetId, placement } = dragStateRef.current
      clearLongPress()
      if (draggingId) {
        if (targetId && targetId !== draggingId) onReorder(draggingId, targetId, placement)
        suppressClickRef.current = true
      }
      dragStateRef.current = { draggingId: null, targetId: null, placement: 'after' }
      pointerStartRef.current = null
      setDraggingSessionId(null)
      setDragOverSessionId(null)
      setDragPlacement('after')
      setDragPoint(null)
      document.body.classList.remove('session-dragging')
    }

    document.addEventListener('pointermove', handlePointerMove, true)
    document.addEventListener('pointerup', handlePointerUp, true)
    return () => {
      clearLongPress()
      document.removeEventListener('pointermove', handlePointerMove, true)
      document.removeEventListener('pointerup', handlePointerUp, true)
      setDragPoint(null)
      document.body.classList.remove('session-dragging')
    }
  }, [onReorder])

  const handleSessionPointerDown = (event: React.PointerEvent, sessionId: string): void => {
    if (event.button !== 0 || renamingId === sessionId) return
    clearLongPress()
    pointerStartRef.current = { x: event.clientX, y: event.clientY, sessionId }
    longPressTimerRef.current = setTimeout(() => {
      if (pointerStartRef.current?.sessionId !== sessionId) return
      dragStateRef.current = { draggingId: sessionId, targetId: sessionId, placement: 'after' }
      setDraggingSessionId(sessionId)
      setDragOverSessionId(sessionId)
      setDragPlacement('after')
      setDragPoint({ x: event.clientX, y: event.clientY })
      document.body.classList.add('session-dragging')
    }, 200)
  }

  // 搜索过滤
  const filteredSessions = useMemo(() => {
    const q = searchQuery.trim().toLowerCase()
    if (!q) return sessions
    return sessions.filter(s =>
      (s.name || '').toLowerCase().includes(q)
      || getPreview(s).toLowerCase().includes(q)
      || getSummaryMarkdown(s).toLowerCase().includes(q)
    )
  }, [sessions, searchQuery])

  // 分组并扁平化
  const rows = useMemo<RenderRow[]>(() => {
    const hasQuery = searchQuery.trim().length > 0
    // 搜索时不显示分组标题，只保留置顶排序
    if (hasQuery) {
      return filteredSessions.map(s => ({ type: 'item' as const, key: `item-${s.id}`, session: s }))
    }
    const buckets: Record<GroupKey, Session[]> = { pinned: [], today: [], yesterday: [], thisWeek: [], earlier: [] }
    for (const s of filteredSessions) {
      // 置顶的归到 pinned，其余按时间
      const k = getGroupKey(s)
      buckets[k].push(s)
    }
    const out: RenderRow[] = []
    for (const g of GROUP_ORDER) {
      if (buckets[g].length === 0) continue
      out.push({ type: 'header', key: `header-${g}`, groupKey: g, label: GROUP_LABELS[g] })
      if (!collapsedGroups[g]) {
        for (const s of buckets[g]) {
          out.push({ type: 'item', key: `item-${s.id}`, session: s, groupKey: g })
        }
      }
    }
    return out
  }, [filteredSessions, searchQuery, collapsedGroups])

  // 点击外部关闭右键菜单
  useEffect(() => {
    if (!contextMenu) return
    const close = () => setContextMenu(null)
    window.addEventListener('click', close)
    window.addEventListener('scroll', close, true)
    return () => {
      window.removeEventListener('click', close)
      window.removeEventListener('scroll', close, true)
    }
  }, [contextMenu])

  useEffect(() => {
    if (!summarySessionId) return
    const closeOnEscape = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') setSummarySessionId(null)
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [summarySessionId])

  // 重命名时聚焦输入框
  useEffect(() => {
    if (renamingId && renameInputRef.current) {
      renameInputRef.current.focus()
      renameInputRef.current.select()
    }
  }, [renamingId])

  const handleContextMenu = (e: React.MouseEvent, sessionId: string): void => {
    e.preventDefault()
    e.stopPropagation()
    setContextMenu({ x: e.clientX, y: e.clientY, sessionId })
  }

  const startRename = (session: Session): void => {
    setRenamingId(session.id)
    setRenamingValue(session.name || '')
    setContextMenu(null)
  }

  const commitRename = (): void => {
    if (renamingId) {
      onRename(renamingId, renamingValue)
    }
    setRenamingId(null)
    setRenamingValue('')
  }

  const cancelRename = (): void => {
    setRenamingId(null)
    setRenamingValue('')
  }

  const getContextMenuPosition = (): { left: number; top: number } => {
    if (!contextMenu) return { left: 8, top: 8 }
    const bounds = containerRef.current?.getBoundingClientRect()
    const menuWidth = 136
    const menuHeight = 180
    const minLeft = bounds ? bounds.left + 4 : 8
    const maxLeft = bounds
      ? Math.max(minLeft, bounds.right - menuWidth)
      : Math.max(minLeft, window.innerWidth - menuWidth - 8)
    const minTop = 8
    const maxTop = Math.max(minTop, window.innerHeight - menuHeight - 8)
    return {
      left: Math.round(Math.min(Math.max(contextMenu.x - 4, minLeft), maxLeft)),
      top: Math.round(Math.min(Math.max(contextMenu.y, minTop), maxTop))
    }
  }

  const renderRow = (index: number): React.ReactNode => {
    const row = rows[index]
    if (row.type === 'header') {
      const isCollapsed = collapsedGroups[row.groupKey]
      return (
        <div
          className="recent-group-header"
          onClick={() => toggleGroup(row.groupKey)}
        >
          <span className="recent-group-arrow">
            {isCollapsed
              ? <ChevronRight size={12} strokeWidth={2} aria-hidden="true" />
              : <ChevronDown size={12} strokeWidth={2} aria-hidden="true" />}
          </span>
          <span>{row.label}</span>
        </div>
      )
    }
    const s = row.session
    const isActive = s.id === activeSessionId
    const isRenaming = renamingId === s.id
    const preview = getPreview(s)
    const hasSummary = Boolean(getSummaryMarkdown(s))
    const isThinking = checkIsThinking(s)
    const groupItemPosition = row.groupKey
      ? rows.slice(0, index).filter(candidate => candidate.type === 'item' && candidate.groupKey === row.groupKey).length
      : 0
    const sessionRows = rows.filter((candidate): candidate is { type: 'item'; key: string; session: Session; groupKey?: GroupKey } => candidate.type === 'item')
    const itemPosition = sessionRows.findIndex(candidate => candidate.session.id === s.id)
    const targetPosition = dragOverSessionId
      ? sessionRows.findIndex(candidate => candidate.session.id === dragOverSessionId)
      : -1
    const draggingPosition = draggingSessionId
      ? sessionRows.findIndex(candidate => candidate.session.id === draggingSessionId)
      : -1
    const insertionPosition = draggingPosition >= 0 && targetPosition >= 0
      ? targetPosition - (draggingPosition < targetPosition ? 1 : 0) + (dragPlacement === 'after' ? 1 : 0)
      : -1
    const itemPositionWithoutDragging = draggingPosition >= 0 && itemPosition > draggingPosition
      ? itemPosition - 1
      : itemPosition
    const movingDown = draggingPosition >= 0 && insertionPosition > draggingPosition
    const movingUp = draggingPosition >= 0 && insertionPosition < draggingPosition
    const pushUp = movingDown && itemPositionWithoutDragging >= draggingPosition
      && itemPositionWithoutDragging < insertionPosition
    const pushDown = movingUp && itemPositionWithoutDragging >= insertionPosition
      && itemPositionWithoutDragging < draggingPosition
    return (
      <div
        className={`recent-item ${row.groupKey ? `recent-group-${row.groupKey}` : ''} ${isActive ? 'active' : ''} ${s.pinned ? 'pinned' : ''} ${isThinking ? 'thinking' : ''} ${draggingSessionId === s.id ? 'dragging' : ''} ${dragOverSessionId === s.id && draggingSessionId !== s.id ? `drag-over drag-over-${dragPlacement}` : ''} ${pushDown ? 'drag-push-down' : ''} ${pushUp ? 'drag-push-up' : ''}`}
        data-session-id={s.id}
        style={row.groupKey === 'pinned' || row.groupKey === 'today' || row.groupKey === 'earlier'
          ? { '--recent-group-delay': `${Math.min(groupItemPosition, 14) * 34}ms` } as React.CSSProperties
          : undefined}
        onPointerDown={event => handleSessionPointerDown(event, s.id)}
        onClick={() => {
          if (suppressClickRef.current) {
            suppressClickRef.current = false
            return
          }
          if (!isRenaming) onSelect(s.id)
        }}
        onContextMenu={(e) => handleContextMenu(e, s.id)}
        onDoubleClick={() => startRename(s)}
        title={s.name}
      >
        <span className="recent-dot"></span>
        {s.pinned && <span className="recent-pin-icon" title="已置顶"><Pin size={12} strokeWidth={2} aria-hidden="true" /></span>}
        <div className="recent-meta">
          {isRenaming ? (
            <input
              ref={renameInputRef}
              className="recent-rename-input"
              value={renamingValue}
              onChange={(e) => setRenamingValue(e.target.value)}
              onClick={(e) => e.stopPropagation()}
              onKeyDown={(e) => {
                if (e.key === 'Enter') commitRename()
                else if (e.key === 'Escape') cancelRename()
              }}
              onBlur={commitRename}
              maxLength={50}
            />
          ) : (
            <>
              <span className="recent-title" title={s.name}>
                {getRemoteSessionChannel(s.id) && (
                  <span style={{
                    display: 'inline-block',
                    fontSize: '10px',
                    padding: '1px 5px',
                    borderRadius: '4px',
                    background: getRemoteSessionChannel(s.id) === 'wechat' ? 'rgba(16, 185, 129, 0.15)' : 'color-mix(in srgb, var(--color-action-primary) 16%, var(--bg-card))',
                    color: getRemoteSessionChannel(s.id) === 'wechat' ? '#10b981' : 'var(--ds-color-23313239366462)',
                    marginRight: '6px',
                    fontWeight: 600,
                    verticalAlign: 'middle',
                    lineHeight: '1.2'
                  }}>
                    {getRemoteSessionChannel(s.id) === 'wechat' ? '微信' : 'QQ'}
                  </span>
                )}
                {s.name}
              </span>
              <span className={`recent-preview ${hasSummary ? 'summary' : ''}`} title={preview}>
                {hasSummary && <BookOpen size={11} strokeWidth={2} aria-hidden="true" />}
                <span className="recent-preview-text">{preview || '暂无消息'}</span>
              </span>
            </>
          )}
        </div>
        {!isRenaming && (
          <button
            className="recent-delete-btn"
            onPointerDown={e => e.stopPropagation()}
            onClick={(e) => { e.stopPropagation(); onDelete(s.id) }}
            title="删除会话"
          >
            <Trash2 size={14} strokeWidth={2} aria-hidden="true" />
          </button>
        )}
      </div>
    )
  }

  return (
    <div className="recent-list-wrapper" ref={containerRef}>
      <div className="recent-search-wrapper">
        <input
          className="recent-search-input"
          type="text"
          placeholder="搜索会话..."
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
        />
        {searchQuery && (
          <button className="recent-search-clear" onClick={() => setSearchQuery('')} title="清除搜索">
            <X size={13} strokeWidth={2} aria-hidden="true" />
          </button>
        )}
      </div>
      <div className="sidebar-recent-container">
        {rows.length === 0 ? (
          <div className="recent-empty">{searchQuery ? '未找到匹配的会话' : '暂无会话'}</div>
        ) : (
          <Virtuoso
            data={rows}
            itemContent={renderRow}
            style={{ height: '100%' }}
            computeItemKey={(_, row) => (row as RenderRow).key}
            defaultItemHeight={56}
            increaseViewportBy={{ top: 100, bottom: 100 }}
          />
        )}
      </div>

      {draggingSessionId && dragPoint && createPortal((() => {
        const draggedSession = sessions.find(session => session.id === draggingSessionId)
        if (!draggedSession) return null
        return (
          <div
            className={`session-drag-ghost session-drag-ghost-${dragPlacement}`}
            style={{ left: dragPoint.x, top: dragPoint.y }}
            aria-hidden="true"
          >
            <span className="recent-dot" />
            <div className="session-drag-ghost-copy">
              <strong>{draggedSession.name}</strong>
              <span>{getPreview(draggedSession) || '暂无消息'}</span>
            </div>
          </div>
        )
      })(), document.body)}

      {contextMenu && (
        <div
          className="recent-context-menu"
          style={getContextMenuPosition()}
          onClick={(e) => e.stopPropagation()}
        >
          {(() => {
            const target = sessions.find(s => s.id === contextMenu.sessionId)
            if (!target) return null
            const isRemote = isRemoteSessionId(target.id)
            return (
              <>
                {!isRemote && (
                  <div
                    className="recent-context-item"
                    onClick={() => { onTogglePin(target.id); setContextMenu(null) }}
                  >
                    {target.pinned
                      ? <PinOff size={14} strokeWidth={2} aria-hidden="true" />
                      : <Pin size={14} strokeWidth={2} aria-hidden="true" />}
                    {target.pinned ? '取消置顶' : '置顶'}
                  </div>
                )}
                {getSummaryMarkdown(target) && (
                  <div
                    className="recent-context-item"
                    onClick={() => { setSummarySessionId(target.id); setContextMenu(null) }}
                  >
                    <BookOpen size={14} strokeWidth={2} aria-hidden="true" />
                    查看摘要
                  </div>
                )}
                <div
                  className="recent-context-item"
                  onClick={() => startRename(target)}
                >
                  <Pencil size={14} strokeWidth={2} aria-hidden="true" />
                  重命名
                </div>
                <div className="recent-context-divider"></div>
                <div
                  className="recent-context-item danger"
                  onClick={() => { onDelete(target.id); setContextMenu(null) }}
                >
                  <Trash2 size={14} strokeWidth={2} aria-hidden="true" />
                  删除
                </div>
              </>
            )
          })()}
        </div>
      )}

      {summarySessionId && createPortal((() => {
        const summarySession = sessions.find(session => session.id === summarySessionId)
        const summary = summarySession ? getSummaryMarkdown(summarySession) : ''
        if (!summarySession || !summary) return null
        return (
          <div className="session-summary-overlay" onMouseDown={() => setSummarySessionId(null)}>
            <section
              className="session-summary-dialog"
              role="dialog"
              aria-modal="true"
              aria-labelledby="session-summary-title"
              onMouseDown={(event) => event.stopPropagation()}
            >
              <header className="session-summary-header">
                <div className="session-summary-heading">
                  <span className="session-summary-icon">
                    <BookOpen size={18} strokeWidth={2} aria-hidden="true" />
                  </span>
                  <div>
                    <h2 id="session-summary-title">会话摘要</h2>
                    <p>{summarySession.name}</p>
                  </div>
                </div>
                <button type="button" onClick={() => setSummarySessionId(null)} title="关闭摘要">
                  <X size={17} strokeWidth={2} aria-hidden="true" />
                </button>
              </header>
              <div className="session-summary-content">
                <MarkdownText rawText={summary} />
              </div>
            </section>
          </div>
        )
      })(), document.body)}
    </div>
  )
}
