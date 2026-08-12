import React from 'react'
import { createPortal } from 'react-dom'
import { formatBytes } from '../utils/helpers'
import type { AppStore } from '../hooks/useAppStore'
import { ChatMessageItem } from '../components/ChatMessageItem'
import {
  ArrowLeft,
  CheckCircle2,
  ChevronDown,
  CircleX,
  Clock3,
  CalendarDays,
  Clipboard,
  ClipboardList,
  Database,
  ExternalLink,
  FileJson2,
  FileText,
  FolderOpen,
  Ghost,
  Lightbulb,
  KeyRound,
  LoaderCircle,
  MessageSquare,
  Network,
  Pause,
  Pencil,
  Play,
  Plug,
  Plus,
  RefreshCw,
  Save,
  Search,
  Settings2,
  Siren,
  Sparkles,
  Store,
  Tag,
  Trash2,
  TrendingUp,
  Upload,
  UserRound,
  Wrench,
  X
} from 'lucide-react'

interface AgentPageProps {
  store: AppStore
  archiveMode?: boolean
}

const CONTEXT_ROUND_OPTIONS = [5, 10, 20, 50] as const

function toLocalDateKey(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function useAnimatedNumber(target: number, duration = 680): number {
  const [value, setValue] = React.useState(target)
  const previousValueRef = React.useRef(target)

  React.useEffect(() => {
    const from = previousValueRef.current
    previousValueRef.current = target

    if (from === target) {
      setValue(target)
      return
    }

    let frame = 0
    const startedAt = performance.now()
    const animate = (now: number): void => {
      const progress = Math.min(1, (now - startedAt) / duration)
      const eased = 1 - Math.pow(1 - progress, 3)
      setValue(Math.round(from + (target - from) * eased))
      if (progress < 1) frame = requestAnimationFrame(animate)
    }

    frame = requestAnimationFrame(animate)
    return () => cancelAnimationFrame(frame)
  }, [duration, target])

  return value
}

interface SkillGenerateModalProps {
  onSave: (name: string, content: string) => Promise<void>
  onClose: () => void
}

function SkillGenerateModal({ onSave, onClose }: SkillGenerateModalProps): React.JSX.Element {
  const [step, setStep] = React.useState<'input' | 'preview'>('input')
  const [skillName, setSkillName] = React.useState('')
  const [description, setDescription] = React.useState('')
  const [content, setContent] = React.useState('')
  const [viewMode, setViewMode] = React.useState<'edit' | 'preview'>('preview')
  const [toolCount, setToolCount] = React.useState(0)
  const [loading, setLoading] = React.useState(false)
  const [transitioning, setTransitioning] = React.useState(false)

  const doGenerate = async (): Promise<void> => {
    if (!description.trim()) return
    setLoading(true)
    try {
      const result = await (window as any).api.generateSkill(skillName, description)
      if (result.status === 'ok') {
        setContent(result.content || '')
        if (result.name && !skillName) setSkillName(String(result.name))
        setToolCount(result.toolCount || 0)
        setTransitioning(true)
        setTimeout(() => { setStep('preview'); setTransitioning(false) }, 280)
      } else {
        alert('生成失败: ' + (result.message || '未知错误'))
      }
    } catch (e: any) {
      alert('生成失败: ' + (e.message || String(e)))
    } finally {
      setLoading(false)
    }
  }

  const doSave = async (): Promise<void> => {
    const name = skillName.trim() || '新技能'
    await onSave(name, content)
  }

  const goBack = (): void => {
    setTransitioning(true)
    setTimeout(() => { setStep('input'); setTransitioning(false) }, 280)
  }

  // 预加载工具目录
  React.useEffect(() => {
    (window as any).api.getToolCatalog().then((data: any) => {
      if (data?.tools) setToolCount(data.tools.length)
    }).catch(() => {})
  }, [])

  return (
    <div className="mcp-modal-overlay" onClick={onClose}>
      <div className="mcp-modal-card skill-gen-modal"
        onClick={e => e.stopPropagation()}>
        {/* Header */}
        <div className="mcp-modal-header skill-gen-header">
          <div className="mcp-modal-title">
            <Sparkles size={18} strokeWidth={1.5} aria-hidden="true" />
            AI 生成技能
          </div>
          <button className="mcp-modal-close-btn" onClick={onClose} aria-label="关闭">
            <X size={18} />
          </button>
        </div>

        {/* Step indicator */}
        <div className="skill-gen-steps">
          <button
            className={`skill-gen-step ${step === 'input' ? 'active' : ''} ${step === 'preview' ? 'done' : ''}`}
            onClick={() => { if (step === 'preview') goBack() }}
            type="button"
          >
            <span className="skill-gen-step-dot">
              {step === 'preview' ? <CheckCircle2 size={14} strokeWidth={2} /> : '1'}
            </span>
            <span className="skill-gen-step-label">描述需求</span>
          </button>
          <div className={`skill-gen-step-connector ${step === 'preview' ? 'active' : ''}`} />
          <button
            className={`skill-gen-step ${step === 'preview' ? 'active' : ''}`}
            disabled={step !== 'preview'}
            type="button"
          >
            <span className="skill-gen-step-dot">2</span>
            <span className="skill-gen-step-label">预览保存</span>
          </button>
        </div>

        {/* Body */}
        <div className={`skill-gen-body ${transitioning ? 'skill-gen-transitioning' : ''}`}>
        {step === 'input' ? (
          <>
            <div className="skill-gen-field">
              <label className="skill-gen-label">
                <Tag size={14} strokeWidth={1.5} aria-hidden="true" />
                技能名称
                <span className="skill-gen-label-hint">选填</span>
              </label>
              <input
                className="mcp-input-fancy"
                placeholder="例如：每日天气助手"
                value={skillName} onChange={e => setSkillName(e.target.value)} />
            </div>

            <div className="skill-gen-field">
              <label className="skill-gen-label">
                <MessageSquare size={14} strokeWidth={1.5} aria-hidden="true" />
                描述你想要的技能
              </label>
              <textarea
                className="skill-gen-textarea-v2"
                placeholder="例如：每天早上 8 点自动查天气，下雨就提醒我带伞。或者：帮我把对话中提到的待办事项自动整理成清单。"
                value={description} onChange={e => setDescription(e.target.value)} />
            </div>

            {toolCount > 0 && (
              <div className="skill-gen-hint">
                <Lightbulb size={14} strokeWidth={1.5} aria-hidden="true" />
                <span>已加载 {toolCount} 个可用工具，AI 会自动匹配并编排技能流程</span>
              </div>
            )}
          </>
        ) : (
          <>
            <div className="skill-gen-view-toggle">
              <button
                className={`skill-gen-view-btn ${viewMode === 'edit' ? 'active' : ''}`}
                onClick={() => setViewMode('edit')}
              >
                <Pencil size={14} strokeWidth={1.5} aria-hidden="true" />
                编辑
              </button>
              <button
                className={`skill-gen-view-btn ${viewMode === 'preview' ? 'active' : ''}`}
                onClick={() => setViewMode('preview')}
              >
                <FileText size={14} strokeWidth={1.5} aria-hidden="true" />
                预览
              </button>
            </div>

            {viewMode === 'edit' ? (
              <textarea className="skill-gen-editor"
                value={content} onChange={e => setContent(e.target.value)} />
            ) : (
              <div className="skill-gen-render">
                {content || (
                  <span className="skill-gen-empty">生成内容为空，请返回上一步重新描述</span>
                )}
              </div>
            )}

            <div className="skill-gen-hint">
              <Lightbulb size={14} strokeWidth={1.5} aria-hidden="true" />
              <span>可直接编辑源码微调，满意后点击保存</span>
            </div>
          </>
        )}
        </div>

        {/* Footer */}
        <div className="mcp-modal-footer skill-gen-footer">
          {step === 'input' ? (
            <>
              <button className="btn-secondary" onClick={onClose}>取消</button>
              <button className="btn-primary skill-gen-submit" onClick={doGenerate}
                disabled={!description.trim() || loading}>
                {loading ? (
                  <><LoaderCircle size={15} strokeWidth={2} className="ui-icon-leading spinning" aria-hidden="true" />生成中…</>
                ) : (
                  <><Sparkles size={15} strokeWidth={1.5} className="ui-icon-leading" aria-hidden="true" />生成技能</>
                )}
              </button>
            </>
          ) : (
            <>
              <button className="btn-secondary" onClick={goBack}>
                <ArrowLeft size={15} strokeWidth={1.5} className="ui-icon-leading" aria-hidden="true" />
                返回修改
              </button>
              <button className="btn-primary skill-gen-submit" onClick={doSave}
                disabled={!content.trim()}>
                <Save size={15} strokeWidth={1.5} className="ui-icon-leading" aria-hidden="true" />
                保存技能
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  )
}

export function AgentPage({ store, archiveMode = false }: AgentPageProps): React.JSX.Element {
  const {
    agentSubTab, setAgentSubTab,
    // skills
    skillsList, skillsPath,
    handleSkillsPathClick, handleImportSkill, handleDeleteSkill,
    handleSaveGeneratedSkill,
    // memory
    autoSaveHistory, setAutoSaveHistory,
    contextRounds, setContextRounds,
    activeSessionId, setSessions,
    currentAvatarName,
    // cron
    cronTasks,
    handleToggleCronTask, handleDeleteCronTask, handleClearCronLogs, handleAddCronTask, handleEditCronTask,
    selectedTaskForLog, setSelectedTaskForLog,
    selectedCronLogDetails, setSelectedCronLogDetails,
    // mcp
    mcpConfig, saveMcpConfig, showToast
  } = store

  const [convMessages, setConvMessages] = React.useState<any[]>([])
  const [isLoadingConversations, setIsLoadingConversations] = React.useState(false)
  const [archiveStats, setArchiveStats] = React.useState({ conversationRounds: 0, companionDays: 0 })
  const [archiveActivityByDate, setArchiveActivityByDate] = React.useState<Record<string, number>>({})
  const [archiveView, setArchiveView] = React.useState<'intimacy' | 'growth'>('intimacy')

  // ── Skill 生成本地状态 ──
  const [skillGenOpen, setSkillGenOpen] = React.useState(false)

  const onSkillGenOpen = React.useCallback(() => {
    setSkillGenOpen(true)
  }, [])

  const onSkillGenClose = React.useCallback(() => {
    setSkillGenOpen(false)
  }, [])

  // ── Skill 生成 handler 包装 ──
  const onSkillGenSave = React.useCallback(async (name: string, content: string) => {
    await handleSaveGeneratedSkill(name, content)
  }, [handleSaveGeneratedSkill])

  // ── 四表管理状态 ──
  const TABLE_NAMES: Record<string, string> = {
    long_term_memory: '长期记忆',
    user_profile: '人物画像',
    user_insight: '用户启发',
    llm_growth: 'LLM成长'
  }
  const TABLE_ICONS: Record<string, React.ReactNode> = {
    long_term_memory: <Database size={16} strokeWidth={2} aria-hidden="true" />,
    user_profile: <UserRound size={16} strokeWidth={2} aria-hidden="true" />,
    user_insight: <Lightbulb size={16} strokeWidth={2} aria-hidden="true" />,
    llm_growth: <TrendingUp size={16} strokeWidth={2} aria-hidden="true" />
  }
  const TABLE_FIELDS: Record<string, { label: string; key: string; type: string; placeholder: string }[]> = {
    long_term_memory: [
      { label: '内容', key: 'content', type: 'textarea', placeholder: '记忆内容...' },
      { label: '重要性 (0-1)', key: 'importance', type: 'number', placeholder: '0.5' },
      { label: '情感标签', key: 'emotion', type: 'text', placeholder: 'neutral / happy / sad / anxious...' },
      { label: '角色', key: 'role', type: 'text', placeholder: 'user / assistant / manual' }
    ],
    user_profile: [
      { label: '分类 (category)', key: 'category', type: 'text', placeholder: '如: profile, preference...' },
      { label: '属性名 (prop_key)', key: 'propKey', type: 'text', placeholder: '如: summary, name...' },
      { label: '属性值 (prop_value)', key: 'propValue', type: 'textarea', placeholder: '属性值...' }
    ],
    user_insight: [
      { label: '启发内容', key: 'content', type: 'textarea', placeholder: '用户启发内容...' },
      { label: '触发上下文', key: 'context', type: 'textarea', placeholder: '记录这条启发的对话背景...' }
    ],
    llm_growth: [
      { label: '分类', key: 'category', type: 'text', placeholder: '如: skill, knowledge...' },
      { label: '成长内容', key: 'content', type: 'textarea', placeholder: 'LLM成长记录...' }
    ]
  }
  const [activeTable, setActiveTable] = React.useState<string>('long_term_memory')
  const [tableRows, setTableRows] = React.useState<any[]>([])
  const [tableStats, setTableStats] = React.useState<Record<string, number>>({})
  const [editingRow, setEditingRow] = React.useState<{ table: string; id: string; data: Record<string, string> } | null>(null)
  const [showAddModal, setShowAddModal] = React.useState(false)
  const [newRowData, setNewRowData] = React.useState<Record<string, string>>({})
  const [tableSearch, setTableSearch] = React.useState('')
  const [tablePage, setTablePage] = React.useState(1)
  const [tableTotal, setTableTotal] = React.useState(0)
  const [tableTotalPages, setTableTotalPages] = React.useState(1)
  const TABLE_LIMIT = 100
  const memoryView = archiveMode || agentSubTab === 'memory'

  const loadConversations = async () => {
    setIsLoadingConversations(true)
    try {
      const data = await window.api.fetchConversations()
      setConvMessages(data.messages || [])
      if (data.status === 'ok') {
        setArchiveStats({
          conversationRounds: Number(data.conversationRounds) || 0,
          companionDays: Number(data.companionDays) || 0
        })
        setArchiveActivityByDate(data.activityByDate || {})
      }
    } catch {}
    finally {
      setIsLoadingConversations(false)
    }
  }

  const loadTableStats = async () => {
    try {
      const data = await window.api.memoryTables()
      if (data.status === 'ok') setTableStats(data.tables || {})
    } catch {}
  }

  const loadTableData = async (table: string, page: number, search?: string) => {
    try {
      const data = await window.api.memoryTableList(table, page, TABLE_LIMIT, search || undefined)
      if (data.status !== 'ok') {
        setTableRows([])
        setTableTotal(0)
        setTableTotalPages(1)
        showToast(data.message || '读取记忆数据失败', 'error')
        return
      }
      setTableRows(data.rows || [])
      setTableTotal(data.total || 0)
      setTableTotalPages(data.totalPages || 1)
    } catch {
      setTableRows([])
      setTableTotal(0)
      setTableTotalPages(1)
      showToast('读取记忆数据失败', 'error')
    }
  }

  // 切换表/搜索时重置页码
  const switchTable = (t: string) => { setActiveTable(t); setTableSearch(''); setTablePage(1) }
  const doSearch = (s: string) => { setTableSearch(s); setTablePage(1) }

  React.useEffect(() => {
    if (archiveMode) {
      loadTableStats()
      loadTableData(activeTable, tablePage, tableSearch)
      void loadConversations()
    }
  }, [archiveMode, activeTable, tablePage, tableSearch])

  const [mcpNewName, setMcpNewName] = React.useState('')
  const [mcpNewUrl, setMcpNewUrl] = React.useState('')
  const [mcpNewApiKey, setMcpNewApiKey] = React.useState('')
  const [mcpNewType, setMcpNewType] = React.useState<'stream' | 'sse' | 'auto'>('stream')
  const [showAddMcpForm, setShowAddMcpForm] = React.useState(false)
  const [showPaddleTokenModal, setShowPaddleTokenModal] = React.useState(false)
  const [paddleToken, setPaddleToken] = React.useState('')
  const [paddleTokenConfigured, setPaddleTokenConfigured] = React.useState(false)

  // 编辑弹窗相关状态
  const [showEditModal, setShowEditModal] = React.useState(false)
  const [editingServer, setEditingServer] = React.useState<any>(null)
  const [editName, setEditName] = React.useState('')
  const [editUrl, setEditUrl] = React.useState('')
  const [editApiKey, setEditApiKey] = React.useState('')
  const [editClearApiKey, setEditClearApiKey] = React.useState(false)
  const [editType, setEditType] = React.useState<'stream' | 'sse' | 'auto'>('stream')

  React.useEffect(() => {
    if (agentSubTab !== 'mcp') return
    window.api.getPaddleOcrTokenStatus()
      .then(status => setPaddleTokenConfigured(Boolean(status.configured)))
      .catch(() => setPaddleTokenConfigured(false))
  }, [agentSubTab])

  // MCP 测试结果弹框状态
  const [showTestResultModal, setShowTestResultModal] = React.useState(false)
  const [testResultData, setTestResultData] = React.useState<any>(null)
  const [testResultServerName, setTestResultServerName] = React.useState('')

  // 定时任务编辑/新增状态
  const [showCronModal, setShowCronModal] = React.useState(false)
  const [editingCron, setEditingCron] = React.useState<any>(null)
  const [cronName, setCronName] = React.useState('')
  const [cronHours, setCronHours] = React.useState<number>(0)
  const [cronMinutes, setCronMinutes] = React.useState<number>(1)
  const [cronSeconds, setCronSeconds] = React.useState<number>(0)
  const [cronAction, setCronAction] = React.useState('')
  const [openDropdownId, setOpenDropdownId] = React.useState<string | null>(null)
  const [contextRoundsMenuOpen, setContextRoundsMenuOpen] = React.useState(false)
  const [contextRoundsHighlight, setContextRoundsHighlight] = React.useState(0)

  // 点击空白处关闭下拉菜单
  React.useEffect(() => {
    const handleClick = () => {
      setOpenDropdownId(null)
      setContextRoundsMenuOpen(false)
    }
    window.addEventListener('click', handleClick)
    return () => window.removeEventListener('click', handleClick)
  }, [])

  const selectContextRounds = (value: number) => {
    setContextRounds(value)
    localStorage.setItem('mindpet_context_rounds', String(value))
    setContextRoundsMenuOpen(false)
  }

  const formatInterval = (totalSeconds: number) => {
    const d = Math.floor(totalSeconds / (3600 * 24))
    const h = Math.floor((totalSeconds % (3600 * 24)) / 3600)
    const m = Math.floor((totalSeconds % 3600) / 60)
    const s = totalSeconds % 60
    let res = ''
    if (d > 0) res += `${d}天 `
    if (h > 0) res += `${h}小时 `
    if (m > 0) res += `${m}分钟 `
    if (s > 0 || (d === 0 && h === 0 && m === 0)) res += `${s}秒`
    return res.trim()
  }

  const archiveConversationRounds = archiveStats.conversationRounds
  const archiveCompanionDays = archiveStats.companionDays
  const archiveScore = Math.floor(archiveConversationRounds / 5)
    + (tableStats.user_profile || 0) * 10
    + (tableStats.user_insight || 0) * 10
    + (tableStats.llm_growth || 0) * 20
    + archiveCompanionDays * 5
  const animatedArchiveScore = useAnimatedNumber(archiveScore)
  const animatedConversationRounds = useAnimatedNumber(archiveConversationRounds)
  const animatedCompanionDays = useAnimatedNumber(archiveCompanionDays)
  const animatedProfileCount = useAnimatedNumber(tableStats.user_profile || 0)
  const animatedInsightCount = useAnimatedNumber(tableStats.user_insight || 0)
  const animatedGrowthCount = useAnimatedNumber(tableStats.llm_growth || 0)
  const heatmapCells = React.useMemo(() => {
    const today = new Date()
    today.setHours(0, 0, 0, 0)
    const end = new Date(today)
    end.setDate(end.getDate() + (6 - end.getDay()))
    const start = new Date(end)
    start.setDate(start.getDate() - 370)
    const cells: { key: string; date: Date; count: number; level: number }[] = []
    for (let offset = 0; offset < 371; offset += 1) {
      const date = new Date(start)
      date.setDate(start.getDate() + offset)
      const key = toLocalDateKey(date)
      cells.push({ key, date, count: Number(archiveActivityByDate[key]) || 0, level: 0 })
    }
    const maxCount = Math.max(...cells.map(cell => cell.count), 0)
    return cells.map(cell => ({
      ...cell,
      level: cell.count === 0 || maxCount === 0 ? 0 : Math.min(4, Math.max(1, Math.ceil(cell.count / (maxCount / 4))))
    }))
  }, [archiveActivityByDate])
  const recentWeekCount = React.useMemo(() => {
    const today = new Date()
    today.setHours(0, 0, 0, 0)
    let sum = 0
    for (let i = 0; i < 7; i++) {
      const d = new Date(today)
      d.setDate(d.getDate() - i)
      sum += Number(archiveActivityByDate[toLocalDateKey(d)]) || 0
    }
    return sum
  }, [archiveActivityByDate])

  const companionTrajectory = React.useMemo(() => {
    const weekCounts = Array.from({ length: Math.ceil(heatmapCells.length / 7) }, (_, week) => (
      heatmapCells.slice(week * 7, week * 7 + 7).reduce((total, cell) => total + cell.count, 0)
    ))
    const maxCount = Math.max(...weekCounts, 0)
    const points = weekCounts.map((count, index) => {
      const x = 4 + (index / Math.max(1, weekCounts.length - 1)) * 412
      const intensity = maxCount === 0 ? 0 : Math.sqrt(count / maxCount)
      return { x, y: 58 - intensity * 36 }
    })
    const path = points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x.toFixed(2)} ${point.y.toFixed(2)}`).join(' ')
    return {
      path,
      end: points[points.length - 1] || { x: 416, y: 58 },
      recentCount: recentWeekCount
    }
  }, [heatmapCells, recentWeekCount])
  const heatmapMonthLabels = React.useMemo(() => {
    if (heatmapCells.length === 0) return []
    const firstDate = heatmapCells[0].date
    return Array.from({ length: 53 }, (_, column) => {
      const date = new Date(firstDate)
      date.setDate(firstDate.getDate() + column * 7)
      const previous = column > 0 ? new Date(firstDate) : null
      if (previous) previous.setDate(firstDate.getDate() + (column - 1) * 7)
      if (column === 0 || date.getMonth() !== previous?.getMonth()) {
        return { label: `${date.getMonth() + 1}月`, column: column + 1 }
      }
      return null
    }).filter((label): label is { label: string; column: number } => Boolean(label))
  }, [heatmapCells])

  return (
    <div className={`agent-page-root${archiveMode ? ' companion-archive-page' : ''}`} style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      {archiveMode && (
        <div className="companion-archive-summary">
          <div className="companion-archive-summary-head">
            <div className="companion-archive-heading">
              <h2>MindPet 和你的共同记录</h2>
              <p>MindPet 已陪伴你 {animatedCompanionDays} 天，聊了 {animatedConversationRounds} 次，我们每天都在相遇</p>
            </div>
            <div className="companion-archive-trend" key={archiveView}>
              <div className="companion-archive-trend-head">
                <span>近一年陪伴轨迹</span>
                <span>{companionTrajectory.recentCount} 次 / 最近一周</span>
              </div>
              <svg className="companion-archive-trend-chart" viewBox="0 0 420 72" role="img" aria-label={`近一年陪伴轨迹，共 ${archiveConversationRounds} 次对话`}>
                <line className="companion-archive-trend-baseline" x1="4" y1="58" x2="416" y2="58" />
                <path className="companion-archive-trend-line" pathLength="1" d={companionTrajectory.path} />
                <circle className="companion-archive-trend-point" cx={companionTrajectory.end.x} cy={companionTrajectory.end.y} r="3.5" />
              </svg>
            </div>
            <div className="companion-archive-score">
              <strong>{animatedArchiveScore}</strong>
              <span>陪伴分</span>
            </div>
          </div>
          <div className="companion-archive-tabs" role="tablist" aria-label="陪伴档案视图">
            <button className={archiveView === 'intimacy' ? 'is-active' : ''} type="button" role="tab" aria-selected={archiveView === 'intimacy'} onClick={() => setArchiveView('intimacy')}>亲密度</button>
            <button className={archiveView === 'growth' ? 'is-active' : ''} type="button" role="tab" aria-selected={archiveView === 'growth'} onClick={() => setArchiveView('growth')}>成长记录</button>
          </div>
          <p className="companion-archive-intro">{archiveView === 'intimacy' ? '聊天或安排任务越多，亲密度越高' : '新的画像、启发和成长记录，会让 MindPet 更了解你'}</p>
          <div className="companion-archive-metrics">
            <div><strong>{animatedConversationRounds}</strong><span>完整对话轮数</span></div>
            <div><strong>{animatedCompanionDays}</strong><span>实际陪伴天数</span></div>
            <div><strong>{animatedProfileCount}</strong><span>画像属性 · 10 分/条</span></div>
            <div><strong>{animatedInsightCount}</strong><span>用户启发 · 10 分/条</span></div>
            <div><strong>{animatedGrowthCount}</strong><span>LLM 成长 · 20 分/条</span></div>
          </div>
          <div className="companion-archive-heatmap">
            <div className="companion-archive-heatmap-head">
              <div>
                <h3><CalendarDays size={15} aria-hidden="true" /> {archiveView === 'intimacy' ? '近一年陪伴热力' : '近一年记录活跃度'}</h3>
                <p>颜色越深，代表当天留下的对话记录越多。</p>
              </div>
              <span>{archiveView === 'intimacy' ? `${archiveCompanionDays} 天有对话` : `${(tableStats.user_profile || 0) + (tableStats.user_insight || 0) + (tableStats.llm_growth || 0)} 条成长记录`}</span>
            </div>
            <div className="companion-archive-heatmap-body">
              <div className="companion-archive-heatmap-weekdays" aria-hidden="true">
                <span>日</span><span>一</span><span>二</span><span>三</span><span>四</span><span>五</span><span>六</span>
              </div>
              <div className="companion-archive-heatmap-grid-wrap">
                <div className="companion-archive-heatmap-months" aria-hidden="true">
                  {heatmapMonthLabels.map(label => <span key={label.column} style={{ gridColumn: label.column }}>{label.label}</span>)}
                </div>
                <div className="companion-archive-heatmap-grid" role="grid" aria-label="近一年每日对话记录热力图">
                  {heatmapCells.map((cell, index) => (
                    <div
                      key={cell.key}
                      className={`companion-archive-heatmap-cell level-${cell.level}`}
                      style={{ animationDelay: `${Math.min(index, 80) * 7}ms` }}
                      role="gridcell"
                      aria-label={`${cell.key}，${cell.count} 条对话记录`}
                      title={`${cell.key} · ${cell.count} 条对话记录`}
                    />
                  ))}
                </div>
              </div>
            </div>
            <div className="companion-archive-heatmap-legend" aria-hidden="true">
              <span>少</span>
              <i className="level-0" /><i className="level-1" /><i className="level-2" /><i className="level-3" /><i className="level-4" />
              <span>多</span>
            </div>
          </div>
        </div>
      )}
      {/* Sub Nav */}
      {!archiveMode && <div className="sub-tab-nav">
        <div className={`sub-tab-item ${agentSubTab === 'skills' ? 'active' : ''}`} onClick={() => setAgentSubTab('skills')}>
          技能加入
        </div>
        <div className={`sub-tab-item ${agentSubTab === 'memory' ? 'active' : ''}`} onClick={() => setAgentSubTab('memory')}>
          记忆控制
        </div>
        <div className={`sub-tab-item ${agentSubTab === 'cron' ? 'active' : ''}`} onClick={() => setAgentSubTab('cron')}>
          定时任务
        </div>
        <div className={`sub-tab-item ${agentSubTab === 'mcp' ? 'active' : ''}`} onClick={() => setAgentSubTab('mcp')}>
          MCP 服务
        </div>
      </div>}

      {/* Sub Panel */}
      <div className="sub-content-panel">
        {/* ── 技能加入 ── */}
        {!archiveMode && agentSubTab === 'skills' && (
          <div>
            <div className="skills-action-row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
              <span
                className="storage-path-display"
                style={{ flex: 1, marginRight: '16px', border: '1px solid var(--border-card)', cursor: 'pointer' }}
                onClick={handleSkillsPathClick}
                title="点击选择新的存放路径"
              >
                <FolderOpen size={16} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                存放路径: {skillsPath || '正在加载技能目录...'}
              </span>
              <div className="skills-action-btns">
                <button className="skill-btn skill-btn-ghost" onClick={() => window.api.openSkillsFolder()}>
                  <FolderOpen size={15} strokeWidth={2} aria-hidden="true" />
                  打开目录
                </button>
                <button className="skill-btn skill-btn-magic" onClick={onSkillGenOpen}>
                  <Sparkles size={15} strokeWidth={2} aria-hidden="true" />
                  AI 生成技能
                </button>
                <button className="skill-btn skill-btn-fill" onClick={handleImportSkill}>
                  <Upload size={15} strokeWidth={2} aria-hidden="true" />
                  导入技能包 (.zip)
                </button>
              </div>
            </div>

            <div className="skills-table-wrapper">
              {skillsList.length > 0 ? (
                <table className="skills-table">
                  <thead>
                    <tr>
                      <th>技能包名称</th>
                      <th>文件格式</th>
                      <th>文件大小</th>
                      <th>导入日期</th>
                      <th style={{ textAlign: 'right' }}>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {skillsList.map(skill => (
                      <tr key={skill.name}>
                        <td style={{ fontWeight: 600 }}>{skill.name}</td>
                        <td><span className="skill-zip-badge">ZIP</span></td>
                        <td>{formatBytes(skill.size)}</td>
                        <td>{new Date(skill.mtime).toLocaleString()}</td>
                        <td style={{ textAlign: 'right' }}>
                          <button className="delete-btn" onClick={() => handleDeleteSkill(skill.name)}>
                            <Trash2 size={14} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                            卸载
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <div className="empty-state">
                  本地没有已加载的 ZIP 技能包。请点击"导入技能包"选择 ZIP 压缩文件，或使用 AI 生成。
                </div>
              )}
            </div>

            {/* ── AI 生成 Skill 模态框 ── */}
            {skillGenOpen && (
              <SkillGenerateModal
                onSave={onSkillGenSave}
                onClose={onSkillGenClose}
              />
            )}
          </div>
        )}

        {/* ── 记忆控制 ── */}
        {memoryView && (
          <div className={`settings-sub-panel settings-panel-card memory-management-panel ${archiveMode ? 'archive-mode' : 'memory-settings-mode'}`}>
            {!archiveMode && <>
            <div className="settings-section-title">会话持久化控制</div>
            <div className="settings-row">
              <div className="settings-row-info">
                <span className="settings-row-title">自动保存聊天历史</span>
                <span className="settings-row-desc">关闭后，关闭应用后会话记录将被自动清除。</span>
              </div>
              <input
                type="checkbox"
                id="autosave-switch"
                className="switch-checkbox"
                checked={autoSaveHistory}
                style={{ transform: 'scale(1.2)', cursor: 'pointer' }}
                onChange={e => {
                  setAutoSaveHistory(e.target.checked)
                  localStorage.setItem('mindpet_autosave', String(e.target.checked))
                }}
              />
            </div>

            <div className="settings-row">
              <div className="settings-row-info">
                <span className="settings-row-title">单次会话记忆上下文轮数</span>
                <span className="settings-row-desc">发送给大模型的前置聊天深度，当前轮数：{contextRounds} 轮对答。</span>
              </div>
              <div className="context-rounds-select">
                <button
                  type="button"
                  className="context-rounds-trigger"
                  aria-haspopup="listbox"
                  aria-expanded={contextRoundsMenuOpen}
                  onClick={event => {
                    event.stopPropagation()
                    const currentIndex = CONTEXT_ROUND_OPTIONS.indexOf(contextRounds as typeof CONTEXT_ROUND_OPTIONS[number])
                    setContextRoundsHighlight(currentIndex >= 0 ? currentIndex : 0)
                    setContextRoundsMenuOpen(open => !open)
                  }}
                  onKeyDown={event => {
                    const currentIndex = CONTEXT_ROUND_OPTIONS.indexOf(contextRounds as typeof CONTEXT_ROUND_OPTIONS[number])
                    if (event.key === 'Escape') {
                      setContextRoundsMenuOpen(false)
                    } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                      event.preventDefault()
                      const startIndex = contextRoundsMenuOpen ? contextRoundsHighlight : (currentIndex >= 0 ? currentIndex : 0)
                      const direction = event.key === 'ArrowDown' ? 1 : -1
                      setContextRoundsHighlight((startIndex + direction + CONTEXT_ROUND_OPTIONS.length) % CONTEXT_ROUND_OPTIONS.length)
                      setContextRoundsMenuOpen(true)
                    } else if (event.key === 'Enter' && contextRoundsMenuOpen) {
                      event.preventDefault()
                      selectContextRounds(CONTEXT_ROUND_OPTIONS[contextRoundsHighlight])
                    }
                  }}
                >
                  <span>{contextRounds} 轮</span>
                  <ChevronDown size={15} strokeWidth={2} aria-hidden="true" />
                </button>
                {contextRoundsMenuOpen && (
                  <div
                    className="context-rounds-menu"
                    role="listbox"
                    aria-label="记忆上下文轮数"
                    onClick={event => event.stopPropagation()}
                  >
                    {CONTEXT_ROUND_OPTIONS.map((rounds, index) => (
                      <button
                        key={rounds}
                        type="button"
                        role="option"
                        aria-selected={contextRounds === rounds}
                        className={`context-rounds-option ${contextRounds === rounds ? 'is-selected' : ''} ${contextRoundsHighlight === index ? 'is-highlighted' : ''}`}
                        onMouseEnter={() => setContextRoundsHighlight(index)}
                        onClick={() => selectContextRounds(rounds)}
                      >
                        <span>{rounds} 轮</span>
                        {contextRounds === rounds && <CheckCircle2 size={15} strokeWidth={2.2} aria-hidden="true" />}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            </div>

            <div className="settings-section-title">本地存储清空</div>

            <div className="settings-row">
              <div className="settings-row-info">
                <span className="settings-row-title">清空当前会话缓存</span>
                <span className="settings-row-desc">清空当前选中会话的历史消息。</span>
              </div>
              <button
                className="delete-btn"
                style={{ border: '1px solid rgba(248,113,113,0.3)', padding: '6px 12px', borderRadius: '6px' }}
                onClick={() => {
                  if (confirm('确认清空当前会话历史吗？')) {
                    setSessions(prev => prev.map(s => {
                      if (s.id === activeSessionId) {
                        return {
                          ...s,
                          messages: [{ id: 1, sender: 'agent', text: '本会话记录已清空。', time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) }]
                        }
                      }
                      return s
                    }))
                  }
                }}
              >
                <Siren size={15} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                清除当前
              </button>
            </div>

            <div className="settings-row">
              <div className="settings-row-info">
                <span className="settings-row-title">清空所有会话记录缓存</span>
                <span className="settings-row-desc">删除所有会话并还原为默认的空白会话。</span>
              </div>
              <button
                className="delete-btn"
                style={{ border: '1px solid rgba(248,113,113,0.3)', padding: '6px 12px', borderRadius: '6px' }}
                onClick={() => {
                  if (confirm('确认清除所有会话记录吗？')) {
                    const defaultSess = [{
                      id: 'agent:main:dashboard:default',
                      name: '新会话',
                      time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
                      messages: [{ id: 1, sender: 'agent', text: `会话记录已彻底清空。${currentAvatarName} 核心记忆已重置。`, time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) }]
                    }]
                    setSessions(defaultSess)
                    store.setActiveSessionId('agent:main:dashboard:default')
                  }
                }}
              >
                <Siren size={15} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                清空所有
              </button>
            </div>

            {/* ── 四表统计概览 ── */}
            </>}
            <div className="settings-section-title memory-archive-only" style={{ marginTop: '8px' }}>
              数据库表概览
              <button className="btn-secondary memory-refresh-button" style={{ marginLeft: 10, padding: '2px 8px', fontSize: 11 }}
                onClick={loadTableStats}>刷新</button>
            </div>
            <div className="memory-table-overview memory-archive-only" style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
              {Object.entries(TABLE_NAMES).map(([key, label]) => (
                <div key={key} className={`memory-table-card memory-table-card-${key} ${key === 'long_term_memory' ? 'memory-table-card-primary' : ''} ${activeTable === key ? 'active' : ''}`} style={{
                  flex: '1 1 calc(25% - 6px)', minWidth: 120,
                  padding: '8px 10px', borderRadius: 6,
                  border: activeTable === key ? '2px solid var(--ds-color-23336238326636)' : '1px solid var(--border-card)',
                  background: activeTable === key ? 'var(--ds-color-726762612835392c)' : 'var(--bg-card-sub, rgba(128,128,128,0.02))',
                  cursor: 'pointer', textAlign: 'center', transition: 'all 0.2s'
                }} onClick={() => switchTable(key)}>
                  <div className="memory-table-card-label" style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-primary)' }}>
                    <span className="memory-table-icon">{TABLE_ICONS[key]}</span>
                    <span>{label}</span>
                  </div>
                  <div className="memory-table-card-count" style={{ fontSize: 18, fontWeight: 700, color: 'var(--ds-color-23336238326636)', marginTop: 2 }}>
                    {tableStats[key] ?? '-'}
                  </div>
                </div>
              ))}
            </div>

            {/* ── 活跃表数据列表 ── */}
            <div className="memory-archive-only" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6, gap: 8 }}>
              <span style={{ fontSize: 13, fontWeight: 600, whiteSpace: 'nowrap' }}>
                {TABLE_NAMES[activeTable]}（共 {tableTotal} 条，本页 {tableRows.length} 条）
              </span>
              <div style={{ display: 'flex', gap: 6, alignItems: 'center', flex: 1, justifyContent: 'flex-end' }}>
                <div style={{ position: 'relative', flex: '0 1 200px' }}>
                  <Search size={13} style={{ position: 'absolute', left: 6, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)', pointerEvents: 'none' }} />
                  <input
                    type="text"
                    className="mcp-input-fancy"
                    placeholder="搜索关键词..."
                    value={tableSearch}
                    onChange={e => doSearch(e.target.value)}
                    style={{ paddingLeft: 24, paddingRight: 8, fontSize: 11, height: 26 }}
                  />
                </div>
                <button className="btn-secondary" style={{ padding: '2px 8px', fontSize: 11, whiteSpace: 'nowrap' }}
                  onClick={() => loadTableData(activeTable, tablePage, tableSearch)}><RefreshCw size={13} /> 刷新</button>
              </div>
            </div>

            <div className="memory-table-list memory-archive-only" style={{ maxHeight: 280, overflowY: 'auto', border: '1px solid var(--border-card)', borderRadius: 6, marginBottom: 12 }}>
              {tableRows.length === 0 ? (
                <div style={{ padding: 20, textAlign: 'center', color: 'var(--text-secondary)', fontSize: 12 }}>暂无数据</div>
              ) : (
                tableRows.map((row: any, i: number) => {
                  const rowId = activeTable === 'user_profile'
                    ? `${row.user_id || ''}||${row.category || ''}||${row.prop_key || ''}`
                    : (row.id || '')
                  const userBadge = row.user_id
                    ? <span style={{ fontSize: 8, color: 'var(--text-muted)', marginRight: 4, fontFamily: 'monospace' }}>[{row.user_id.substring(0, 12)}{(row.user_id||'').length > 12 ? '…' : ''}]</span>
                    : null
                  const roleBadge = row.role && activeTable === 'long_term_memory'
                    ? <span style={{
                        fontSize: 9, fontWeight: 700, padding: '1px 5px', borderRadius: 3, marginRight: 4,
                        background: row.role === 'user' ? 'var(--ds-color-726762612839362c)' : row.role === 'assistant' ? 'var(--ds-color-7267626128323434)' : 'var(--ds-color-7267626128313238)',
                        color: row.role === 'user' ? 'var(--ds-color-23363061356661)' : row.role === 'assistant' ? 'var(--ds-color-23663437326236)' : 'var(--text-secondary)'
                      }}>{row.role}</span> : null
                  const importanceBar = row.importance != null && activeTable === 'long_term_memory'
                    ? <span style={{
                        display: 'inline-block', width: 30, height: 4, borderRadius: 2, marginRight: 4, verticalAlign: 'middle',
                        background: `linear-gradient(90deg, var(--ds-color-23336238326636) ${(row.importance||0)*100}%, var(--ds-color-7267626128313238) ${(row.importance||0)*100}%)`
                      }} title={`重要性: ${(row.importance||0).toFixed(1)}`} /> : null
                  const emotionTag = row.emotion && activeTable === 'long_term_memory'
                    ? <span style={{ fontSize: 9, color: 'var(--text-muted)', marginRight: 4 }}>[{row.emotion}]</span> : null
                  const catBadge = (row.category && (activeTable === 'llm_growth' || activeTable === 'user_profile'))
                    ? <span style={{ fontSize: 9, fontWeight: 600, padding: '1px 5px', borderRadius: 3, marginRight: 4,
                        background: 'rgba(16,185,129,0.12)', color: '#10b981' }}>{row.category}</span> : null

                  let displayContent = ''
                  if (activeTable === 'user_profile') {
                    displayContent = `${row.prop_key || ''}: ${(row.prop_value || '').substring(0, 100)}`
                  } else {
                    displayContent = (row.content || row.insight || '').substring(0, 200)
                  }

                  const date = row.created_at || row.updated_at || ''
                  return (
                    <div key={rowId + '_' + i} className="memory-data-row" style={{
                      padding: '6px 10px', borderBottom: '1px solid var(--border-card)',
                      fontSize: 11, display: 'flex', justifyContent: 'space-between', alignItems: 'center'
                    }}>
                      <div className="memory-data-content" style={{ flex: 1, minWidth: 0 }}>
                        <div className="memory-data-mainline" style={{ color: 'var(--text-primary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', display: 'flex', alignItems: 'center', gap: 2 }}>
                          {userBadge}{catBadge}{importanceBar}{roleBadge}{emotionTag}
                          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{displayContent}</span>
                        </div>
                        {date && <div className="memory-data-meta" style={{ color: 'var(--text-muted)', fontSize: 10, marginTop: 2 }}>{date}</div>}
                      </div>
                      <div className="memory-data-actions" style={{ display: 'flex', gap: 4, flexShrink: 0, marginLeft: 8 }}>
                        <button className="btn-secondary memory-row-action memory-row-edit-button" style={{ padding: '2px 6px', fontSize: 10 }} title="编辑记忆" aria-label="编辑记忆"
                          onClick={() => {
                            const initData: Record<string, string> = {}
                            if (activeTable === 'user_profile') {
                              initData['category'] = row.category || ''
                              initData['propKey'] = row.prop_key || ''
                              initData['propValue'] = row.prop_value || ''
                            } else if (activeTable === 'long_term_memory') {
                              initData['content'] = row.content || ''
                              initData['importance'] = String(row.importance ?? 0.5)
                              initData['emotion'] = row.emotion || 'neutral'
                              initData['role'] = row.role || 'manual'
                            } else if (activeTable === 'user_insight') {
                              initData['content'] = row.content || row.insight || ''
                              initData['context'] = row.context || ''
                            } else {
                              initData['content'] = row.content || row.insight || ''
                              initData['category'] = row.category || ''
                            }
                            setEditingRow({ table: activeTable, id: rowId, data: initData })
                          }}><Pencil size={11} /></button>
                        <button className="delete-btn memory-row-action memory-row-delete-button" style={{ padding: '2px 6px', fontSize: 10 }} title="删除记忆" aria-label="删除记忆"
                          onClick={async () => {
                            if (!confirm('确认删除该记录？')) return
                            await window.api.memoryTableDelete(activeTable, rowId)
                            loadTableData(activeTable, tablePage, tableSearch)
                            loadTableStats()
                          }}><Trash2 size={11} /></button>
                      </div>
                    </div>
                  )
                })
              )}
            </div>

            {/* ── 快捷操作 ── */}
            <div className="memory-archive-only" style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
              <button className="btn-primary" style={{ fontSize: 11, padding: '4px 10px' }}
                onClick={() => {
                  setNewRowData({})
                  setShowAddModal(true)
                }}><Plus size={13} /> 新增{TABLE_NAMES[activeTable]}</button>
              <button className="delete-btn" style={{ fontSize: 11, padding: '4px 10px' }}
                onClick={async () => {
                  if (!confirm('确认清理低价值记忆？')) return
                  await window.api.purifyMemories()
                  setActiveTable('long_term_memory'); setTablePage(1); loadTableData('long_term_memory', 1, tableSearch)
                  loadTableStats()
                }}><Trash2 size={13} /> 净化长期记忆</button>
              <button className="btn-secondary" style={{ fontSize: 11, padding: '4px 10px' }}
                onClick={async () => {
                  const data = await window.api.exportMemoriesText()
                  const t = prompt('编辑记忆文本:', data.text||'')
                  if (t != null) {
                    await window.api.importMemoriesText(t)
                    setActiveTable('long_term_memory'); setTablePage(1); loadTableData('long_term_memory', 1, tableSearch)
                  }
                }}><FileText size={13} /> 导出编辑</button>
            </div>

            {/* ── 分页导航 ── */}
            {tableTotalPages > 1 && (
              <div className="memory-pagination memory-archive-only" style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 6, marginBottom: 12, fontSize: 12 }}>
                <button className="btn-secondary memory-pagination-button" style={{ padding: '2px 8px', fontSize: 11 }}
                  disabled={tablePage <= 1}
                  onClick={() => setTablePage(1)}>« 首页</button>
                <button className="btn-secondary memory-pagination-button" style={{ padding: '2px 8px', fontSize: 11 }}
                  disabled={tablePage <= 1}
                  onClick={() => setTablePage(p => p - 1)}>‹ 上页</button>
                <span className="memory-pagination-status" style={{ color: 'var(--text-secondary)', fontSize: 11, minWidth: 100, textAlign: 'center' }}>
                  第 {tablePage}/{tableTotalPages} 页，共 {tableTotal} 条
                </span>
                <button className="btn-secondary memory-pagination-button" style={{ padding: '2px 8px', fontSize: 11 }}
                  disabled={tablePage >= tableTotalPages}
                  onClick={() => setTablePage(p => p + 1)}>下页 ›</button>
                <button className="btn-secondary memory-pagination-button" style={{ padding: '2px 8px', fontSize: 11 }}
                  disabled={tablePage >= tableTotalPages}
                  onClick={() => setTablePage(tableTotalPages)}>末页 »</button>
              </div>
            )}

            {/* ── 对话历史 ── */}
            <div className="settings-section-title memory-archive-only" style={{ marginTop: 4 }}>
              对话历史 (数据库)
              <button
                className={`btn-secondary memory-refresh-button ${isLoadingConversations ? 'is-refreshing' : ''}`}
                style={{ marginLeft: 10, padding: '2px 8px', fontSize: 11 }}
                disabled={isLoadingConversations}
                aria-busy={isLoadingConversations}
                onClick={() => void loadConversations()}
              >
                <RefreshCw size={12} strokeWidth={2} aria-hidden="true" />
                刷新
              </button>
            </div>
            <div className="memory-conversation-list memory-archive-only" style={{ maxHeight: 180, overflowY: 'auto', border: '1px solid var(--border-card)', borderRadius: 6 }}>
              {convMessages.map((m: any, i: number) => (
                <div key={i} style={{ padding: '4px 10px', borderBottom: '1px solid var(--border-card)', fontSize: 11 }}>
                  <span style={{ color: m.role==='user'?'var(--ds-color-23363061356661)':'var(--ds-color-23663437326236)', fontWeight: 600 }}>[{m.role}]</span>
                  <span style={{ marginLeft: 4, color: 'var(--text-secondary)' }}>{(m.content||'').substring(0, 150)}</span>
                </div>
              ))}
              {convMessages.length === 0 && <div style={{ padding: 12, textAlign: 'center', color: 'var(--text-secondary)', fontSize: 12 }}>暂无对话</div>}
            </div>
          </div>
        )}

        {/* ── 定时任务 ── */}
        {agentSubTab === 'cron' && (
          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
              <div className="settings-section-title" style={{ margin: 0, fontSize: '14px', fontWeight: 600 }}>
                已配置的定时任务 ({cronTasks.length})
              </div>
              <button
                type="button"
                className="btn-primary"
                onClick={() => {
                  setEditingCron(null)
                  setCronName('')
                  setCronHours(0)
                  setCronMinutes(1)
                  setCronSeconds(0)
                  setCronAction('')
                  setShowCronModal(true)
                }}
                style={{ height: '28px', padding: '0 12px', fontSize: '12px', borderRadius: '6px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '4px' }}
              >
                <Plus size={15} strokeWidth={2} aria-hidden="true" />
                新增任务
              </button>
            </div>

            <div className="skills-table-wrapper" style={{ overflow: 'visible' }}>
              <table className="cron-table" style={{ overflow: 'visible' }}>
                <thead>
                  <tr>
                    <th>任务名称</th>
                    <th>执行间隔</th>
                    <th>最近触发时间</th>
                    <th>触发次数</th>
                    <th>状态</th>
                    <th style={{ textAlign: 'right' }}>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {cronTasks.map(task => (
                    <tr key={task.id}>
                      <td style={{ fontWeight: 600 }}>{task.name}</td>
                      <td>{formatInterval(task.interval)}</td>
                      <td>{task.lastTriggered}</td>
                      <td><span className="cron-badge-trigger">{task.triggerCount} 次</span></td>
                      <td>
                        <span style={{ color: task.isActive ? '#10b981' : '#f87171', fontWeight: 'bold' }}>
                          {task.isActive ? '运行中' : '已暂停'}
                        </span>
                      </td>
                      <td style={{ textAlign: 'right', position: 'relative' }}>
                        <button
                          type="button"
                          className="btn-secondary"
                          style={{ padding: '4px 8px', fontSize: '11px', marginRight: '8px' }}
                          onClick={() => {
                            const latestTask = cronTasks.find(t => t.id === task.id)
                            setSelectedTaskForLog(latestTask || task)
                          }}
                        >
                          <ClipboardList size={14} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                          日志
                        </button>
                        {!task.isSystem && (
                          <button
                            type="button"
                            className="btn-secondary"
                            style={{ padding: '4px 8px', fontSize: '11px' }}
                            onClick={(e) => {
                              e.stopPropagation()
                              setOpenDropdownId(openDropdownId === task.id ? null : task.id)
                            }}
                          >
                            <Settings2 size={14} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                            操作
                            <ChevronDown size={13} strokeWidth={2} className="ui-icon-trailing" aria-hidden="true" />
                          </button>
                        )}

                        {openDropdownId === task.id && (
                          <div
                            style={{
                              position: 'absolute',
                              top: '100%',
                              right: '0',
                              marginTop: '4px',
                              background: 'var(--bg-card)',
                              border: '1px solid var(--border-card)',
                              borderRadius: '6px',
                              boxShadow: '0 4px 12px rgba(0,0,0,0.1)',
                              zIndex: 10,
                              display: 'flex',
                              flexDirection: 'column',
                              minWidth: '100px',
                              overflow: 'hidden'
                            }}
                            onClick={(e) => e.stopPropagation()}
                          >
                            <div
                              style={{ padding: '8px 12px', cursor: 'pointer', fontSize: '12px', textAlign: 'left', display: 'flex', gap: '8px', alignItems: 'center' }}
                              onMouseEnter={(e) => e.currentTarget.style.backgroundColor = 'var(--bg-app)'}
                              onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
                              onClick={() => {
                                setEditingCron(task)
                                setCronName(task.name)
                                setCronHours(Math.floor(task.interval / 3600))
                                setCronMinutes(Math.floor((task.interval % 3600) / 60))
                                setCronSeconds(task.interval % 60)
                                setCronAction(task.action || '')
                                setShowCronModal(true)
                                setOpenDropdownId(null)
                              }}
                            >
                              <Pencil size={14} strokeWidth={2} aria-hidden="true" />
                              编辑
                            </div>
                            <div
                              style={{ padding: '8px 12px', cursor: 'pointer', fontSize: '12px', textAlign: 'left', display: 'flex', gap: '8px', alignItems: 'center' }}
                              onMouseEnter={(e) => e.currentTarget.style.backgroundColor = 'var(--bg-app)'}
                              onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
                              onClick={() => {
                                handleToggleCronTask(task.id)
                                setOpenDropdownId(null)
                              }}
                            >
                              {task.isActive
                                ? <><Pause size={14} strokeWidth={2} aria-hidden="true" />暂停</>
                                : <><Play size={14} strokeWidth={2} aria-hidden="true" />启动</>}
                            </div>
                            {task.name !== '系统画像提纯与经验沉淀' && (
                              <div
                                style={{ padding: '8px 12px', cursor: 'pointer', fontSize: '12px', textAlign: 'left', color: '#ef4444', borderTop: '1px solid var(--border-card)', display: 'flex', gap: '8px', alignItems: 'center' }}
                                onMouseEnter={(e) => e.currentTarget.style.backgroundColor = 'var(--bg-app)'}
                                onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
                                onClick={() => {
                                  handleDeleteCronTask(task.id)
                                  setOpenDropdownId(null)
                                }}
                              >
                                <Trash2 size={14} strokeWidth={2} aria-hidden="true" />
                                移除
                              </div>
                            )}
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* 定时任务日志详情 Modal */}
            {selectedTaskForLog && (
              <div className="cron-modal-overlay" style={{
                position: 'fixed',
                top: 0,
                left: 0,
                width: '100%',
                height: '100%',
                backgroundColor: 'rgba(0, 0, 0, 0.5)',
                backdropFilter: 'blur(8px)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                zIndex: 1000
              }}>
                <div className="cron-modal-content" style={{
                  width: '560px',
                  maxHeight: '80%',
                  background: 'var(--bg-card)',
                  border: '1px solid var(--border-card)',
                  borderRadius: '12px',
                  padding: '24px',
                  display: 'flex',
                  flexDirection: 'column',
                  boxShadow: '0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04)'
                }}>
                  {/* Modal Header */}
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px', borderBottom: '1px solid var(--border-card)', paddingBottom: '12px' }}>
                    <h3 style={{ margin: 0, fontSize: '16px', fontWeight: '600', color: 'var(--text-primary)' }}>
                      <Clock3 size={18} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                      定时任务日志: {selectedTaskForLog.name}
                    </h3>
                    <button
                      onClick={() => {
                        setSelectedTaskForLog(null)
                        setSelectedCronLogDetails(null)
                      }}
                      style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer', fontSize: '18px' }}
                    >
                      <X size={18} strokeWidth={2} aria-hidden="true" />
                    </button>
                  </div>

                  {/* Modal Body */}
                  <div style={{ flex: 1, overflowY: 'auto', marginBottom: '20px', minHeight: '200px', maxHeight: '400px' }}>
                    <div style={{ fontSize: '12.5px', color: 'var(--text-secondary)', marginBottom: '12px' }}>
                      <strong>触发周期</strong>：每 {selectedTaskForLog.interval} 秒一次 | <strong>当前累计触发</strong>：{selectedTaskForLog.triggerCount} 次
                      <br />
                      <strong>动作指令</strong>：<code style={{ background: 'var(--bg-app)', padding: '2px 6px', borderRadius: '4px', display: 'inline-block', marginTop: '4px' }}>{selectedTaskForLog.action || '无'}</code>
                    </div>

                    <h4 style={{ margin: '16px 0 8px 0', fontSize: '13px', fontWeight: '600' }}>
                      <FileText size={15} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                      执行历史日志
                    </h4>

                    {selectedTaskForLog.logs && selectedTaskForLog.logs.length > 0 ? (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                        {selectedTaskForLog.logs.map((log: any) => {
                          let statusText = '成功'
                          let StatusIcon = CheckCircle2
                          let borderLeftColor = '#10b981'
                          if (log.status === 'failed') {
                            statusText = '失败'
                            StatusIcon = CircleX
                            borderLeftColor = '#ef4444'
                          } else if (log.status === 'running') {
                            statusText = '执行中'
                            StatusIcon = LoaderCircle
                            borderLeftColor = 'var(--ds-color-23336238326636)'
                          }

                          return (
                            <div key={log.id} style={{
                              padding: '10px 12px',
                              background: 'var(--bg-app)',
                              borderLeft: `1px solid ${borderLeftColor}`,
                              borderRadius: '4px',
                              fontSize: '12px',
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'space-between'
                            }}>
                              <div style={{ flex: 1, marginRight: '16px' }}>
                                <div style={{ display: 'flex', gap: '8px', color: 'var(--text-secondary)', marginBottom: '4px', fontSize: '11px' }}>
                                  <span style={{ fontWeight: '600', display: 'inline-flex', alignItems: 'center' }}>
                                    <StatusIcon size={13} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                                    {statusText}
                                  </span>
                                  <span>•</span>
                                  <span>{log.time}</span>
                                </div>
                                <div style={{ color: 'var(--text-primary)', wordBreak: 'break-all' }}>{log.message}</div>
                              </div>
                              <button
                                type="button"
                                className="btn-secondary"
                                style={{ padding: '4px 8px', fontSize: '11px', flexShrink: 0 }}
                                onClick={() => setSelectedCronLogDetails(log)}
                                disabled={!log.messages || log.messages.length === 0}
                                title={(!log.messages || log.messages.length === 0) ? "该日志未记录详细执行交互" : "查看执行详情"}
                              >
                                <ClipboardList size={14} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                                详情
                              </button>
                            </div>
                          )
                        })}
                      </div>
                    ) : (
                      <div className="empty-state" style={{ padding: '40px 0', fontSize: '12px' }}>
                        暂无触发执行日志。
                      </div>
                    )}
                  </div>

                  {/* Modal Footer */}
                  <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px', borderTop: '1px solid var(--border-card)', paddingTop: '16px' }}>
                    <button
                      className="delete-btn"
                      style={{ border: '1px solid rgba(248,113,113,0.3)', padding: '6px 12px', borderRadius: '6px', fontSize: '12.5px' }}
                      onClick={async () => {
                        if (confirm('确认清空该任务的执行日志吗？')) {
                          await handleClearCronLogs(selectedTaskForLog.id)
                          setSelectedTaskForLog(prev => prev ? { ...prev, logs: [] } : null)
                          setSelectedCronLogDetails(null)
                        }
                      }}
                      disabled={!selectedTaskForLog.logs || selectedTaskForLog.logs.length === 0 || selectedTaskForLog.isSystem}
                    >
                      <Trash2 size={14} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                      清空日志
                    </button>
                    <button
                      className="btn-primary"
                      style={{ padding: '6px 16px', borderRadius: '6px', fontSize: '12.5px' }}
                      onClick={() => {
                        setSelectedTaskForLog(null)
                        setSelectedCronLogDetails(null)
                      }}
                    >
                      关闭
                    </button>
                  </div>
                </div>
              </div>
            )}

            {/* 二级 Modal: 定时任务执行详情 Chat 页面 */}
            {selectedCronLogDetails && (
              <div className="cron-modal-overlay" style={{
                position: 'fixed',
                top: 0,
                left: 0,
                width: '100%',
                height: '100%',
                backgroundColor: 'rgba(0, 0, 0, 0.5)',
                backdropFilter: 'blur(8px)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                zIndex: 1001
              }}>
                <div className="cron-modal-content" style={{
                  width: '650px',
                  height: '80%',
                  maxHeight: '650px',
                  background: 'var(--bg-card)',
                  border: '1px solid var(--border-card)',
                  borderRadius: '12px',
                  padding: '24px',
                  display: 'flex',
                  flexDirection: 'column',
                  boxShadow: '0 25px 50px -12px rgba(0, 0, 0, 0.25)'
                }}>
                  {/* Header */}
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px', borderBottom: '1px solid var(--border-card)', paddingBottom: '12px' }}>
                    <h3 style={{ margin: 0, fontSize: '15px', fontWeight: '600', color: 'var(--text-primary)' }}>
                      <ClipboardList size={17} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                      任务执行详情 ({selectedCronLogDetails.time})
                    </h3>
                    <button
                      onClick={() => setSelectedCronLogDetails(null)}
                      style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer', fontSize: '18px' }}
                    >
                      <X size={18} strokeWidth={2} aria-hidden="true" />
                    </button>
                  </div>

                  {/* Chat Content Body */}
                  <div className="chat-messages-box" style={{ flex: 1, overflowY: 'auto', marginBottom: '20px', paddingRight: '4px', background: 'var(--bg-content)', borderRadius: '8px', padding: '16px' }}>
                    {selectedCronLogDetails.messages && selectedCronLogDetails.messages.map((msg: any) => (
                      <ChatMessageItem
                        key={msg.id}
                        msg={msg}
                        currentAvatarName={currentAvatarName}
                        onPreviewFile={(f) => {
                          store.handlePreviewFile(f)
                          store.setShowFilePanel(true)
                          store.setActiveTab('chat')
                          setSelectedCronLogDetails(null)
                          setSelectedTaskForLog(null)
                        }}
                      />
                    ))}
                  </div>

                  {/* Footer */}
                  <div style={{ display: 'flex', justifyContent: 'flex-end', borderTop: '1px solid var(--border-card)', paddingTop: '16px' }}>
                    <button
                      className="btn-primary"
                      style={{ padding: '6px 20px', borderRadius: '6px', fontSize: '12.5px' }}
                      onClick={() => setSelectedCronLogDetails(null)}
                    >
                      确定
                    </button>
                  </div>
                </div>
              </div>
            )}

            {/* 新增/编辑定时任务 Modal */}
            {showCronModal && (
              <div className="cron-modal-overlay" style={{
                position: 'fixed', top: 0, left: 0, width: '100%', height: '100%',
                backgroundColor: 'rgba(0, 0, 0, 0.5)', backdropFilter: 'blur(8px)',
                display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1002
              }}>
                <div className="mcp-modal-card" onClick={e => e.stopPropagation()} style={{ width: '450px' }}>
                  <div className="mcp-modal-header">
                    <div className="mcp-modal-title">
                      {editingCron
                        ? <Pencil size={17} strokeWidth={2} aria-hidden="true" />
                        : <Plus size={17} strokeWidth={2} aria-hidden="true" />}
                      <span>{editingCron ? '编辑定时任务' : '新增定时任务'}</span>
                    </div>
                    <button className="mcp-modal-close-btn" onClick={() => setShowCronModal(false)} title="关闭"><X size={18} strokeWidth={2} aria-hidden="true" /></button>
                  </div>
                  <div className="mcp-modal-body">
                    <div style={{ marginBottom: '12px' }}>
                      <label className="mcp-form-label">任务名称</label>
                      <input
                        type="text"
                        className="mcp-input-fancy"
                        placeholder="如：定时清理日志、系统状态巡检"
                        value={cronName}
                        onChange={e => setCronName(e.target.value)}
                        disabled={editingCron?.name === '系统画像提纯与经验沉淀'}
                        style={editingCron?.name === '系统画像提纯与经验沉淀' ? { opacity: 0.6, cursor: 'not-allowed' } : {}}
                      />
                    </div>
                    <div style={{ marginBottom: '12px' }}>
                      <label className="mcp-form-label">执行频率</label>
                      <div style={{ display: 'flex', gap: '8px' }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '4px', flex: 1 }}>
                          <input type="number" min="0" className="mcp-input-fancy" placeholder="小时" value={cronHours || ''} onChange={e => setCronHours(Number(e.target.value) || 0)} />
                          <span style={{ fontSize: '12px', color: 'var(--text-secondary)', whiteSpace: 'nowrap' }}>时</span>
                        </div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '4px', flex: 1 }}>
                          <input type="number" min="0" max="59" className="mcp-input-fancy" placeholder="分钟" value={cronMinutes || ''} onChange={e => setCronMinutes(Number(e.target.value) || 0)} />
                          <span style={{ fontSize: '12px', color: 'var(--text-secondary)', whiteSpace: 'nowrap' }}>分</span>
                        </div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '4px', flex: 1 }}>
                          <input type="number" min="0" max="59" className="mcp-input-fancy" placeholder="秒" value={cronSeconds || ''} onChange={e => setCronSeconds(Number(e.target.value) || 0)} />
                          <span style={{ fontSize: '12px', color: 'var(--text-secondary)', whiteSpace: 'nowrap' }}>秒</span>
                        </div>
                      </div>
                    </div>
                    <div>
                      <label className="mcp-form-label">动作指令 / 提示词</label>
                      <textarea
                        className="mcp-input-fancy"
                        style={{ minHeight: '80px', resize: 'vertical', ...(editingCron?.name === '系统画像提纯与经验沉淀' ? { opacity: 0.6, cursor: 'not-allowed' } : {}) }}
                        placeholder="给助手的执行指令，例如：检查当前系统 CPU 状态"
                        value={cronAction}
                        onChange={e => setCronAction(e.target.value)}
                        disabled={editingCron?.name === '系统画像提纯与经验沉淀'}
                      />
                    </div>
                  </div>
                  <div className="mcp-modal-footer">
                    <button
                      type="button"
                      className="btn-secondary"
                      onClick={() => setShowCronModal(false)}
                      style={{ fontSize: '12.5px', padding: '6px 14px', borderRadius: '6px', cursor: 'pointer' }}
                    >
                      取消
                    </button>
                    <button
                      type="button"
                      className="btn-primary"
                      onClick={async () => {
                        if (!cronName.trim()) {
                          showToast('请填写任务名称', 'error')
                          return
                        }
                        if (!cronAction.trim()) {
                          showToast('请填写动作指令/提示词', 'error')
                          return
                        }
                        const totalInterval = cronHours * 3600 + cronMinutes * 60 + cronSeconds
                        if (totalInterval < 5) {
                          showToast('执行频率不能少于 5 秒', 'error')
                          return
                        }

                        if (editingCron) {
                          await handleEditCronTask(editingCron.id, {
                            name: cronName.trim(),
                            interval: totalInterval,
                            action: cronAction.trim()
                          })
                        } else {
                          await handleAddCronTask({
                            name: cronName.trim(),
                            interval: totalInterval,
                            action: cronAction.trim(),
                            isActive: true
                          })
                        }
                        setShowCronModal(false)
                      }}
                      style={{ fontSize: '12.5px', padding: '6px 14px', borderRadius: '6px', cursor: 'pointer' }}
                    >
                      保存任务
                    </button>
                  </div>
                </div>
              </div>
            )}
          </div>
        )}

        {/* ── MCP 服务 ── */}
        {agentSubTab === 'mcp' && (
          <div className="settings-sub-panel">
            <div className="form-desc-text" style={{ marginBottom: '12px' }}>
              配置并管理 Model Context Protocol (MCP) 服务列表。大模型及微信助手可自动并发连接并调用列表中处于启用状态的所有工具。
            </div>

            <div style={{ background: 'var(--bg-card-sub, rgba(128,128,128,0.02))', padding: '14px 18px', borderRadius: '8px', border: '1px solid var(--border-color, var(--ds-color-7267626128313238))', marginBottom: '16px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <div style={{ fontSize: '13px', fontWeight: 600, marginBottom: '2px', color: 'var(--text-color-strong)', display: 'flex', alignItems: 'center' }}>
                  <Lightbulb size={15} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                  发现更多外部 MCP 服务
                </div>
                <div style={{ fontSize: '11.5px', color: 'var(--text-muted)' }}>探索由开发者社区提供的丰富工具包</div>
              </div>
              <div style={{ display: 'flex', gap: '10px', alignItems: 'center' }}>
                <a href="https://mcpmarket.cn/" target="_blank" rel="noreferrer" style={{ fontSize: '12.5px', color: 'var(--ds-color-23336238326636)', textDecoration: 'none', fontWeight: 500 }}>
                  <Store size={14} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                  MCP 中文市场
                  <ExternalLink size={12} strokeWidth={2} className="ui-icon-trailing" aria-hidden="true" />
                </a>
                <span style={{ color: 'var(--ds-color-7267626128313238)', fontSize: '12px' }}>|</span>
                <a href="https://www.modelscope.cn/mcp" target="_blank" rel="noreferrer" style={{ fontSize: '12.5px', color: 'var(--ds-color-23336238326636)', textDecoration: 'none', fontWeight: 500 }}>
                  <Network size={14} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                  魔塔
                  <ExternalLink size={12} strokeWidth={2} className="ui-icon-trailing" aria-hidden="true" />
                </a>
              </div>
            </div>

            {/* MCP 服务列表区 */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
              <div className="settings-section-title" style={{ margin: 0, fontSize: '14px', fontWeight: 600 }}>
                已连接的服务列表 ({(mcpConfig?.servers || []).length})
              </div>
              <div style={{ display: 'flex', gap: '8px' }}>
                <button
                  type="button"
                  className="btn-secondary"
                  onClick={() => {
                    setPaddleToken('')
                    setShowPaddleTokenModal(true)
                  }}
                  style={{ height: '28px', padding: '0 12px', fontSize: '12px', borderRadius: '6px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '5px' }}
                >
                  <KeyRound size={14} strokeWidth={2} aria-hidden="true" />
                  {paddleTokenConfigured ? '更换 PaddleOCR Token' : '配置 PaddleOCR Token'}
                </button>
                <button
                  type="button"
                  className="btn-primary"
                  onClick={() => setShowAddMcpForm(true)}
                  style={{ height: '28px', padding: '0 12px', fontSize: '12px', borderRadius: '6px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '4px' }}
                >
                  <Plus size={15} strokeWidth={2} aria-hidden="true" />
                  添加自定义
                </button>
              </div>
            </div>

            <div className="mcp-glass-card">
              {(mcpConfig?.servers || []).length === 0 ? (
                <div style={{ padding: '32px 24px', textAlign: 'center', color: 'var(--text-muted, var(--ds-color-23383838))', fontSize: '13px' }}>
                  <Ghost size={18} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                  暂无已添加的服务，请通过右上角“添加自定义”按钮添加。
                </div>
              ) : (
                <div className="mcp-table-container">
                  <table className="mcp-table">
                    <thead>
                      <tr>
                        <th style={{ width: '150px' }}>服务名称</th>
                        <th>终结点地址 (Endpoint)</th>
                        <th style={{ width: '100px', textAlign: 'center' }}>协议类型</th>
                        <th style={{ width: '100px', textAlign: 'center' }}>鉴权密钥</th>
                        <th style={{ width: '90px', textAlign: 'center' }}>启用状态</th>
                        <th style={{ width: '230px', textAlign: 'center' }}>操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {(mcpConfig.servers).map((server: any) => (
                        <tr key={server.id} className="mcp-table-row">
                          <td style={{ fontWeight: 600, color: 'var(--text-color-strong)' }}>
                            {server.name}
                          </td>
                          <td>
                            <span className="mcp-url-text" title={server.url}>
                              {server.url}
                            </span>
                          </td>
                          <td style={{ textAlign: 'center' }}>
                            <span className={`mcp-badge ${server.type === 'sse' ? 'none' : 'configured'}`}>
                              {server.type === 'stream' ? 'Stream' : server.type === 'sse' ? 'SSE' : server.type === 'auto' ? '自动' : 'Stream'}
                            </span>
                          </td>
                          <td style={{ textAlign: 'center' }}>
                            <span className={`mcp-badge ${server.hasApiKey ? 'configured' : 'none'}`}>
                              {server.hasApiKey ? '已配置' : '无'}
                            </span>
                          </td>
                          <td style={{ textAlign: 'center' }}>
                            <div className="mcp-switch-container">
                              <label className="mcp-switch-label">
                                <input
                                  type="checkbox"
                                  checked={server.enabled}
                                  onChange={e => {
                                    const newServers = mcpConfig.servers.map((s: any) => s.id === server.id ? { ...s, enabled: e.target.checked } : s)
                                    saveMcpConfig({ servers: newServers })
                                  }}
                                />
                                <span className="mcp-switch-slider" />
                              </label>
                            </div>
                          </td>
                          <td>
                            <div className="mcp-btn-action-group">
                              <button
                                type="button"
                                className="mcp-btn-action test"
                                onClick={async () => {
                                  try {
                                    showToast(`正在测试连接 [${server.name}]...`, 'info')
                                    const res = await window.api.testMcpServer({
                                      id: server.id,
                                      url: server.url,
                                      type: server.type || 'stream',
                                      preset: server.preset,
                                      model: server.model
                                    })
                                    setTestResultData(res)
                                    setTestResultServerName(server.name)
                                    setShowTestResultModal(true)
                                  } catch (err: any) {
                                    alert(`测试异常：\n${err.message || err}`)
                                  }
                                }}
                              >
                                <Plug size={14} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                                测试
                              </button>
                              <button
                                type="button"
                                className="mcp-btn-action edit"
                                onClick={() => {
                                  setEditingServer(server)
                                  setEditName(server.name)
                                  setEditUrl(server.url)
                                  setEditApiKey('')
                                  setEditClearApiKey(false)
                                  setEditType(server.type || 'stream')
                                  setShowEditModal(true)
                                }}
                              >
                                <Pencil size={14} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                                编辑
                              </button>
                              <button
                                type="button"
                                className="mcp-btn-action delete"
                                onClick={() => {
                                  if (confirm(`确认要删除 [${server.name}] 服务吗？`)) {
                                    const newServers = mcpConfig.servers.filter((s: any) => s.id !== server.id)
                                    saveMcpConfig({ servers: newServers })
                                    showToast(`已删除 [${server.name}] 服务。`, 'success')
                                  }
                                }}
                              >
                                <Trash2 size={14} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                                删除
                              </button>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      {showPaddleTokenModal && (
        <div className="mcp-modal-overlay">
          <div className="mcp-modal-card" onClick={e => e.stopPropagation()}>
            <div className="mcp-modal-header">
              <div className="mcp-modal-title">
                <KeyRound size={17} strokeWidth={2} aria-hidden="true" />
                <span>{paddleTokenConfigured ? '更换 PaddleOCR Token' : '配置 PaddleOCR Token'}</span>
              </div>
              <button
                className="mcp-modal-close-btn"
                onClick={() => { setShowPaddleTokenModal(false); setPaddleToken('') }}
                title="关闭"
              >
                <X size={18} strokeWidth={2} aria-hidden="true" />
              </button>
            </div>
            <div className="mcp-modal-body">
              <div style={{ fontSize: '12px', lineHeight: 1.7, color: 'var(--text-muted)' }}>
                PDF 转 DOCX/PPTX 时会直接调用 PaddleOCR AI Studio API，不经过 MCP。
                {paddleTokenConfigured
                  ? ' 当前已有 Token；保存新 Token 后会立即替换旧 Token。'
                  : ' 首次转换时如果尚未配置，也会自动显示同类引导卡片。'}
              </div>
              <a
                href="https://aistudio.baidu.com/paddleocr"
                target="_blank"
                rel="noreferrer"
                style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: '12px' }}
              >
                获取 AI Studio Access Token <ExternalLink size={12} />
              </a>
              <div>
                <label className="mcp-form-label">新的 AI Studio Access Token</label>
                <input
                  autoFocus
                  type="password"
                  className="mcp-input-fancy"
                  placeholder={paddleTokenConfigured ? '粘贴新 Token 以替换旧 Token' : '粘贴 Access Token'}
                  value={paddleToken}
                  onChange={e => setPaddleToken(e.target.value)}
                />
                <div style={{ marginTop: 7, fontSize: '11px', color: 'var(--text-muted)' }}>
                  Token 使用系统加密存储，不写入聊天记录，也不会发送给大模型。
                </div>
              </div>
            </div>
            <div className="mcp-modal-footer" style={{ justifyContent: 'space-between' }}>
              <div>
                {paddleTokenConfigured && (
                  <button
                    type="button"
                    className="btn-secondary"
                    onClick={async () => {
                      try {
                        await window.api.clearPaddleOcrToken()
                        setPaddleTokenConfigured(false)
                        setPaddleToken('')
                        showToast('已删除 PaddleOCR Token。下次转换时会重新显示引导卡片。', 'success')
                      } catch (error: any) {
                        showToast(`删除 Token 失败：${error?.message || error}`, 'error')
                      }
                    }}
                  >
                    删除现有 Token
                  </button>
                )}
              </div>
              <div style={{ display: 'flex', gap: '8px' }}>
                <button
                  type="button"
                  className="btn-secondary"
                  onClick={() => { setShowPaddleTokenModal(false); setPaddleToken('') }}
                >
                  取消
                </button>
                <button
                  type="button"
                  className="btn-primary"
                  disabled={!paddleToken.trim()}
                  onClick={async () => {
                    try {
                      await window.api.setPaddleOcrToken(paddleToken)
                      setPaddleTokenConfigured(true)
                      setPaddleToken('')
                      setShowPaddleTokenModal(false)
                      showToast('PaddleOCR Token 已安全保存。', 'success')
                    } catch (error: any) {
                      showToast(`保存 Token 失败：${error?.message || error}`, 'error')
                    }
                  }}
                >
                  {paddleTokenConfigured ? '保存并替换' : '安全保存'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 编辑弹窗 Modal */}
      {showEditModal && editingServer && (
        <div className="mcp-modal-overlay">
          <div className="mcp-modal-card" onClick={e => e.stopPropagation()}>
            <div className="mcp-modal-header">
              <div className="mcp-modal-title">
                <Pencil size={17} strokeWidth={2} aria-hidden="true" />
                <span>编辑 MCP 服务</span>
              </div>
              <button className="mcp-modal-close-btn" onClick={() => { setShowEditModal(false); setEditingServer(null); }} title="关闭"><X size={18} strokeWidth={2} aria-hidden="true" /></button>
            </div>
            <div className="mcp-modal-body">
              <div>
                <label className="mcp-form-label">服务名称</label>
                <input
                  type="text"
                  className="mcp-input-fancy"
                  placeholder="如：Bing搜索"
                  value={editName}
                  onChange={e => setEditName(e.target.value)}
                />
              </div>

              <div>
                <label className="mcp-form-label">MCP Endpoint 地址</label>
                <input
                  type="text"
                  className="mcp-input-fancy"
                  placeholder="https://mcpmarket.cn/mcp/..."
                  value={editUrl}
                  onChange={e => setEditUrl(e.target.value)}
                />
              </div>

              <div>
                <label className="mcp-form-label">API 鉴权密钥 (Token) - 可选</label>
                <input
                  type="password"
                  className="mcp-input-fancy"
                  placeholder={editingServer.hasApiKey ? '密钥已安全保存；留空表示保持不变' : '默认留空'}
                  value={editApiKey}
                  onChange={e => setEditApiKey(e.target.value)}
                />
                {editingServer.hasApiKey && (
                  <label style={{ display: 'flex', alignItems: 'center', gap: '6px', marginTop: '6px', fontSize: '11px', color: 'var(--text-muted)', cursor: 'pointer' }}>
                    <input
                      type="checkbox"
                      checked={editClearApiKey}
                      onChange={e => setEditClearApiKey(e.target.checked)}
                    />
                    删除已安全保存的密钥
                  </label>
                )}
              </div>

              <div>
                <label className="mcp-form-label">传输协议类型</label>
                <select
                  className="mcp-input-fancy"
                  value={editType}
                  onChange={e => setEditType(e.target.value as any)}
                  style={{ cursor: 'pointer' }}
                >
                  <option value="stream">Streamable HTTP (推荐)</option>
                  <option value="sse">Server-Sent Events</option>
                  <option value="auto">自动探测</option>
                </select>
              </div>
            </div>
            <div className="mcp-modal-footer">
              <button
                type="button"
                className="btn-secondary"
                onClick={() => { setShowEditModal(false); setEditingServer(null); }}
                style={{ fontSize: '12.5px', padding: '6px 14px', borderRadius: '6px', cursor: 'pointer' }}
              >
                取消
              </button>
              <button
                type="button"
                className="btn-primary"
                onClick={() => {
                  if (!editName.trim() || !editUrl.trim()) {
                    showToast('请完整填写服务名称和地址！', 'error')
                    return
                  }
                  const newServers = mcpConfig.servers.map((s: any) =>
                    s.id === editingServer.id
                      ? {
                        ...s,
                        name: editName.trim(),
                        url: editUrl.trim(),
                        apiKey: editApiKey.trim(),
                        hasApiKey: editClearApiKey ? false : Boolean(editApiKey.trim()) || Boolean(s.hasApiKey),
                        clearApiKey: editClearApiKey,
                        type: editType
                      }
                      : s
                  )
                  saveMcpConfig({ servers: newServers })
                  setShowEditModal(false)
                  setEditingServer(null)
                  showToast('服务配置已更新并重新连接！', 'success')
                }}
                style={{ fontSize: '12.5px', padding: '6px 14px', borderRadius: '6px', cursor: 'pointer' }}
              >
                保存修改
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 新增弹窗 Modal */}
      {showAddMcpForm && (
        <div className="mcp-modal-overlay">
          <div className="mcp-modal-card" onClick={e => e.stopPropagation()}>
            <div className="mcp-modal-header">
              <div className="mcp-modal-title">
                <Plus size={17} strokeWidth={2} aria-hidden="true" />
                <span>新增 MCP 服务配置</span>
              </div>
              <button className="mcp-modal-close-btn" onClick={() => {
                setShowAddMcpForm(false)
                setMcpNewName('')
                setMcpNewUrl('')
                setMcpNewApiKey('')
                setMcpNewType('stream')
              }} title="关闭"><X size={18} strokeWidth={2} aria-hidden="true" /></button>
            </div>
            <div className="mcp-modal-body">
              <div>
                <label className="mcp-form-label">服务名称</label>
                <input
                  type="text"
                  className="mcp-input-fancy"
                  placeholder="如：自定义服务、我的数据库助手"
                  value={mcpNewName}
                  onChange={e => setMcpNewName(e.target.value)}
                />
              </div>

              <div>
                <label className="mcp-form-label">MCP Endpoint 地址</label>
                <input
                  type="text"
                  className="mcp-input-fancy"
                  placeholder="https://mcpmarket.cn/mcp/..."
                  value={mcpNewUrl}
                  onChange={e => setMcpNewUrl(e.target.value)}
                />
              </div>

              <div>
                <label className="mcp-form-label">API 鉴权密钥 (Token) - 可选</label>
                <input
                  type="password"
                  className="mcp-input-fancy"
                  placeholder="默认留空"
                  value={mcpNewApiKey}
                  onChange={e => setMcpNewApiKey(e.target.value)}
                />
              </div>

              <div>
                <label className="mcp-form-label">传输协议类型</label>
                <select
                  className="mcp-input-fancy"
                  value={mcpNewType}
                  onChange={e => setMcpNewType(e.target.value as any)}
                  style={{ cursor: 'pointer' }}
                >
                  <option value="stream">Streamable HTTP (推荐)</option>
                  <option value="sse">Server-Sent Events</option>
                  <option value="auto">自动探测</option>
                </select>
              </div>
            </div>
            <div className="mcp-modal-footer">
              <button
                type="button"
                className="btn-secondary"
                onClick={() => {
                  setShowAddMcpForm(false)
                  setMcpNewName('')
                  setMcpNewUrl('')
                  setMcpNewApiKey('')
                  setMcpNewType('stream')
                }}
                style={{ fontSize: '12.5px', padding: '6px 14px', borderRadius: '6px', cursor: 'pointer' }}
              >
                取消
              </button>
              <button
                type="button"
                className="btn-primary"
                onClick={() => {
                  if (!mcpNewName.trim() || !mcpNewUrl.trim()) {
                    showToast('请完整填写服务名称和地址！', 'error')
                    return
                  }
                  const servers = mcpConfig?.servers || []
                  const newServers = [...servers, {
                    id: `mcp-${Date.now()}-${Math.random().toString(36).substring(2, 5)}`,
                    name: mcpNewName.trim(),
                    url: mcpNewUrl.trim(),
                    apiKey: mcpNewApiKey.trim(),
                    type: mcpNewType,
                    enabled: true
                  }]
                  saveMcpConfig({ servers: newServers })

                  setShowAddMcpForm(false)
                  setMcpNewName('')
                  setMcpNewUrl('')
                  setMcpNewApiKey('')
                  setMcpNewType('stream')
                  showToast('已成功添加新 MCP 服务！', 'success')
                }}
                style={{ fontSize: '12.5px', padding: '6px 14px', borderRadius: '6px', cursor: 'pointer' }}
              >
                保存并连接
              </button>
            </div>
          </div>
        </div>
      )}

      {/* MCP 测试结果弹框 */}
      {showTestResultModal && testResultData && createPortal((
        <div
          style={{
            position: 'absolute',
            inset: 0,
            width: '100%',
            height: '100%',
            backgroundColor: 'rgba(0,0,0,0.7)',
            zIndex: 99998,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            cursor: 'pointer',
            boxSizing: 'border-box'
          }}
          onClick={() => setShowTestResultModal(false)}
        >
          <div
            style={{
              width: 'min(900px, calc(100% - 40px))',
              maxWidth: 'calc(100% - 40px)',
              height: 'min(80%, 800px)',
              maxHeight: 'calc(100% - 40px)',
              backgroundColor: 'var(--color-bg-primary, #fff)',
              borderRadius: '12px',
              boxShadow: '0 8px 32px rgba(0,0,0,0.3)',
              display: 'flex',
              flexDirection: 'column',
              overflow: 'hidden',
              minHeight: 0,
              cursor: 'default'
            }}
            onClick={(e) => e.stopPropagation()}
          >
            {/* 弹框头部 */}
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                padding: '16px 20px',
                borderBottom: '1px solid var(--color-border, var(--ds-color-23653065306530))',
                backgroundColor: 'var(--color-bg-secondary, #f5f5f5)'
              }}
            >
              <h3 style={{ margin: 0, fontSize: '16px', fontWeight: 600 }}>
                <Plug size={18} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                MCP 测试结果 - {testResultServerName}
              </h3>
              <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                <button
                  onClick={() => {
                    navigator.clipboard.writeText(JSON.stringify(testResultData, null, 2))
                    showToast('已复制到剪贴板', 'success')
                  }}
                  style={{
                    background: 'linear-gradient(135deg, var(--ds-color-23363061356661) 0%, var(--ds-color-23336238326636) 100%)',
                    border: 'none',
                    color: '#fff',
                    padding: '6px 12px',
                    borderRadius: '6px',
                    fontSize: '12px',
                    fontWeight: 600,
                    cursor: 'pointer'
                  }}
                >
                  <Clipboard size={14} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                  复制全部
                </button>
                <button
                  onClick={() => setShowTestResultModal(false)}
                  style={{
                    background: 'none',
                    border: 'none',
                    fontSize: '20px',
                    cursor: 'pointer',
                    padding: '4px 8px',
                    borderRadius: '4px',
                    color: 'var(--color-text-primary, var(--ds-color-23333333))'
                  }}
                >
                  <X size={18} strokeWidth={2} aria-hidden="true" />
                </button>
              </div>
            </div>

            {/* 弹框内容 */}
            <div
              style={{
                flex: 1,
                overflow: 'auto',
                padding: '20px'
              }}
            >
              {/* 测试状态 */}
              <div
                style={{
                  marginBottom: '16px',
                  padding: '12px 16px',
                  backgroundColor: testResultData.success ? 'rgba(16, 185, 129, 0.08)' : 'rgba(239, 68, 68, 0.08)',
                  border: `1px solid ${testResultData.success ? 'rgba(16, 185, 129, 0.2)' : 'rgba(239, 68, 68, 0.2)'}`,
                  borderRadius: '8px',
                  fontSize: '13px'
                }}
              >
                <div style={{ fontWeight: 600, marginBottom: '8px', color: testResultData.success ? '#10b981' : '#ef4444', display: 'flex', alignItems: 'center' }}>
                  {testResultData.success
                    ? <CheckCircle2 size={16} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                    : <CircleX size={16} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />}
                  {testResultData.success ? '测试成功' : '测试失败'}
                </div>
                {testResultData.protocol && <div>协议: {testResultData.protocol}</div>}
                {testResultData.error && <div>错误: {testResultData.error}</div>}
                {testResultData.toolsSize && (
                  <div>
                    工具定义大小: {testResultData.toolsSize.charCount.toLocaleString()} 字符
                    (~{testResultData.toolsSize.estimatedTokens.toLocaleString()} tokens)
                  </div>
                )}
              </div>

              {/* 工具列表 */}
              {testResultData.tools && testResultData.tools.length > 0 && (
                <div style={{ marginBottom: '20px' }}>
                  <div
                    style={{
                      fontWeight: 600,
                      marginBottom: '8px',
                      fontSize: '14px',
                      color: 'var(--color-text-primary, var(--ds-color-23333333))'
                    }}
                  >
                    <Wrench size={16} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                    工具列表 (共 {testResultData.tools.length} 个)
                  </div>
                  <div
                    style={{
                      border: '1px solid var(--color-border, var(--ds-color-23653065306530))',
                      borderRadius: '8px',
                      overflow: 'hidden'
                    }}
                  >
                    {testResultData.tools.map((tool: any, idx: number) => (
                      <div
                        key={idx}
                        style={{
                          padding: '12px 16px',
                          borderBottom: idx < testResultData.tools.length - 1 ? '1px solid var(--color-border, var(--ds-color-23653065306530))' : 'none',
                          backgroundColor: 'var(--color-bg-secondary, #f8f9fa)'
                        }}
                      >
                        <div
                          style={{
                            fontWeight: 600,
                            fontSize: '13px',
                            marginBottom: '4px',
                            color: 'var(--ds-color-23336238326636)'
                          }}
                        >
                          {tool.name}
                        </div>
                        <div
                          style={{
                            fontSize: '12px',
                            color: 'var(--color-text-secondary, var(--ds-color-23363636))',
                            marginBottom: '8px'
                          }}
                        >
                          {tool.description || '无描述'}
                        </div>
                        <pre
                          style={{
                            margin: 0,
                            fontSize: '11px',
                            lineHeight: '1.4',
                            whiteSpace: 'pre-wrap',
                            wordBreak: 'break-word',
                            fontFamily: 'monospace',
                            backgroundColor: 'var(--color-bg-code, #f0f0f0)',
                            padding: '8px',
                            borderRadius: '4px',
                            maxHeight: '150px',
                            overflow: 'auto'
                          }}
                        >
                          {JSON.stringify(tool.inputSchema || {}, null, 2)}
                        </pre>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* 完整 JSON 响应 */}
              <div>
                <div
                  style={{
                    fontWeight: 600,
                    marginBottom: '8px',
                    fontSize: '14px',
                    color: 'var(--color-text-primary, var(--ds-color-23333333))'
                  }}
                >
                  <FileJson2 size={16} strokeWidth={2} className="ui-icon-leading" aria-hidden="true" />
                  完整 JSON 响应
                </div>
                <pre
                  style={{
                    margin: 0,
                    fontSize: '12px',
                    lineHeight: '1.5',
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-word',
                    fontFamily: 'monospace',
                    backgroundColor: 'var(--color-bg-code, #f0f0f0)',
                    padding: '16px',
                    borderRadius: '8px',
                    maxHeight: '400px',
                    overflow: 'auto',
                    border: '1px solid var(--color-border, var(--ds-color-23653065306530))'
                  }}
                >
                  {JSON.stringify(testResultData, null, 2)}
                </pre>
              </div>
            </div>
          </div>
        </div>
      ),
        document.querySelector('.workspace-panel-column') ?? document.body
      )}

      {/* 表行编辑 Modal — 多字段 */}
      {editingRow && (() => {
        const fields = TABLE_FIELDS[editingRow.table] || []
        return createPortal(
        <div className="cron-modal-overlay memory-editor-overlay" style={{
          position: 'absolute', inset: 0, width: '100%', height: '100%',
          backgroundColor: 'rgba(0, 0, 0, 0.5)', backdropFilter: 'blur(8px)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1004
        }} onClick={() => setEditingRow(null)}>
          <div className="mcp-modal-card memory-editor-modal" onClick={e => e.stopPropagation()} style={{ width: '600px', maxWidth: '90%', maxHeight: '85%', display: 'flex', flexDirection: 'column' }}>
            <div className="mcp-modal-header">
              <div className="mcp-modal-title">
                <Pencil size={17} strokeWidth={2} aria-hidden="true" />
                <span>编辑 {TABLE_NAMES[editingRow.table]}</span>
              </div>
              <button className="mcp-modal-close-btn" onClick={() => setEditingRow(null)}><X size={18} /></button>
            </div>
            <div className="mcp-modal-body memory-editor-body" style={{ flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 10 }}>
              {fields.map(f => (
                <div key={f.key} className="memory-editor-field">
                  <label className="mcp-form-label">{f.label}</label>
                  {f.type === 'textarea' ? (
                    <textarea
                      className="mcp-input-fancy"
                      style={{ minHeight: '80px', width: '100%', resize: 'vertical' }}
                      placeholder={f.placeholder}
                      value={editingRow.data[f.key] || ''}
                      onChange={e => setEditingRow({ ...editingRow, data: { ...editingRow.data, [f.key]: e.target.value } })}
                    />
                  ) : (
                    <input
                      type={f.type}
                      className="mcp-input-fancy"
                      placeholder={f.placeholder}
                      value={editingRow.data[f.key] || ''}
                      onChange={e => setEditingRow({ ...editingRow, data: { ...editingRow.data, [f.key]: e.target.value } })}
                    />
                  )}
                </div>
              ))}
            </div>
            <div className="mcp-modal-footer memory-editor-actions">
              <button className="btn-secondary" onClick={() => setEditingRow(null)}
                style={{ fontSize: '12.5px', padding: '6px 14px', borderRadius: '6px', cursor: 'pointer' }}>取消</button>
              <button className="btn-primary"
                onClick={async () => {
                  await window.api.memoryTableUpdate(editingRow.table, editingRow.id, editingRow.data)
                  setEditingRow(null)
                  loadTableData(activeTable, tablePage, tableSearch)
                  loadTableStats()
                }}
                style={{ fontSize: '12.5px', padding: '6px 14px', borderRadius: '6px', cursor: 'pointer' }}>
                <Save size={15} /> 保存
              </button>
            </div>
          </div>
        </div>,
        document.querySelector('.workspace-panel-column') ?? document.body
        )
      })()}

      {/* 新增行 Modal */}
      {showAddModal && (() => {
        const fields = TABLE_FIELDS[activeTable] || []
        return createPortal(
        <div className="cron-modal-overlay memory-editor-overlay" style={{
          position: 'absolute', inset: 0, width: '100%', height: '100%',
          backgroundColor: 'rgba(0, 0, 0, 0.5)', backdropFilter: 'blur(8px)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1004
        }} onClick={() => setShowAddModal(false)}>
          <div className="mcp-modal-card memory-editor-modal" onClick={e => e.stopPropagation()} style={{ width: '600px', maxWidth: '90%', maxHeight: '85%', display: 'flex', flexDirection: 'column' }}>
            <div className="mcp-modal-header">
              <div className="mcp-modal-title">
                <Plus size={17} strokeWidth={2} aria-hidden="true" />
                <span>新增 {TABLE_NAMES[activeTable]}</span>
              </div>
              <button className="mcp-modal-close-btn" onClick={() => setShowAddModal(false)}><X size={18} /></button>
            </div>
            <div className="mcp-modal-body memory-editor-body" style={{ flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 10 }}>
              {fields.map(f => (
                <div key={f.key} className="memory-editor-field">
                  <label className="mcp-form-label">{f.label}</label>
                  {f.type === 'textarea' ? (
                    <textarea
                      className="mcp-input-fancy"
                      style={{ minHeight: '80px', width: '100%', resize: 'vertical' }}
                      placeholder={f.placeholder}
                      value={newRowData[f.key] || ''}
                      onChange={e => setNewRowData({ ...newRowData, [f.key]: e.target.value })}
                    />
                  ) : (
                    <input
                      type={f.type}
                      className="mcp-input-fancy"
                      placeholder={f.placeholder}
                      value={newRowData[f.key] || ''}
                      onChange={e => setNewRowData({ ...newRowData, [f.key]: e.target.value })}
                    />
                  )}
                </div>
              ))}
            </div>
            <div className="mcp-modal-footer memory-editor-actions">
              <button className="btn-secondary" onClick={() => { setShowAddModal(false); setNewRowData({}) }}
                style={{ fontSize: '12.5px', padding: '6px 14px', borderRadius: '6px', cursor: 'pointer' }}>取消</button>
              <button className="btn-primary"
                onClick={async () => {
                  await window.api.memoryTableCreate(activeTable, newRowData)
                  setShowAddModal(false)
                  setNewRowData({})
                  loadTableData(activeTable, tablePage, tableSearch)
                  loadTableStats()
                }}
                style={{ fontSize: '12.5px', padding: '6px 14px', borderRadius: '6px', cursor: 'pointer' }}>
                <Plus size={15} /> 创建
              </button>
            </div>
          </div>
        </div>,
        document.querySelector('.workspace-panel-column') ?? document.body
        )
      })()}
    </div>
  )
}
