/* eslint-disable react-hooks/set-state-in-effect */
import React, { Suspense, lazy, useState, useRef, useEffect, useMemo } from 'react'
import { useAppStore, useAppStoreRaw } from '../hooks/useAppStore'
import type { Session } from '../hooks/useAppStore'
import { ChatControllerProvider } from '../hooks/useChatController'
import { OverviewIcon, SkillsIcon, SettingsIcon } from './icons/Icons'
import {
  CheckCircle2,
  ArrowLeft,
  ChevronRight,
  CircleX,
  Copy,
  KeyRound,
  Heart,
  Lightbulb,
  List,
  MessageCircle,
  Minus,
  Moon,
  MoreHorizontal,
  Network,
  PanelLeftClose,
  PanelLeftOpen,
  PanelRightClose,
  PanelRightOpen,
  Plus,
  Search,
  ScrollText,
  Square,
  Sun,
  Workflow,
  X
} from 'lucide-react'
import { useRpaStore } from '../rpa/useRpaStore'
import iconFromImage from '../assets/icon.png'
import { RecentSessionList } from './RecentSessionList'
import { normalizeSearchCitations } from '../utils/helpers'

const ChatPage = lazy(() => import('../pages/ChatPage').then(module => ({ default: module.ChatPage })))
const ControlPage = lazy(() => import('../pages/ControlPage').then(module => ({ default: module.ControlPage })))
const AgentPage = lazy(() => import('../pages/AgentPage').then(module => ({ default: module.AgentPage })))
const KnowledgeGraphPanel = lazy(() => import('./KnowledgeGraphPanel').then(module => ({ default: module.KnowledgeGraphPanel })))
const SettingsPage = lazy(() => import('../pages/SettingsPage').then(module => ({ default: module.SettingsPage })))
const LogsPage = lazy(() => import('../pages/LogsPage').then(module => ({ default: module.LogsPage })))
const RpaPage = lazy(() => import('../rpa/RpaPage').then(module => ({ default: module.RpaPage })))
const FilePreviewPanel = lazy(() =>
  import('./FilePreviewPanel').then(module => ({ default: module.FilePreviewPanel }))
)

function PageLoadingFallback(): React.JSX.Element {
  return <div className="page-loading-placeholder" role="status" aria-label="正在加载页面" />
}

type FunctionPageId = 'control' | 'agent' | 'archive' | 'knowledge' | 'rpa' | 'logs' | 'settings'

type WorkspaceTab =
  | { key: string; kind: 'session'; sessionId: string }
  | { key: string; kind: 'page'; pageId: FunctionPageId; detailId?: string }

type CompanionKnowledge = {
  nodes?: Array<{ id: string; label: string; type?: string }>
  edges?: Array<{ source: string; target: string; label?: string }>
}

const FUNCTION_PAGE_LABELS: Record<FunctionPageId, string> = {
  control: '订阅频道',
  agent: '代理',
  archive: '陪伴档案',
  knowledge: '知识图谱',
  rpa: 'RPA 任务',
  logs: '日志',
  settings: '设置'
}

const AGENT_SUB_TAB_LABELS: Record<string, string> = {
  skills: '技能加入',
  memory: '记忆控制',
  knowledge: '知识图谱',
  cron: '定时任务',
  mcp: 'MCP 服务'
}

const SETTINGS_SUB_TAB_LABELS: Record<string, string> = {
  keys: '模型配置',
  storage: '存储管理',
  avatar: '虚拟体'
}

const isFunctionPage = (tab: string): tab is FunctionPageId =>
  Object.prototype.hasOwnProperty.call(FUNCTION_PAGE_LABELS, tab)

const checkIsThinking = (s: Session | undefined): boolean => {
  if (!s || !s.messages) return false
  for (let i = s.messages.length - 1; i >= 0; i--) {
    const m = s.messages[i]
    if (m.sender === 'agent') return !!m.isThinking
  }
  return false
}

function getSessionPreview(session: Session): string {
  const summary = (session.contextSummary || '')
    .replace(/\n*---\s*\n*<details>[\s\S]*?<\/details>/gi, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/[*_`#>-]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  if (summary) return summary.slice(-80)
  const messages = session.messages || []
  for (let index = messages.length - 1; index >= 0; index--) {
    const message = messages[index]
    if (message.sender === 'system' || message.isThinking) continue
    const text = normalizeSearchCitations(message.text || '', 'plain')
      .replace(/```[\s\S]*?```/g, '[代码]')
      .replace(/[-*_`#>]/g, '')
      .replace(/\s+/g, ' ')
      .trim()
    if (text) return text.slice(0, 40)
    const fileNames = Array.isArray(message.fileInfos)
      ? message.fileInfos.map((file: { name?: string }) => file.name).filter(Boolean)
      : []
    if (fileNames.length > 0) return `附件：${fileNames.join('、')}`
    if (message.fileInfo?.name) return `附件：${message.fileInfo.name}`
  }
  return ''
}

/** Keeps the shell stable while only the active agent message text is growing. */
function createShellSessionsSelector(): (state: { sessions: Session[] }) => Session[] {
  let cachedSessions: Session[] = []
  let cachedSignatures: string[] = []
  return (state: { sessions: Session[] }) => {
    const sessions = state.sessions
    const signatures = sessions.map(session => [
      session.id,
      session.name,
      session.pinned ? '1' : '0',
      session.createdAt || session.time,
      checkIsThinking(session) ? '1' : '0',
      getSessionPreview(session)
    ].join('\u0000'))
    if (
      signatures.length === cachedSignatures.length &&
      signatures.every((signature, index) => signature === cachedSignatures[index])
    ) {
      return cachedSessions
    }
    cachedSessions = sessions
    cachedSignatures = signatures
    return sessions
  }
}

export function AgentWindow(): React.JSX.Element {
  const store = useAppStore()
  const selectShellSessions = useMemo(() => createShellSessionsSelector(), [])

  // 使用 Zustand 细粒度选择器订阅状态以阻止全局无用重渲染
  const theme = useAppStoreRaw(state => state.theme)
  const isCollapsed = useAppStoreRaw(state => state.isCollapsed)
  const activeTab = useAppStoreRaw(state => state.activeTab)
  const agentSubTab = useAppStoreRaw(state => state.agentSubTab)
  const settingsSubTab = useAppStoreRaw(state => state.settingsSubTab)
  const showApiKeyModal = useAppStoreRaw(state => state.showApiKeyModal)
  const sessions = useAppStoreRaw(selectShellSessions)
  const activeSessionId = useAppStoreRaw(state => state.activeSessionId)
  const customModelFile = useAppStoreRaw(state => state.customModelFile)
  const skillsList = useAppStoreRaw(state => state.skillsList)
  const contextRounds = useAppStoreRaw(state => state.contextRounds)
  const toast = useAppStoreRaw(state => state.toast)
  const activePermissionRequest = useAppStoreRaw(state => state.activePermissionRequest)
  const showFilePanel = useAppStoreRaw(state => state.showFilePanel)
  const isSessionsInitialized = useAppStoreRaw(state => state.isSessionsInitialized)
  const rpaTasks = useRpaStore(state => state.tasks)
  const activeRpaTaskId = useRpaStore(state => state.activeTaskId)
  const selectRpaTask = useRpaStore(state => state.selectTask)
  const sessionsById = useMemo(
    () => new Map(sessions.map(session => [session.id, session])),
    [sessions]
  )
  const rpaTasksById = useMemo(
    () => new Map(rpaTasks.map(task => [task.id, task])),
    [rpaTasks]
  )
  // 派生状态从 Zustand 中获取
  const activeSession = sessionsById.get(activeSessionId) || sessions[0] || { messages: [] }
  const activeSessMessages = activeSession.messages || []

  const [showSplash, setShowSplash] = useState(true)
  const [splashFadeOut, setSplashFadeOut] = useState(false)
  const [isMinDelayPassed, setIsMinDelayPassed] = useState(false)

  useEffect(() => {
    const timer = setTimeout(() => {
      setIsMinDelayPassed(true)
    }, 1200)
    return () => clearTimeout(timer)
  }, [])

  useEffect(() => {
    if (isMinDelayPassed && isSessionsInitialized) {
      setSplashFadeOut(true)
      const destroyTimer = setTimeout(() => {
        setShowSplash(false)
      }, 400)
      return () => clearTimeout(destroyTimer)
    }
    return undefined
  }, [isMinDelayPassed, isSessionsInitialized])

  const {
    handleThemeToggle,
    setIsCollapsed,
    setActiveTab,
    setAgentSubTab,
    setSettingsSubTab,
    setShowApiKeyModal,
    setActiveSessionId,
    handleCreateNewSession,
    handleDeleteSession,
    handleTogglePinSession,
    handleRenameSession,
    handleReorderSessions,
    setHighlightedMessageId,
    setShowFilePanel,
    setPreviewFile,
    setOpenTabs,
    showToast
  } = store

  const currentAvatarName = customModelFile ? customModelFile.replace(/\.model3\.json$/i, '') : 'MindPet'

  const chatActions = useMemo(() => ({
    setInputValue: store.setInputValue,
    handleSendChat: store.handleSendChat,
    saveLlmConfig: store.saveLlmConfig,
    setAttachedFiles: store.setAttachedFiles,
    handlePasteFiles: store.handlePasteFiles,
    handleUploadFile: store.handleUploadFile,
    setHighlightedMessageId: store.setHighlightedMessageId,
    handleAbortLlm: store.handleAbortLlm,
    handleUpdateExecutionDevice: store.handleUpdateExecutionDevice,
    handleConnectSsh: store.handleConnectSsh,
    handleDisconnectSsh: store.handleDisconnectSsh,
    showToast: store.showToast,
    handleRespondPermission: store.handleRespondPermission,
    toggleSkillEnable: store.toggleSkillEnable,
    setActiveTab: store.setActiveTab,
    setAgentSubTab: store.setAgentSubTab,
    refreshSkillsAndStorage: store.refreshSkillsAndStorage,
    refreshMcpServers: store.refreshMcpServers,
    saveMcpConfig: store.saveMcpConfig,
    handlePreviewFile: store.handlePreviewFile,
    setShowFilePanel: store.setShowFilePanel
  }), [
    store.setInputValue,
    store.handleSendChat,
    store.saveLlmConfig,
    store.setAttachedFiles,
    store.handlePasteFiles,
    store.handleUploadFile,
    store.setHighlightedMessageId,
    store.handleAbortLlm,
    store.handleUpdateExecutionDevice,
    store.handleConnectSsh,
    store.handleDisconnectSsh,
    store.showToast,
    store.handleRespondPermission,
    store.toggleSkillEnable,
    store.setActiveTab,
    store.setAgentSubTab,
    store.refreshSkillsAndStorage,
    store.refreshMcpServers,
    store.saveMcpConfig,
    store.handlePreviewFile,
    store.setShowFilePanel
  ])

  const [showHistoryDropdown, setShowHistoryDropdown] = useState(false)
  const [companionPanel, setCompanionPanel] = useState<'memory' | 'knowledge' | null>(null)
  const [companionKnowledge, setCompanionKnowledge] = useState<CompanionKnowledge | null>(null)
  const [companionKnowledgeLoading, setCompanionKnowledgeLoading] = useState(false)
  const [historySearchQuery, setHistorySearchQuery] = useState('')
  const historyDropdownRef = useRef<HTMLDivElement>(null)

  // 侧边栏下方菜单组（控制/代理/日志/设置）默认收起，把空间让给最近会话
  const [menuHovering, setMenuHovering] = useState(false)

  // Migrate persisted sessions that still point at the old Agent sub-tab.
  useEffect(() => {
    if (activeTab === 'agent' && agentSubTab === 'knowledge') {
      setAgentSubTab('skills')
      setActiveTab('knowledge')
    }
  }, [activeTab, agentSubTab, setActiveTab, setAgentSubTab])

  const [isMaximized, setIsMaximized] = useState(false)

  const checkMaximized = async (): Promise<void> => {
    if (window.api?.isAgentWindowMaximized) {
      const max = await window.api.isAgentWindowMaximized()
      setIsMaximized(max)
    }
  }

  useEffect(() => {
    checkMaximized()
    window.addEventListener('resize', checkMaximized)
    return () => window.removeEventListener('resize', checkMaximized)
  }, [])

  const [workspaceTabs, setWorkspaceTabs] = useState<WorkspaceTab[]>([])
  const [sessionToDeleteId, setSessionToDeleteId] = useState<string | null>(null)
  const [hiddenTabKeys, setHiddenTabKeys] = useState<string[]>([])
  const [showTabOverflowMenu, setShowTabOverflowMenu] = useState(false)
  const tabsViewportRef = useRef<HTMLDivElement>(null)
  const tabElementRefs = useRef(new Map<string, HTMLDivElement>())
  const tabOverflowMenuRef = useRef<HTMLDivElement>(null)

  const getWorkspaceTabLabel = (tab: WorkspaceTab): string => {
    if (tab.kind === 'session') {
      return sessionsById.get(tab.sessionId)?.name || '新会话'
    }
    if (tab.pageId === 'rpa' && tab.detailId) {
      return `RPA-${rpaTasksById.get(tab.detailId)?.name || '未知任务'}`
    }
    if (tab.pageId === 'agent') {
      return `代理-${AGENT_SUB_TAB_LABELS[agentSubTab] || '技能加入'}`
    }
    if (tab.pageId === 'settings') {
      return `设置-${SETTINGS_SUB_TAB_LABELS[settingsSubTab] || '模型配置'}`
    }
    return FUNCTION_PAGE_LABELS[tab.pageId]
  }

  const workspaceTabLabelSignature = workspaceTabs
    .map(tab => `${tab.key}:${getWorkspaceTabLabel(tab)}`)
    .join('\u0000')

  const activeWorkspaceKey = activeTab === 'chat'
    ? `session:${activeSessionId}`
    : activeTab === 'rpa' && activeRpaTaskId
      ? `page:rpa:${activeRpaTaskId}`
      : `page:${activeTab}`

  useEffect(() => {
    if (activeTab === 'chat' && activeSessionId) {
      const key = `session:${activeSessionId}`
      setWorkspaceTabs(prev => prev.some(tab => tab.key === key)
        ? prev
        : [...prev, { key, kind: 'session', sessionId: activeSessionId }])
    }
  }, [activeSessionId, activeTab])

  useEffect(() => {
    if (isFunctionPage(activeTab)) {
      const detailId = activeTab === 'rpa' ? activeRpaTaskId || undefined : undefined
      const key = detailId ? `page:${activeTab}:${detailId}` : `page:${activeTab}`
      setWorkspaceTabs(prev => prev.some(tab => tab.key === key)
        ? prev
        : [...prev, { key, kind: 'page', pageId: activeTab, detailId }])
    }
  }, [activeRpaTaskId, activeTab])

  useEffect(() => {
    const validIds = new Set(sessions.map(s => s.id))
    setWorkspaceTabs(prev => {
      const next = prev.filter(tab => tab.kind === 'page' || validIds.has(tab.sessionId))
      return next.length === prev.length ? prev : next
    })
  }, [sessions])

  const activateWorkspaceTab = (tab: WorkspaceTab): void => {
    if (tab.kind === 'session') {
      setActiveSessionId(tab.sessionId)
      setActiveTab('chat')
    } else if (tab.pageId === 'rpa') {
      void selectRpaTask(tab.detailId || null).then(() => setActiveTab('rpa'))
    } else {
      setActiveTab(tab.pageId)
    }
  }

  const handleTabsWheel = (event: React.WheelEvent<HTMLDivElement>): void => {
    if (event.ctrlKey) return
    const viewport = event.currentTarget
    if (viewport.scrollWidth <= viewport.clientWidth) return

    const delta = Math.abs(event.deltaX) > Math.abs(event.deltaY)
      ? event.deltaX
      : event.deltaY
    if (!delta) return

    event.preventDefault()
    viewport.scrollLeft += delta
  }

  const handleCloseTab = (keyToClose: string, e: React.MouseEvent): void => {
    e.stopPropagation()
    const currentIndex = workspaceTabs.findIndex(tab => tab.key === keyToClose)
    const closingTab = workspaceTabs[currentIndex]
    if (!closingTab) return

    const isClosingActive = closingTab.key === activeWorkspaceKey
    const nextTabs = workspaceTabs.filter(tab => tab.key !== keyToClose)
    setWorkspaceTabs(nextTabs)

    if (isClosingActive) {
      const nextTab = nextTabs[Math.min(currentIndex, nextTabs.length - 1)]
      if (nextTab) {
        activateWorkspaceTab(nextTab)
      } else if (sessions.length > 0) {
        setActiveSessionId(sessions[0].id)
        setActiveTab('chat')
      } else {
        handleCreateNewSession()
      }
    }
  }

  useEffect(() => {
    const viewport = tabsViewportRef.current
    if (!viewport) return undefined

    let frameId = 0
    const updateHiddenTabs = (): void => {
      cancelAnimationFrame(frameId)
      frameId = requestAnimationFrame(() => {
        const viewportRect = viewport.getBoundingClientRect()
        const nextHiddenKeys = workspaceTabs
          .filter(tab => {
            const element = tabElementRefs.current.get(tab.key)
            if (!element) return false
            const rect = element.getBoundingClientRect()
            return rect.left < viewportRect.left - 1 || rect.right > viewportRect.right + 1
          })
          .map(tab => tab.key)
        setHiddenTabKeys(prev => (
          prev.length === nextHiddenKeys.length && prev.every((key, index) => key === nextHiddenKeys[index])
            ? prev
            : nextHiddenKeys
        ))
      })
    }

    updateHiddenTabs()
    const resizeObserver = new ResizeObserver(updateHiddenTabs)
    resizeObserver.observe(viewport)
    viewport.addEventListener('scroll', updateHiddenTabs, { passive: true })
    return () => {
      cancelAnimationFrame(frameId)
      resizeObserver.disconnect()
      viewport.removeEventListener('scroll', updateHiddenTabs)
    }
  }, [workspaceTabs, workspaceTabLabelSignature])

  useEffect(() => {
    const viewport = tabsViewportRef.current
    const activeElement = tabElementRefs.current.get(activeWorkspaceKey)
    if (!viewport || !activeElement) return

    const frameId = requestAnimationFrame(() => {
      const viewportRect = viewport.getBoundingClientRect()
      const activeRect = activeElement.getBoundingClientRect()
      if (activeRect.left < viewportRect.left) {
        viewport.scrollLeft -= viewportRect.left - activeRect.left
      } else if (activeRect.right > viewportRect.right) {
        viewport.scrollLeft += activeRect.right - viewportRect.right
      }
    })
    return () => cancelAnimationFrame(frameId)
  }, [activeWorkspaceKey, workspaceTabs])

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent): void => {
      if (tabOverflowMenuRef.current && !tabOverflowMenuRef.current.contains(event.target as Node)) {
        setShowTabOverflowMenu(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  useEffect(() => {
    if (hiddenTabKeys.length === 0) setShowTabOverflowMenu(false)
  }, [hiddenTabKeys])


  useEffect(() => {
    const handleClickOutside = (event: MouseEvent): void => {
      if (historyDropdownRef.current && !historyDropdownRef.current.contains(event.target as Node)) {
        setShowHistoryDropdown(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => {
      document.removeEventListener('mousedown', handleClickOutside)
    }
  }, [])

  useEffect(() => {
    setHistorySearchQuery('')
  }, [activeSessionId])

  useEffect(() => {
    // A session change starts a new local context; do not leave a preview
    // from the previous conversation sitting beside it.
    setCompanionPanel(null)
    setCompanionKnowledge(null)
  }, [activeSessionId])

  const userMessages = activeSessMessages?.filter(m => m.sender === 'user') || []
  const filteredHistoryMessages = useMemo(() => {
    const query = historySearchQuery.trim().toLowerCase()
    if (!query) return userMessages
    return userMessages.filter(message =>
      normalizeSearchCitations(message.text || '', 'plain').toLowerCase().includes(query)
    )
  }, [activeSessMessages, historySearchQuery])

  const companionMemoryMessages = useMemo(
    () => activeSessMessages
      .filter(message => message.sender !== 'system' && !message.isThinking && Boolean(message.text?.trim()))
      .slice(-3)
      .reverse(),
    [activeSessMessages]
  )

  const openCompanionPanel = (panel: 'memory' | 'knowledge'): void => {
    setCompanionPanel(panel)
    if (panel !== 'knowledge') return
    setCompanionKnowledge(null)
    setCompanionKnowledgeLoading(true)
    void window.api.getKnowledgeGraph(undefined, 6)
      .then(data => setCompanionKnowledge(data as CompanionKnowledge))
      .catch(() => setCompanionKnowledge({ nodes: [], edges: [] }))
      .finally(() => setCompanionKnowledgeLoading(false))
  }

  const renderPage = (): React.JSX.Element => {
    let page: React.JSX.Element
    switch (activeTab) {
      case 'chat': page = <ChatControllerProvider actions={chatActions}><ChatPage /></ChatControllerProvider>; break
      case 'control': page = <ControlPage store={store} />; break
      case 'agent': page = <AgentPage store={store} />; break
      case 'archive': page = <AgentPage store={store} archiveMode />; break
      case 'knowledge': page = <KnowledgeGraphPanel showToast={showToast} />; break
      case 'logs': page = <LogsPage store={store} />; break
      case 'settings': page = <SettingsPage store={store} />; break
      case 'rpa': page = <RpaPage />; break
      default: page = <div>Overview</div>
    }
    return <Suspense fallback={<PageLoadingFallback />}>{page}</Suspense>
  }

  const hiddenTabKeySet = new Set(hiddenTabKeys)
  const hiddenTabs = workspaceTabs.filter(tab => hiddenTabKeySet.has(tab.key))
  const handleMenuKeyDown = (event: React.KeyboardEvent<HTMLDivElement>, action: () => void): void => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      action()
    }
  }

  return (
    <div className={`agent-window-container ${theme}`}>
      {/* ── 1. Left Sidebar ── */}
      <div className={`agent-sidebar ${isCollapsed ? 'collapsed' : ''}`}>
        <div
          className={`sidebar-menu-rail ${menuHovering ? 'menu-expanded' : ''}`}
          onMouseEnter={() => setMenuHovering(true)}
          onMouseLeave={() => setMenuHovering(false)}
        >
          <div className="sidebar-rail-drag" style={{ WebkitAppRegion: 'drag' } as React.CSSProperties & { WebkitAppRegion: string }} />
          <div className="sidebar-rail-brand">
            <button
              className={`brand-avatar brand-avatar-toggle ${isCollapsed ? 'is-collapsed' : ''}`}
              onClick={() => {
                if (isCollapsed) {
                  setMenuHovering(false)
                  setIsCollapsed(false)
                }
              }}
              aria-label={isCollapsed ? 'Open sessions' : 'MindPet'}
              title={isCollapsed ? 'Open sessions' : 'MindPet'}
              type="button"
            >
              <img src={iconFromImage} alt="MindPet" style={{ width: '100%', height: '100%', objectFit: 'cover', borderRadius: 'inherit' }} />
              <span className="brand-avatar-open-icon" aria-hidden="true">
                <PanelLeftOpen size={16} strokeWidth={2} />
              </span>
            </button>
          </div>

          {/* 可折叠菜单组：默认以方形图标显示，展开后显示文字 */}
          <div className="sidebar-menu">
            <div className={`menu-item menu-item-companion ${activeTab === 'chat' ? 'active' : ''}`} onClick={() => setActiveTab('chat')} onKeyDown={(event) => handleMenuKeyDown(event, () => setActiveTab('chat'))} role="button" tabIndex={0} aria-current={activeTab === 'chat' ? 'page' : undefined} title="陪伴">
              <div className="menu-item-left"><MessageCircle size={18} strokeWidth={2} aria-hidden="true" /><span>陪伴</span></div>
              <ChevronRight className="menu-item-arrow" size={14} strokeWidth={2} aria-hidden="true" />
            </div>
            <div className={`menu-item ${activeTab === 'control' ? 'active' : ''}`} onClick={() => setActiveTab('control')} onKeyDown={(event) => handleMenuKeyDown(event, () => setActiveTab('control'))} role="button" tabIndex={0} aria-current={activeTab === 'control' ? 'page' : undefined} title="连接与服务">
              <div className="menu-item-left"><OverviewIcon /><span>连接与服务</span></div>
              <ChevronRight className="menu-item-arrow" size={14} strokeWidth={2} aria-hidden="true" />
            </div>
            <div className={`menu-item ${activeTab === 'agent' ? 'active' : ''}`} onClick={() => setActiveTab('agent')} onKeyDown={(event) => handleMenuKeyDown(event, () => setActiveTab('agent'))} role="button" tabIndex={0} aria-current={activeTab === 'agent' ? 'page' : undefined} title="能力中心">
              <div className="menu-item-left"><SkillsIcon /><span>能力中心</span></div>
              <ChevronRight className="menu-item-arrow" size={14} strokeWidth={2} aria-hidden="true" />
            </div>
            <div className={`menu-item ${activeTab === 'archive' ? 'active' : ''}`} onClick={() => setActiveTab('archive')} onKeyDown={(event) => handleMenuKeyDown(event, () => setActiveTab('archive'))} role="button" tabIndex={0} aria-current={activeTab === 'archive' ? 'page' : undefined} title="陪伴档案">
              <div className="menu-item-left"><Heart size={18} strokeWidth={2} aria-hidden="true" /><span>陪伴档案</span></div>
              <ChevronRight className="menu-item-arrow" size={14} strokeWidth={2} aria-hidden="true" />
            </div>
            <div className={`menu-item ${activeTab === 'knowledge' ? 'active' : ''}`} onClick={() => setActiveTab('knowledge')} onKeyDown={(event) => handleMenuKeyDown(event, () => setActiveTab('knowledge'))} role="button" tabIndex={0} aria-current={activeTab === 'knowledge' ? 'page' : undefined} title="知识图谱">
              <div className="menu-item-left"><Network size={18} strokeWidth={2} aria-hidden="true" /><span>知识图谱</span></div>
              <ChevronRight className="menu-item-arrow" size={14} strokeWidth={2} aria-hidden="true" />
            </div>
            <div
              className={`menu-item ${activeTab === 'rpa' ? 'active' : ''}`}
              onClick={() => { void selectRpaTask(null).then(() => setActiveTab('rpa')) }}
              onKeyDown={(event) => handleMenuKeyDown(event, () => { void selectRpaTask(null).then(() => setActiveTab('rpa')) })}
              role="button"
              tabIndex={0}
              aria-current={activeTab === 'rpa' ? 'page' : undefined}
              title="RPA 任务"
            >
              <div className="menu-item-left"><Workflow size={18} strokeWidth={2} aria-hidden="true" /><span>自动化</span></div>
              <ChevronRight className="menu-item-arrow" size={14} strokeWidth={2} aria-hidden="true" />
            </div>
            <div className={`menu-item ${activeTab === 'logs' ? 'active' : ''}`} onClick={() => setActiveTab('logs')} onKeyDown={(event) => handleMenuKeyDown(event, () => setActiveTab('logs'))} role="button" tabIndex={0} aria-current={activeTab === 'logs' ? 'page' : undefined} title="使用记录">
              <div className="menu-item-left"><ScrollText size={18} strokeWidth={2} aria-hidden="true" /><span>使用记录</span></div>
              <ChevronRight className="menu-item-arrow" size={14} strokeWidth={2} aria-hidden="true" />
            </div>
            <div className={`menu-item ${activeTab === 'settings' ? 'active' : ''}`} onClick={() => setActiveTab('settings')} onKeyDown={(event) => handleMenuKeyDown(event, () => setActiveTab('settings'))} role="button" tabIndex={0} aria-current={activeTab === 'settings' ? 'page' : undefined} title="设置">
              <div className="menu-item-left"><SettingsIcon /><span>设置</span></div>
              <ChevronRight className="menu-item-arrow" size={14} strokeWidth={2} aria-hidden="true" />
            </div>
          </div>

          <div className="sidebar-footer">
            <button className="theme-toggle-icon-btn" onClick={handleThemeToggle} title="切换主题" aria-label={theme === 'dark' ? '切换到浅色主题' : '切换到深色主题'}>
              {theme === 'dark'
                ? <Sun size={18} strokeWidth={2} aria-hidden="true" />
                : <Moon size={18} strokeWidth={2} aria-hidden="true" />}
            </button>
          </div>
        </div>

        <div className={`sidebar-session-panel ${isCollapsed ? 'is-hidden' : ''}`}>
            <div className="sidebar-session-drag" style={{ WebkitAppRegion: 'drag' } as React.CSSProperties & { WebkitAppRegion: string }} />
            <div className="sidebar-session-header">
              <div className="sidebar-session-heading">
                <div className="sidebar-session-title-row">
                  <span>会话</span>
                  <button
                    type="button"
                    className="sidebar-session-toggle"
                    onClick={() => setIsCollapsed(true)}
                    aria-label="Collapse sessions"
                    title="Collapse sessions"
                  >
                    <PanelLeftClose size={15} strokeWidth={2} aria-hidden="true" />
                  </button>
                </div>
                <span className="brand-status" title={currentAvatarName}>
                  <span className="status-dot-pulse"></span>
                  {currentAvatarName}
                </span>
              </div>
            </div>

            <div className="new-chat-btn-wrapper">
              <button className="new-chat-btn" onClick={handleCreateNewSession} title="创建新会话" aria-label="创建新会话">
                <Plus size={17} strokeWidth={2} aria-hidden="true" />
                <span>新会话</span>
              </button>
            </div>

            <div className="sidebar-recent-title">
              <span>最近会话</span>
              {activePermissionRequest && (
                <span className="menu-sandbox-badge" title="有待审批的终端命令" onClick={() => setActiveTab('chat')}>●</span>
              )}
            </div>
            <RecentSessionList
              sessions={sessions}
              activeSessionId={activeSessionId}
              onSelect={(id) => { setActiveSessionId(id); setActiveTab('chat') }}
              onDelete={setSessionToDeleteId}
              onTogglePin={handleTogglePinSession}
              onRename={handleRenameSession}
              onReorder={handleReorderSessions}
            />
        </div>
      </div>

      {/* ── 2. Right Content Area ── */}
      <div className="agent-content-area">
        {/* ── 自定义标题栏 (Custom Titlebar) ── */}
        <div className="window-titlebar">
          <div className="titlebar-tabs-shell">
          <div
            ref={tabsViewportRef}
            className="titlebar-tabs"
            onWheel={handleTabsWheel}
            onDoubleClick={() => handleCreateNewSession()}
          >
            {workspaceTabs.map(tab => {
              const session = tab.kind === 'session'
                ? sessionsById.get(tab.sessionId)
                : undefined
              if (tab.kind === 'session' && !session) return null
              const isActive = tab.key === activeWorkspaceKey
              const isThinking = tab.kind === 'session' && checkIsThinking(session)
              const label = getWorkspaceTabLabel(tab)
              return (
                <div
                  key={tab.key}
                  ref={element => {
                    if (element) tabElementRefs.current.set(tab.key, element)
                    else tabElementRefs.current.delete(tab.key)
                  }}
                  className={`titlebar-tab ${tab.kind === 'page' ? 'function-tab' : ''} ${isActive ? 'active' : ''} ${isThinking ? 'thinking' : ''}`}
                  onClick={() => activateWorkspaceTab(tab)}
                >
                  {isThinking && <span className="tab-status-dot-pulse"></span>}
                  <span className="titlebar-tab-name" title={label}>{label}</span>
                  <span
                    className="titlebar-tab-close"
                    onClick={(e) => handleCloseTab(tab.key, e)}
                    title="关闭标签页"
                  >
                    <X size={12} strokeWidth={2} aria-hidden="true" />
                  </span>
                </div>
              )
            })}

            <button
              className="titlebar-new-tab-btn"
              onClick={() => handleCreateNewSession()}
              title="新建会话"
            >
              <Plus size={15} strokeWidth={2} aria-hidden="true" />
            </button>
          </div>
          {hiddenTabs.length > 0 && (
            <div className="titlebar-tab-overflow" ref={tabOverflowMenuRef}>
              <button
                className={`titlebar-tab-overflow-btn ${showTabOverflowMenu ? 'active' : ''}`}
                onClick={() => setShowTabOverflowMenu(prev => !prev)}
                title={`还有 ${hiddenTabs.length} 个标签`}
                aria-label="显示更多标签"
                aria-expanded={showTabOverflowMenu}
              >
                <MoreHorizontal size={16} strokeWidth={2} aria-hidden="true" />
              </button>
              {showTabOverflowMenu && (
                <div className="titlebar-tab-overflow-menu">
                  {hiddenTabs.map(tab => (
                    <div
                      key={tab.key}
                      className={`titlebar-tab-overflow-item ${tab.key === activeWorkspaceKey ? 'active' : ''}`}
                      onClick={() => {
                        activateWorkspaceTab(tab)
                        setShowTabOverflowMenu(false)
                      }}
                    >
                      <span title={getWorkspaceTabLabel(tab)}>{getWorkspaceTabLabel(tab)}</span>
                      <span
                        className="titlebar-tab-overflow-close"
                        onClick={event => {
                          handleCloseTab(tab.key, event)
                          setShowTabOverflowMenu(false)
                        }}
                        title="关闭标签页"
                      >
                        <X size={12} strokeWidth={2} aria-hidden="true" />
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
          <div className="titlebar-drag-region" aria-hidden="true" />
          </div>

        </div>

        <div className="content-layout">
          <div className={`content-main-column workspace-panel-column ${activeTab === 'chat' ? 'chat-panel-column' : ''}`}>
          {activeTab !== 'rpa' && (
        <div className={`content-header ${activeTab === 'chat' ? 'chat-content-header' : ''}`} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div>
            <div className="content-title">
              {activeTab === 'chat' && (sessions.find(s => s.id === activeSessionId)?.name || '本地安全沙箱会话')}
              {activeTab === 'control' && '连接与服务'}
              {activeTab === 'agent' && '能力中心'}
              {activeTab === 'archive' && '陪伴档案'}
              {activeTab === 'knowledge' && '知识图谱'}
              {activeTab === 'logs' && 'Token 消耗与模型日志统计'}
              {activeTab === 'settings' && '系统设置'}
            </div>
            {activeTab !== 'chat' && (
              <div className="content-subtitle">
                {activeTab === 'control' && '管理外部连接、订阅与可用服务'}
                {activeTab === 'agent' && `管理技能、记忆与 MCP 能力 · ${skillsList.length} 项扩展 · ${contextRounds} 轮上下文`}
                {activeTab === 'archive' && '查看陪伴分数、数据库记忆与对话历史'}
                {activeTab === 'knowledge' && '探索实体、关系与可追溯证据'}
                {activeTab === 'logs' && '实时监测大语言模型调用频率及 Token 开销走势'}
                {activeTab === 'settings' && '大模型与虚拟体模拟配置项'}
              </div>
            )}
          </div>

          {/* 右侧工具栏 */}
          {activeTab === 'chat' && (
            <div style={{ position: 'relative' }} ref={historyDropdownRef}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                <button
                  className={`history-btn ${showFilePanel ? 'active' : ''}`}
                  onClick={() => {
                    if (showFilePanel) {
                      setShowFilePanel(false)
                    } else {
                      void store.loadGeneratedFiles()
                      setPreviewFile(null)
                      setOpenTabs([])
                      setShowFilePanel(true)
                    }
                  }}
                  title={showFilePanel ? '关闭文件预览区域' : '打开文件预览区域'}
                  aria-label={showFilePanel ? '关闭文件预览区域' : '打开文件预览区域'}
                >
                  {showFilePanel
                    ? <PanelRightClose size={18} strokeWidth={2} aria-hidden="true" />
                    : <PanelRightOpen size={18} strokeWidth={2} aria-hidden="true" />}
                </button>
                <button
                  className="history-btn"
                  onClick={() => setShowHistoryDropdown(!showHistoryDropdown)}
                  title="查看历史提问"
                  aria-label="查看历史提问"
                  aria-expanded={showHistoryDropdown}
                >
                  <List size={18} strokeWidth={2} aria-hidden="true" />
                </button>
              </div>

              {showHistoryDropdown && (
                <div className="history-dropdown">
                  <div className="history-dropdown-header">
                    <div className="history-dropdown-title">
                      <span>历史提问</span>
                      <span className="history-dropdown-count">
                        {historySearchQuery.trim() ? `${filteredHistoryMessages.length}/${userMessages.length}` : userMessages.length}
                      </span>
                    </div>
                    <div className="history-search-field">
                      <Search size={14} strokeWidth={2} aria-hidden="true" />
                      <input
                        value={historySearchQuery}
                        onChange={event => setHistorySearchQuery(event.target.value)}
                        placeholder="搜索提问内容"
                        aria-label="搜索历史提问"
                      />
                      {historySearchQuery && (
                        <button
                          type="button"
                          onClick={() => setHistorySearchQuery('')}
                          title="清除搜索"
                          aria-label="清除搜索"
                        >
                          <X size={13} strokeWidth={2} aria-hidden="true" />
                        </button>
                      )}
                    </div>
                  </div>
                  <div className="history-dropdown-list">
                    {filteredHistoryMessages.length > 0 ? (
                      filteredHistoryMessages.map(msg => (
                        <button
                          key={msg.id}
                          type="button"
                          className="history-item"
                          onClick={() => {
                            setHighlightedMessageId(msg.id)
                            setShowHistoryDropdown(false)
                          }}
                          title={normalizeSearchCitations(msg.text, 'plain')}
                        >
                          {normalizeSearchCitations(msg.text, 'plain')}
                        </button>
                      ))
                    ) : (
                      <div className="history-empty">
                        {historySearchQuery.trim() ? '没有匹配的提问' : '暂无提问记录'}
                      </div>
                    )}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
        )}
        <div key={activeTab} className={`content-body tab-${activeTab}`} style={{ flex: 1, minWidth: 0 }}>
            {renderPage()}
        </div>
          </div>

        {/* 右侧文件面板 */}
        {activeTab === 'chat' && !showFilePanel && (
          <aside className="companion-dock" aria-label="陪伴侧舱">
            <div className="companion-dock-profile">
              {companionPanel && (
                <button
                  type="button"
                  className="companion-dock-back"
                  onClick={() => setCompanionPanel(null)}
                  aria-label="返回对话"
                  title="返回对话"
                >
                  <ArrowLeft size={15} strokeWidth={2} aria-hidden="true" />
                </button>
              )}
              <div className="companion-dock-avatar">
                <img src={iconFromImage} alt="" aria-hidden="true" />
                <span className="companion-dock-status" />
              </div>
              <div>
                <strong>{currentAvatarName} 在这里</strong>
                <span>本地陪伴在线</span>
              </div>
            </div>

            {companionPanel ? (
              <div className="companion-dock-preview" key={companionPanel}>
                <span className="companion-dock-label">{companionPanel === 'memory' ? '记忆速览' : '关系速览'}</span>
                <h3>{companionPanel === 'memory' ? '我正在记住什么' : '与你有关的线索'}</h3>
                {companionPanel === 'memory' ? (
                  companionMemoryMessages.length > 0 ? (
                    <div className="companion-preview-list">
                      {companionMemoryMessages.map(message => (
                        <div className={`companion-preview-message ${message.sender === 'user' ? 'is-user' : ''}`} key={message.id}>
                          <span>{message.sender === 'user' ? '你' : currentAvatarName}</span>
                          <p>{normalizeSearchCitations(message.text || '', 'plain').replace(/\s+/g, ' ').trim().slice(0, 96)}</p>
                        </div>
                      ))}
                    </div>
                  ) : <div className="companion-preview-empty">还没有可预览的记忆</div>
                ) : companionKnowledgeLoading ? (
                  <div className="companion-preview-loading"><span /><span /><span /></div>
                ) : (companionKnowledge?.nodes?.length || 0) > 0 ? (
                  <div className="companion-knowledge-list">
                    {companionKnowledge?.nodes?.slice(0, 6).map(node => {
                      const relation = companionKnowledge.edges?.find(edge => edge.source === node.id || edge.target === node.id)
                      const linkedId = relation ? (relation.source === node.id ? relation.target : relation.source) : ''
                      const linkedNode = companionKnowledge.nodes?.find(item => item.id === linkedId)
                      return (
                        <div className="companion-knowledge-item" key={node.id}>
                          <div><strong>{node.label}</strong><span>{node.type || '线索'}</span></div>
                          {linkedNode && <p>{relation?.label || '关联'} · {linkedNode.label}</p>}
                        </div>
                      )
                    })}
                  </div>
                ) : <div className="companion-preview-empty">还没有可预览的关系</div>}
                <div className="companion-dock-preview-meta">
                  <span>{companionPanel === 'memory' ? `${contextRounds} 轮上下文` : `${companionKnowledge?.nodes?.length || 0} 个节点`}</span>
                  <span>本地存储</span>
                </div>
              </div>
            ) : <div className="companion-dock-section">
              <span className="companion-dock-label">当前状态</span>
              <p>{activePermissionRequest ? '有一项操作等待你的确认' : '可以陪你聊天，也可以直接帮你做事'}</p>
            </div>}

            {!companionPanel && <div className="companion-dock-section companion-dock-stats">
              <span className="companion-dock-label">这个工作区</span>
              <div className="companion-dock-stat"><b>{contextRounds}</b><span>轮上下文</span></div>
              <div className="companion-dock-stat"><b>{activeSessMessages.length}</b><span>条当前消息</span></div>
            </div>}

            {!companionPanel && <div className="companion-dock-actions">
              <button type="button" onClick={() => openCompanionPanel('memory')}>
                <Lightbulb size={15} strokeWidth={2} aria-hidden="true" />
                <span>查看记忆</span>
              </button>
              <button type="button" onClick={() => openCompanionPanel('knowledge')}>
                <Network size={15} strokeWidth={2} aria-hidden="true" />
                <span>探索关系</span>
              </button>
            </div>}

            <p className="companion-dock-note">信息默认保存在本地</p>
          </aside>
        )}

        {showFilePanel && activeTab === 'chat' && (
          <Suspense fallback={<PageLoadingFallback />}>
            <FilePreviewPanel store={store} />
          </Suspense>
        )}
        </div>
      </div>

      <div className="window-controls-overlay" aria-label="窗口控制">
        <button
          className="titlebar-control-btn"
          onClick={() => window.api?.minimizeAgentWindow()}
          title="最小化"
          aria-label="最小化"
        >
          <Minus size={12} strokeWidth={1.8} aria-hidden="true" />
        </button>
        <button
          className="titlebar-control-btn"
          onClick={() => {
            window.api?.maximizeAgentWindow()
            setTimeout(checkMaximized, 100)
          }}
          title={isMaximized ? '向下还原' : '最大化'}
          aria-label={isMaximized ? '向下还原' : '最大化'}
        >
          {isMaximized
            ? <Copy size={11} strokeWidth={1.6} aria-hidden="true" />
            : <Square size={10} strokeWidth={1.6} aria-hidden="true" />}
        </button>
        <button
          className="titlebar-control-btn close"
          onClick={() => window.api?.closeAgentWindow()}
          title="关闭"
          aria-label="关闭"
        >
          <X size={12} strokeWidth={1.6} aria-hidden="true" />
        </button>
      </div>

      {/* 删除会话二次确认弹框 */}
      {sessionToDeleteId && (
        <div className="mcp-modal-overlay">
          <div className="mcp-modal-card" style={{ maxWidth: '380px', width: '90%' }}>
            <div className="mcp-modal-header" style={{ padding: '16px 20px', borderBottom: '1px solid var(--border-color, var(--ds-color-7267626128313238))' }}>
              <div className="mcp-modal-title" style={{ fontSize: '16px', fontWeight: 'bold', color: 'var(--text-primary)' }}>
                删除
              </div>
              <button className="mcp-modal-close-btn" onClick={() => setSessionToDeleteId(null)} title="关闭">
                <X size={18} strokeWidth={2} aria-hidden="true" />
              </button>
            </div>
            <div className="mcp-modal-body" style={{ padding: '24px 20px', fontSize: '13px', color: 'var(--text-secondary, var(--ds-color-23363636))', lineHeight: '1.6' }}>
              您即将删除此话题，此操作无法撤销。
            </div>
            <div className="mcp-modal-footer" style={{ padding: '12px 20px 16px', display: 'flex', justifyContent: 'flex-end', gap: '12px', borderTop: 'none' }}>
              <button
                onClick={() => setSessionToDeleteId(null)}
                style={{
                  padding: '6px 18px',
                  borderRadius: '6px',
                  border: '1px solid var(--border-color, var(--ds-color-7267626128313238))',
                  background: 'var(--bg-card, #ffffff)',
                  color: 'var(--text-primary, var(--ds-color-23333333))',
                  cursor: 'pointer',
                  fontSize: '12.5px',
                  fontWeight: 500,
                  transition: 'background 0.2s'
                }}
                onMouseEnter={e => e.currentTarget.style.background = 'var(--bg-hover, var(--ds-color-7267626128313238))'}
                onMouseLeave={e => e.currentTarget.style.background = 'var(--bg-card, #ffffff)'}
              >
                取消
              </button>
              <button
                onClick={() => {
                  if (sessionToDeleteId) {
                    handleDeleteSession(sessionToDeleteId)
                    setSessionToDeleteId(null)
                  }
                }}
                style={{
                  padding: '6px 18px',
                  borderRadius: '6px',
                  border: 'none',
                  background: 'var(--ds-color-23653035333363)', // 珊瑚红/橙红色
                  color: '#ffffff',
                  cursor: 'pointer',
                  fontSize: '12.5px',
                  fontWeight: 'bold',
                  boxShadow: '0 2px 6px rgba(224, 83, 60, 0.15)',
                  transition: 'filter 0.2s'
                }}
                onMouseEnter={e => e.currentTarget.style.filter = 'brightness(1.05)'}
                onMouseLeave={e => e.currentTarget.style.filter = 'none'}
              >
                删除
              </button>
            </div>
          </div>
        </div>
      )}

      {/* API Key 引导配置弹窗 */}
      {showApiKeyModal && (
        <div
          className="mcp-modal-overlay"
          style={{
            backdropFilter: 'blur(10px)',
            backgroundColor: 'rgba(0, 0, 0, 0.45)',
            zIndex: 99999
          }}
        >
          <style>{`
            @keyframes modalSlideIn {
              from {
                opacity: 0;
                transform: scale(0.95) translateY(10px);
              }
              to {
                opacity: 1;
                transform: scale(1) translateY(0);
              }
            }
          `}</style>
          <div
            className="mcp-modal-card"
            style={{
              maxWidth: '420px',
              width: '90%',
              background: 'var(--bg-card, rgba(255, 255, 255, 0.85))',
              backdropFilter: 'blur(20px)',
              WebkitBackdropFilter: 'blur(20px)',
              border: '1px solid var(--border-color, rgba(255, 255, 255, 0.25))',
              boxShadow: '0 20px 50px rgba(0, 0, 0, 0.15), inset 0 1px 1px rgba(255, 255, 255, 0.2)',
              borderRadius: '16px',
              animation: 'modalSlideIn 0.22s cubic-bezier(0.2, 0.8, 0.2, 1)'
            }}
          >
            <div className="mcp-modal-header" style={{ padding: '18px 24px', borderBottom: '1px solid var(--border-color, var(--ds-color-7267626128313238))', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div className="mcp-modal-title" style={{ fontSize: '16px', fontWeight: 'bold', display: 'flex', alignItems: 'center', gap: '8px', color: 'var(--text-primary)' }}>
                <KeyRound size={18} strokeWidth={2} aria-hidden="true" />
                <span>缺少大模型配置</span>
              </div>
              <button
                className="mcp-modal-close-btn"
                style={{ fontSize: '20px', background: 'transparent', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}
                onClick={() => setShowApiKeyModal(false)}
              >
                <X size={18} strokeWidth={2} aria-hidden="true" />
              </button>
            </div>
            <div className="mcp-modal-body" style={{ padding: '24px', fontSize: '13.5px', color: 'var(--text-secondary, var(--ds-color-23346235353633))', lineHeight: '1.6' }}>
              <p style={{ margin: 0, fontWeight: 500, color: 'var(--text-primary)' }}>
                为了体验桌宠 {currentAvatarName} 的全部智能交互功能，建议您先配置大模型 API 密钥。
              </p>
              <p style={{ margin: '10px 0 0 0', fontSize: '12.5px', color: 'var(--text-muted, var(--ds-color-23366237323830))' }}>
                未配置 Key 状态下将无法开启 AI 聊天、代码编写、定时运行、系统操作等核心功能。
              </p>
            </div>
            <div className="mcp-modal-footer" style={{ padding: '16px 24px 20px', display: 'flex', justifyContent: 'flex-end', gap: '12px', borderTop: 'none', background: 'transparent' }}>
              <button
                onClick={() => setShowApiKeyModal(false)}
                style={{
                  padding: '8px 18px',
                  borderRadius: '8px',
                  border: '1px solid var(--border-color, var(--ds-color-7267626128313238))',
                  background: 'transparent',
                  color: 'var(--text-primary, var(--ds-color-23333734313531))',
                  cursor: 'pointer',
                  fontSize: '12.5px',
                  fontWeight: 500,
                  transition: 'all 0.2s'
                }}
                onMouseEnter={e => {
                  e.currentTarget.style.background = 'var(--bg-hover, var(--ds-color-7267626128313238))'
                }}
                onMouseLeave={e => {
                  e.currentTarget.style.background = 'transparent'
                }}
              >
                暂不配置
              </button>
              <button
                onClick={() => {
                  setShowApiKeyModal(false)
                  setActiveTab('settings')
                  setSettingsSubTab('keys')
                }}
                style={{
                  padding: '8px 20px',
                  borderRadius: '8px',
                  border: 'none',
                  background: 'linear-gradient(135deg, var(--ds-color-23346638636666) 0%, var(--ds-color-23336238326636) 100%)',
                  color: '#ffffff',
                  cursor: 'pointer',
                  fontSize: '12.5px',
                  fontWeight: 'bold',
                  boxShadow: '0 4px 12px rgba(79, 140, 255, 0.3)',
                  transition: 'all 0.2s'
                }}
                onMouseEnter={e => {
                  e.currentTarget.style.filter = 'brightness(1.08)'
                  e.currentTarget.style.boxShadow = '0 6px 16px rgba(79, 140, 255, 0.4)'
                }}
                onMouseLeave={e => {
                  e.currentTarget.style.filter = 'none'
                  e.currentTarget.style.boxShadow = '0 4px 12px rgba(79, 140, 255, 0.3)'
                }}
              >
                前往配置 API Key
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Global Toast */}
      {toast && (
        <div
          className={`global-toast-notification ${toast.type} ${activePermissionRequest ? 'has-approval' : ''}`}
          role="button"
          tabIndex={0}
          title="点击回到 MindPet"
          onClick={() => window.api?.openAgentWindow?.()}
          onKeyDown={(event) => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault()
              window.api?.openAgentWindow?.()
            }
          }}
        >
          <span className="toast-icon">
            {toast.type === 'success' && <CheckCircle2 size={18} strokeWidth={2} aria-hidden="true" />}
            {toast.type === 'error' && <CircleX size={18} strokeWidth={2} aria-hidden="true" />}
            {toast.type === 'info' && <Lightbulb size={18} strokeWidth={2} aria-hidden="true" />}
          </span>
          <span className="toast-message">{toast.message}</span>
        </div>
      )}

      {/* 全局初始化过渡页面 */}
      {showSplash && (
        <div
          className={`splash-container ${splashFadeOut ? 'fade-out' : ''}`}
          role="status"
          aria-label="正在加载 MindPet"
        >
          <div className="splash-title">MindPet</div>
        </div>
      )}

    </div>
  )
}
