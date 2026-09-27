import React from 'react'
import {
  ArrowUp,
  BookOpen,
  Brain,
  CalendarDays,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Heart,
  ImagePlus,
  Images,
  MessageCircle,
  Quote,
  RefreshCw,
  Send,
  Sparkles,
  Trash2,
  X
} from 'lucide-react'
import '../assets/memory-cosmos.css'

type ViewMode = 'gallery' | 'portrait'

interface GalleryItem {
  id: string
  source_type?: string
  image_uri?: string
  title?: string
  story?: string
  mood?: string
  ai_summary?: string
  source_context?: string
  event_at?: string
}

interface ProfileItem {
  category?: string
  prop_key?: string
  prop_value?: string
  updated_at?: string
}

interface InsightItem {
  id?: string | number
  insight?: string
  context?: string
  created_at?: string
}

interface GrowthItem extends InsightItem { category?: string }
interface RememberedItem {
  id: string
  kind?: string
  title?: string
  understanding?: string
  content?: string
  evidence?: string | {
    available?: boolean
    context?: string
    userMessage?: string
    assistantReply?: string
    turnId?: string
  }
  source_label?: string
  source_type?: string
  source_session_id?: string
  source_gallery_id?: string
  user_message?: string
  assistant_reply?: string
  image_uri?: string
  category?: string
  categoryLabel?: string
  created_at?: string
  event_at?: string
  occurredAt?: string
  rememberedAt?: string
  time?: string
  source?: {
    recordType?: string
    channel?: string
    label?: string
    sessionId?: string
    galleryItemId?: string
    imageUri?: string
  }
}
interface PortraitData {
  workingMemory: string
  profile: ProfileItem[]
  insights: InsightItem[]
  growth: GrowthItem[]
  memories: RememberedItem[]
}

const moodMeta: Record<string, { label: string; color: string }> = {
  happy: { label: '开心', color: '#f0bd73' },
  excited: { label: '雀跃', color: '#e9a66f' },
  grateful: { label: '感激', color: '#9fc8c2' },
  relieved: { label: '放松', color: '#8fb9ac' },
  sad: { label: '低落', color: '#8ea2bd' },
  lonely: { label: '孤单', color: '#8193ad' },
  anxious: { label: '不安', color: '#c49a83' },
  stressed: { label: '有压力', color: '#bd7d79' },
  angry: { label: '生气', color: '#c47f78' },
  neutral: { label: '平静', color: '#aeb9c6' }
}

const categoryNames: Record<string, string> = {
  identity: '关于你',
  preference: '你的偏好',
  experience: '重要经历',
  state: '持续状态',
  profile: '整体印象'
}

const cosmosStars = Array.from({ length: 84 }, (_, index) => ({
  x: (index * 47 + 13) % 101,
  y: (index * 71 + 17) % 97,
  size: index % 13 === 0 ? 3 : index % 5 === 0 ? 2 : 1,
  depth: index % 4,
  delay: -((index * 0.61) % 12),
  duration: 8 + (index * 7) % 15
}))

const text = (value: unknown): string => value == null ? '' : String(value)

function dateLabel(value?: string): string {
  if (!value) return '刚刚'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }).format(parsed)
}

function localDateValue(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function parseLocalDate(value: string): Date {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) return new Date()
  return new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
}

function MemoryDatePicker({ value, disabled, onChange }: { value: string; disabled: boolean; onChange: (value: string) => void }): React.JSX.Element {
  const selectedDate = parseLocalDate(value)
  const [open, setOpen] = React.useState(false)
  const [visibleMonth, setVisibleMonth] = React.useState(() => new Date(selectedDate.getFullYear(), selectedDate.getMonth(), 1))
  const [activeDate, setActiveDate] = React.useState(selectedDate)
  const pickerRef = React.useRef<HTMLDivElement | null>(null)
  const triggerRef = React.useRef<HTMLButtonElement | null>(null)
  const dayRefs = React.useRef(new Map<string, HTMLButtonElement>())
  const activeValue = localDateValue(activeDate)

  React.useEffect(() => {
    if (!open) return
    const closeOnOutsidePointer = (event: PointerEvent): void => {
      if (event.target instanceof Node && !pickerRef.current?.contains(event.target)) setOpen(false)
    }
    document.addEventListener('pointerdown', closeOnOutsidePointer)
    return () => document.removeEventListener('pointerdown', closeOnOutsidePointer)
  }, [open])

  React.useEffect(() => {
    if (open) requestAnimationFrame(() => dayRefs.current.get(activeValue)?.focus())
  }, [activeValue, open])

  const focusDate = (date: Date): void => {
    setActiveDate(date)
    if (date.getMonth() !== visibleMonth.getMonth() || date.getFullYear() !== visibleMonth.getFullYear()) {
      setVisibleMonth(new Date(date.getFullYear(), date.getMonth(), 1))
    }
  }

  const shiftMonth = (amount: number): void => {
    const nextMonth = new Date(visibleMonth.getFullYear(), visibleMonth.getMonth() + amount, 1)
    const lastDay = new Date(nextMonth.getFullYear(), nextMonth.getMonth() + 1, 0).getDate()
    const nextActive = new Date(nextMonth.getFullYear(), nextMonth.getMonth(), Math.min(activeDate.getDate(), lastDay))
    setVisibleMonth(nextMonth)
    setActiveDate(nextActive)
  }

  const changeActiveBy = (amount: number): void => {
    const next = new Date(activeDate.getFullYear(), activeDate.getMonth(), activeDate.getDate() + amount)
    focusDate(next)
  }

  const chooseDate = (date: Date): void => {
    onChange(localDateValue(date))
    setOpen(false)
    requestAnimationFrame(() => triggerRef.current?.focus())
  }

  const monthTitle = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long' }).format(visibleMonth)
  const selectedLabel = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }).format(selectedDate)
  const todayValue = localDateValue(new Date())
  const firstWeekday = (new Date(visibleMonth.getFullYear(), visibleMonth.getMonth(), 1).getDay() + 6) % 7
  const daysInMonth = new Date(visibleMonth.getFullYear(), visibleMonth.getMonth() + 1, 0).getDate()
  const cells: (Date | null)[] = [...Array.from({ length: firstWeekday }, () => null), ...Array.from({ length: daysInMonth }, (_, index) => new Date(visibleMonth.getFullYear(), visibleMonth.getMonth(), index + 1))]
  while (cells.length % 7 !== 0) cells.push(null)

  return <div ref={pickerRef} className={`memory-date-picker ${open ? 'is-open' : ''}`}>
    <button
      ref={triggerRef}
      className="memory-date-trigger"
      type="button"
      disabled={disabled}
      aria-haspopup="dialog"
      aria-expanded={open}
      aria-label={`发生日期：${selectedLabel}`}
      aria-controls={open ? 'memory-share-calendar' : undefined}
      onClick={() => {
        if (!open) {
          const current = parseLocalDate(value)
          setVisibleMonth(new Date(current.getFullYear(), current.getMonth(), 1))
          setActiveDate(current)
        }
        setOpen(current => !current)
      }}
    ><span>{selectedLabel}</span><CalendarDays size={16} aria-hidden="true" /></button>
    {open && <div id="memory-share-calendar" className="memory-calendar-popover" role="dialog" aria-label="选择发生日期" onKeyDown={event => {
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); setOpen(false); triggerRef.current?.focus() }
    }}>
      <div className="memory-calendar-heading">
        <strong>{monthTitle}</strong>
        <div>
          <button type="button" aria-label="上个月" onClick={() => shiftMonth(-1)}><ChevronLeft size={16} /></button>
          <button type="button" aria-label="下个月" onClick={() => shiftMonth(1)}><ChevronRight size={16} /></button>
        </div>
      </div>
      <div className="memory-calendar-grid" role="grid" aria-label={monthTitle} onKeyDown={event => {
        if (event.key === 'ArrowLeft') { event.preventDefault(); changeActiveBy(-1) }
        else if (event.key === 'ArrowRight') { event.preventDefault(); changeActiveBy(1) }
        else if (event.key === 'ArrowUp') { event.preventDefault(); changeActiveBy(-7) }
        else if (event.key === 'ArrowDown') { event.preventDefault(); changeActiveBy(7) }
        else if (event.key === 'Home') { event.preventDefault(); changeActiveBy(-((activeDate.getDay() + 6) % 7)) }
        else if (event.key === 'End') { event.preventDefault(); changeActiveBy(6 - ((activeDate.getDay() + 6) % 7)) }
        else if (event.key === 'PageUp') { event.preventDefault(); shiftMonth(event.shiftKey ? -12 : -1) }
        else if (event.key === 'PageDown') { event.preventDefault(); shiftMonth(event.shiftKey ? 12 : 1) }
        else if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); setOpen(false); triggerRef.current?.focus() }
      }}>
        <div className="memory-calendar-week" role="row">{['一', '二', '三', '四', '五', '六', '日'].map((day, index) => <span key={day} role="columnheader" aria-label={`星期${day}`} className={index > 4 ? 'is-weekend' : ''}>{day}</span>)}</div>
        {Array.from({ length: cells.length / 7 }, (_, row) => <div className="memory-calendar-week" role="row" key={row}>
          {cells.slice(row * 7, row * 7 + 7).map((date, column) => {
            if (!date) return <span className="memory-calendar-blank" key={`blank-${row}-${column}`} aria-hidden="true" />
            const dateValue = localDateValue(date)
            return <button
              key={dateValue}
              ref={element => { if (element) dayRefs.current.set(dateValue, element); else dayRefs.current.delete(dateValue) }}
              type="button"
              role="gridcell"
              tabIndex={dateValue === activeValue ? 0 : -1}
              aria-label={new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(date)}
              aria-selected={dateValue === value}
              aria-current={dateValue === todayValue ? 'date' : undefined}
              className={`${dateValue === value ? 'is-selected' : ''} ${dateValue === todayValue ? 'is-today' : ''} ${column > 4 ? 'is-weekend' : ''}`}
              onFocus={() => setActiveDate(date)}
              onClick={() => chooseDate(date)}
            >{date.getDate()}</button>
          })}
        </div>)}
      </div>
      <button className="memory-calendar-today" type="button" onClick={() => chooseDate(new Date())}>回到今天</button>
    </div>}
  </div>
}

function shortDate(value?: string): string {
  if (!value) return '此刻'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', { year: '2-digit', month: 'short' }).format(parsed)
}

function cleanWorkingMemory(value: string): string {
  return value
    .replace(/^## 当前状态\s*/m, '')
    .replace(/^待跟进：/m, '接下来还想聊：')
    .replace(/^最近情绪：/m, '最近的情绪：')
    .trim()
}

function memoryTitle(item: GalleryItem): string {
  if (item.story?.trim()) return item.story.trim().slice(0, 28)
  if (item.title?.trim() && item.title !== '一段新回忆') return item.title.trim()
  return `留在 ${dateLabel(item.event_at)} 的瞬间`
}

function rememberedTitle(item: RememberedItem): string {
  const explicit = text(item.title).trim()
  if (explicit) return explicit
  const understanding = text(item.understanding || item.content).trim()
  if (!understanding) return '一件我想好好记住的事'
  return understanding.length > 34 ? `${understanding.slice(0, 34)}…` : understanding
}

function rememberedDate(item: RememberedItem): string {
  return dateLabel(item.occurredAt || item.rememberedAt || item.time || item.event_at || item.created_at)
}

function rememberedSourceLabel(item: RememberedItem): string {
  return text(item.source?.label || item.source_label || item.categoryLabel || item.category || '来自真实对话')
}

function rememberedEvidence(item: RememberedItem): string {
  if (typeof item.evidence === 'object') return text(item.evidence?.userMessage || item.user_message)
  return text(item.user_message)
}

function rememberedEvidenceContext(item: RememberedItem): string {
  if (typeof item.evidence === 'string') return text(item.evidence)
  return text(item.evidence?.context)
}

function rememberedReply(item: RememberedItem): string {
  if (typeof item.evidence === 'object') return text(item.evidence?.assistantReply || item.assistant_reply)
  return text(item.assistant_reply)
}

export function MemoryGalleryPage(): React.JSX.Element {
  const [view, setView] = React.useState<ViewMode>('gallery')
  const [items, setItems] = React.useState<GalleryItem[]>([])
  const [selectedId, setSelectedId] = React.useState<string | null>(null)
  const [portrait, setPortrait] = React.useState<PortraitData>({ workingMemory: '', profile: [], insights: [], growth: [], memories: [] })
  const [expandedMemoryId, setExpandedMemoryId] = React.useState<string | null>(null)
  const [portraitDraft, setPortraitDraft] = React.useState('')
  const [portraitReply, setPortraitReply] = React.useState('')
  const [portraitSaving, setPortraitSaving] = React.useState(false)
  const [loading, setLoading] = React.useState(true)
  const [error, setError] = React.useState('')
  const [composerOpen, setComposerOpen] = React.useState(false)
  const [confirmDeleteId, setConfirmDeleteId] = React.useState<string | null>(null)
  const [draft, setDraft] = React.useState({
    story: '',
    mood: 'neutral',
    eventAt: localDateValue(new Date()),
    imageUri: '',
    imageName: ''
  })
  const [saving, setSaving] = React.useState(false)
  const sceneRef = React.useRef<HTMLElement | null>(null)
  const dragRef = React.useRef<{ pointerId: number; startX: number; currentX: number; moved: boolean } | null>(null)
  const wheelLockRef = React.useRef(0)
  const shareDialogRef = React.useRef<HTMLElement | null>(null)
  const shareCloseRef = React.useRef<HTMLButtonElement | null>(null)
  const returnFocusRef = React.useRef<HTMLElement | null>(null)
  const savingRef = React.useRef(false)

  const loadAll = React.useCallback(async (focusId?: string): Promise<void> => {
    setLoading(true)
    setError('')
    try {
      const [galleryResult, portraitResult] = await Promise.all([
        window.api.getMemoryGallery(),
        window.api.getMemoryPortrait()
      ])
      if (galleryResult?.status !== 'ok') throw new Error(galleryResult?.message || '记忆星河暂时无法打开')
      const nextItems = Array.isArray(galleryResult.items) ? galleryResult.items : []
      setItems(nextItems)
      setSelectedId(current => {
        if (focusId && nextItems.some((item: GalleryItem) => item.id === focusId)) return focusId
        if (current && nextItems.some((item: GalleryItem) => item.id === current)) return current
        return nextItems[0]?.id || null
      })
      if (portraitResult?.status === 'ok') {
        const nextPortrait = {
          workingMemory: text(portraitResult.workingMemory),
          profile: Array.isArray(portraitResult.profile) ? portraitResult.profile : [],
          insights: Array.isArray(portraitResult.insights) ? portraitResult.insights : [],
          growth: Array.isArray(portraitResult.growth) ? portraitResult.growth : [],
          memories: Array.isArray(portraitResult.memories) ? portraitResult.memories : []
        }
        setPortrait(nextPortrait)
        setExpandedMemoryId(current => {
          if (current && nextPortrait.memories.some((item: RememberedItem) => item.id === current)) return current
          return nextPortrait.memories[0]?.id || null
        })
      } else {
        setError(portraitResult?.message || 'MindPet 暂时无法整理长期记忆')
      }
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : '记忆星河暂时无法打开')
    } finally {
      setLoading(false)
    }
  }, [])

  React.useEffect(() => { void loadAll() }, [loadAll])

  React.useEffect(() => { savingRef.current = saving }, [saving])

  const closeComposer = React.useCallback((): void => {
    if (savingRef.current) return
    const returnTarget = returnFocusRef.current
    setComposerOpen(false)
    requestAnimationFrame(() => {
      if (returnTarget?.isConnected) returnTarget.focus()
      else sceneRef.current?.focus()
      returnFocusRef.current = null
    })
  }, [])

  const openComposer = React.useCallback((): void => {
    returnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
    setComposerOpen(true)
  }, [])

  React.useEffect(() => {
    if (!composerOpen) return
    requestAnimationFrame(() => (shareCloseRef.current || shareDialogRef.current)?.focus())
    const handleKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        event.preventDefault()
        closeComposer()
        return
      }
      if (event.key !== 'Tab' || !shareDialogRef.current) return
      const controls = Array.from(shareDialogRef.current.querySelectorAll<HTMLElement>('button:not(:disabled), textarea:not(:disabled), input:not(:disabled), [tabindex]:not([tabindex="-1"])'))
      if (controls.length === 0) {
        event.preventDefault()
        shareDialogRef.current.focus()
        return
      }
      const first = controls[0]
      const last = controls[controls.length - 1]
      const activeIndex = controls.indexOf(document.activeElement as HTMLElement)
      if (event.shiftKey && activeIndex <= 0) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && (activeIndex === -1 || document.activeElement === last)) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [closeComposer, composerOpen])

  const selectedIndex = Math.max(0, items.findIndex(item => item.id === selectedId))

  const rememberedItems = React.useMemo<RememberedItem[]>(() => {
    if (portrait.memories.length > 0) return portrait.memories
    const profiles = portrait.profile.map((item, index) => ({
      id: `profile-${item.category || 'profile'}-${item.prop_key || index}`,
      title: text(item.prop_key) || categoryNames[item.category || ''] || '关于你',
      understanding: text(item.prop_value),
      source_label: '在相处中逐渐确认',
      source_type: 'profile',
      category: categoryNames[item.category || ''] || '稳定理解',
      created_at: item.updated_at
    }))
    const insights = portrait.insights.map((item, index) => ({
      id: `insight-${item.id || index}`,
      title: text(item.insight).slice(0, 34),
      understanding: text(item.insight),
      evidence: text(item.context),
      source_label: '一次对话里记住的',
      source_type: 'insight',
      category: '相处中的发现',
      created_at: item.created_at
    }))
    return [...profiles, ...insights].slice(0, 28)
  }, [portrait.insights, portrait.memories, portrait.profile])

  const choosePhoto = async (): Promise<void> => {
    const paths = await window.api.selectAttachmentFiles()
    const imagePath = paths.find(path => /\.(png|jpe?g|gif|webp)$/i.test(path))
    if (!imagePath) return
    const copied = await window.api.attachFileFromPath(imagePath, 'memory-gallery')
    if (!copied?.path) return
    setDraft(current => ({
      ...current,
      imageUri: `local-file:///${copied.path.replace(/\\/g, '/')}`,
      imageName: copied.name
    }))
  }

  const resetDraft = (): void => {
    setDraft({ story: '', mood: 'neutral', eventAt: localDateValue(new Date()), imageUri: '', imageName: '' })
  }

  const shareMemory = async (): Promise<void> => {
    if (!draft.imageUri || saving) return
    savingRef.current = true
    setSaving(true)
    setError('')
    try {
      const result = await window.api.shareMemoryGalleryItem(draft)
      if (result?.status !== 'ok' && result?.status !== 'partial') {
        setError(result?.message || '这次分享没有保存成功')
        return
      }
      resetDraft()
      await loadAll(result.id)
      if (result.status === 'partial') setError(result.message)
      savingRef.current = false
      setSaving(false)
      closeComposer()
    } catch (shareError) {
      setError(shareError instanceof Error ? shareError.message : '这次分享没有保存成功')
    } finally {
      savingRef.current = false
      setSaving(false)
    }
  }

  const removeMemory = async (item: GalleryItem): Promise<void> => {
    const result = await window.api.deleteMemoryGalleryItem(item.id)
    if (result?.status !== 'ok') {
      setError(result?.message || '删除失败')
      return
    }
    const nextItems = items.filter(entry => entry.id !== item.id)
    setItems(nextItems)
    setSelectedId(nextItems[Math.min(selectedIndex, Math.max(0, nextItems.length - 1))]?.id || null)
    setConfirmDeleteId(null)
    requestAnimationFrame(() => sceneRef.current?.focus())
  }

  const selectIndex = React.useCallback((nextIndex: number): void => {
    if (items.length === 0) return
    const clamped = Math.max(0, Math.min(items.length - 1, nextIndex))
    setConfirmDeleteId(null)
    if (document.activeElement instanceof HTMLElement && document.activeElement.classList.contains('memory-orbit-delete')) {
      sceneRef.current?.focus()
    }
    setSelectedId(items[clamped].id)
  }, [items])

  const onSceneWheel = (event: React.WheelEvent<HTMLElement>): void => {
    if (items.length < 2) return
    const now = Date.now()
    if (now < wheelLockRef.current) return
    const movement = Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX : event.deltaY
    if (Math.abs(movement) < 18) return
    event.preventDefault()
    wheelLockRef.current = now + 360
    selectIndex(selectedIndex + (movement > 0 ? 1 : -1))
  }

  const onScenePointerDown = (event: React.PointerEvent<HTMLElement>): void => {
    if (event.button !== 0) return
    if ((event.target as HTMLElement).closest('button, input, textarea, label')) return
    dragRef.current = { pointerId: event.pointerId, startX: event.clientX, currentX: event.clientX, moved: false }
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  const onScenePointerMove = (event: React.PointerEvent<HTMLElement>): void => {
    const drag = dragRef.current
    if (drag?.pointerId === event.pointerId) {
      const distance = event.clientX - drag.startX
      drag.currentX = event.clientX
      drag.moved = Math.abs(distance) > 8
      const preview = Math.max(-110, Math.min(110, distance))
      event.currentTarget.style.setProperty('--memory-drag-x', `${(preview * 0.14).toFixed(2)}px`)
      event.currentTarget.style.setProperty('--memory-drag-yaw', `${(preview * 0.025).toFixed(2)}deg`)
      return
    }
    const scene = sceneRef.current
    if (!scene || event.pointerType === 'touch') return
    const bounds = scene.getBoundingClientRect()
    const x = ((event.clientX - bounds.left) / bounds.width) - 0.5
    const y = ((event.clientY - bounds.top) / bounds.height) - 0.5
    scene.style.setProperty('--cosmos-shift-x', `${(-x * 16).toFixed(2)}px`)
    scene.style.setProperty('--cosmos-shift-y', `${(-y * 12).toFixed(2)}px`)
    scene.style.setProperty('--cosmos-rotate-x', `${(-y * 1.8).toFixed(2)}deg`)
    scene.style.setProperty('--cosmos-rotate-y', `${(x * 1.6).toFixed(2)}deg`)
  }

  const onScenePointerUp = (event: React.PointerEvent<HTMLElement>): void => {
    const drag = dragRef.current
    if (drag?.pointerId !== event.pointerId) return
    const distance = drag.currentX - drag.startX
    if (event.type !== 'pointercancel' && Math.abs(distance) > 58) {
      selectIndex(selectedIndex + (distance < 0 ? 1 : -1))
    }
    event.currentTarget.style.setProperty('--memory-drag-x', '0px')
    event.currentTarget.style.setProperty('--memory-drag-yaw', '0deg')
    dragRef.current = null
  }

  const onSceneKeyDown = (event: React.KeyboardEvent<HTMLElement>): void => {
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') selectIndex(selectedIndex + 1)
    if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') selectIndex(selectedIndex - 1)
  }

  const teachPortraitMemory = async (): Promise<void> => {
    const content = portraitDraft.trim()
    if (!content || portraitSaving) return
    setPortraitSaving(true)
    setPortraitReply('')
    setError('')
    try {
      const result = await window.api.teachMemoryAboutUser({ content })
      if (result?.status !== 'ok' && result?.status !== 'partial') {
        setError(result?.message || '这件事暂时没有记下来')
        return
      }
      setPortraitDraft('')
      setPortraitReply(text(result.reply || result.message || '我记住了。'))
      await loadAll()
      setView('portrait')
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : '这件事暂时没有记下来')
    } finally {
      setPortraitSaving(false)
    }
  }

  return (
    <main className="memory-cosmos-page">
      <aside className="memory-cosmos-sidebar" aria-label="记忆视图">
        <div className="memory-cosmos-mark"><span>MP</span><i /></div>
        <div className="memory-cosmos-nav">
          <button type="button" className={view === 'gallery' ? 'active' : ''} onClick={() => setView('gallery')}><Images size={18} /><span><strong>回忆星河</strong><small>在照片间穿行</small></span></button>
          <button type="button" className={view === 'portrait' ? 'active' : ''} onClick={() => setView('portrait')}><Brain size={18} /><span><strong>MindPet 记得</strong><small>关于你的长期理解</small></span></button>
        </div>
        <button className="memory-cosmos-share" type="button" onClick={() => { setView('gallery'); openComposer() }}><ImagePlus size={18} /><span><strong>分享一段回忆</strong><small>原图保存在本机</small></span></button>
        <div className="memory-cosmos-sidebar-note"><i />原图保存在本机</div>
      </aside>

      <section
        ref={sceneRef}
        className={`memory-cosmos-scene is-${view}`}
        tabIndex={0}
        onWheel={view === 'gallery' ? onSceneWheel : undefined}
        onPointerDown={view === 'gallery' ? onScenePointerDown : undefined}
        onPointerMove={onScenePointerMove}
        onPointerUp={onScenePointerUp}
        onPointerCancel={onScenePointerUp}
        onKeyDown={view === 'gallery' ? onSceneKeyDown : undefined}
      >
        <div className="memory-cosmos-nebula" aria-hidden="true"><i /><i /><i /></div>
        <div className="memory-cosmos-stars" aria-hidden="true">
          {cosmosStars.map((star, index) => <i key={index} style={{
            '--star-x': `${star.x}%`, '--star-y': `${star.y}%`, '--star-size': `${star.size}px`,
            '--star-delay': `${star.delay}s`, '--star-duration': `${star.duration}s`,
            '--star-opacity': 0.18 + star.depth * 0.14, '--star-glow': `${star.size * 5}px`
          } as React.CSSProperties} />)}
        </div>

        <header className="memory-cosmos-heading">
          <div><span>{view === 'gallery' ? 'MEMORY ORBIT' : `WHAT MINDPET REMEMBERS · 共 ${rememberedItems.length} 件`}</span><h1>{view === 'gallery' ? '回忆在时间里发光' : 'MindPet 记得'}</h1><p>{view === 'gallery' ? '滚动、拖动或使用方向键，在回忆的纵深里穿行。' : '这些是我从真实对话中理解并愿意长期保留的、关于你的事。'}</p></div>
          <button type="button" className="memory-cosmos-refresh" onClick={() => void loadAll()} aria-label="刷新"><RefreshCw size={16} /></button>
        </header>

        {error && <div className="memory-cosmos-error" role="alert"><span>{error}</span><button type="button" onClick={() => setError('')} aria-label="关闭错误提示"><X size={14} /></button></div>}

        {view === 'gallery' ? (
          <div className="memory-space-view">
            {loading ? (
              <div className="memory-cosmos-loading"><span /><p>正在寻找你的回忆坐标…</p></div>
            ) : items.length === 0 ? (
              <div className="memory-cosmos-empty"><div className="memory-empty-orbit"><Images size={28} /><i /><i /></div><h2>这里还没有星光</h2><p>分享一张照片。MindPet 会看见它、回应你，也会把这次相遇写进长期记忆。</p><button type="button" onClick={openComposer}><ImagePlus size={17} />分享第一段回忆</button></div>
            ) : (
              <>
                <div className="memory-orbit-controls" aria-label="切换回忆">
                  <button type="button" disabled={selectedIndex === 0} onClick={() => selectIndex(selectedIndex - 1)} aria-label="更新的回忆"><ChevronLeft size={18} /></button>
                  <span>{String(selectedIndex + 1).padStart(2, '0')} / {String(items.length).padStart(2, '0')}</span>
                  <button type="button" disabled={selectedIndex === items.length - 1} onClick={() => selectIndex(selectedIndex + 1)} aria-label="更早的回忆"><ChevronRight size={18} /></button>
                </div>

                <div className="memory-orbit-stage" aria-live="polite">
                  {items.map((item, index) => {
                    let offset = index - selectedIndex
                    if (items.length > 2) {
                      const half = Math.floor(items.length / 2)
                      if (offset > half) offset -= items.length
                      if (offset < -half) offset += items.length
                    }
                    const distance = Math.abs(offset)
                    const visuallyHidden = distance > 3
                    const direction = offset === 0 ? 0 : offset < 0 ? -1 : 1
                    const response = item.ai_summary || item.source_context || ''
                    const itemMood = moodMeta[item.mood || 'neutral'] || moodMeta.neutral
                    return (
                      <article key={item.id} aria-hidden={visuallyHidden ? true : undefined} className={`memory-orbit-node ${offset === 0 ? 'is-active' : ''} ${offset < 0 ? 'is-newer' : 'is-older'}`} style={{
                        '--memory-offset': offset,
                        '--memory-x': offset === 0 ? '-9vw' : `${direction * Math.min(56, 40 + Math.max(0, distance - 1) * 8)}vw`,
                        '--memory-lane': `${offset === 0 ? 0 : ((index % 5) - 2) * 22}px`,
                        '--memory-depth': distance,
                        '--memory-z': `${distance * -210}px`,
                        '--memory-turn': `${offset === 0 ? 0 : offset < 0 ? 54 : -54}deg`,
                        '--memory-tilt': `${offset === 0 ? -1.4 : offset < 0 ? -2.4 : 2.4}deg`,
                        '--memory-blur': `${distance * 0.65}px`,
                        '--memory-scale': Math.max(0.5, 1 - distance * 0.14),
                        '--memory-opacity': visuallyHidden ? 0 : distance === 0 ? 1 : Math.max(0.14, 0.58 - distance * 0.12),
                        '--memory-accent': itemMood.color,
                        zIndex: offset === 0 ? 52 : 40 - distance,
                        pointerEvents: visuallyHidden ? 'none' : 'auto'
                      } as React.CSSProperties}>
                        <button className="memory-planet" type="button" tabIndex={visuallyHidden ? -1 : 0} aria-hidden={visuallyHidden ? true : undefined} onClick={() => setSelectedId(item.id)} aria-label={`查看 ${memoryTitle(item)}`}>
                          <span className="memory-planet-aura" />
                          <span className="memory-planet-media">{item.image_uri ? <img src={item.image_uri} alt={item.story || item.title || '回忆照片'} /> : <Heart size={34} strokeWidth={1.2} />}</span>
                          <span className="memory-planet-date">{shortDate(item.event_at)}</span>
                        </button>

                        <div className="memory-orbit-copy" aria-hidden={offset === 0 ? undefined : true}>
                          <div className="memory-orbit-meta"><i />{dateLabel(item.event_at)} · {itemMood.label}</div>
                          <h2>{memoryTitle(item)}</h2>
                          {item.story && <p className="memory-orbit-story">“{item.story}”</p>}
                          <div className="memory-orbit-response"><MessageCircle size={16} /><div><span>MindPet 当时对你说</span><p>{response || '这段旧回忆没有留下当时的回应。以后再次聊起它时，我会重新认识这个瞬间。'}</p></div></div>
                          {(item.source_type === 'manual' || item.source_type === 'shared') && <button
                            className={`memory-orbit-delete ${confirmDeleteId === item.id ? 'is-confirming' : ''}`}
                            type="button"
                            tabIndex={offset === 0 ? 0 : -1}
                            aria-hidden={offset === 0 ? undefined : true}
                            aria-live="polite"
                            aria-label={confirmDeleteId === item.id ? `再次点击，确认移除 ${memoryTitle(item)}` : `移除 ${memoryTitle(item)}`}
                            onPointerDown={event => event.stopPropagation()}
                            onBlur={() => setConfirmDeleteId(current => current === item.id ? null : current)}
                            onClick={() => {
                              if (confirmDeleteId === item.id) void removeMemory(item)
                              else setConfirmDeleteId(item.id)
                            }}
                          ><Trash2 size={14} />{confirmDeleteId === item.id ? '再次点击，确认移除' : '移除这段回忆'}</button>}
                        </div>
                      </article>
                    )
                  })}
                </div>

                <div className="memory-time-axis" aria-label="回忆时间轴">
                  <div className="memory-time-direction"><span>现在</span><i /><span>更早</span></div>
                  <div className="memory-time-track">{items.map((item, index) => <button key={item.id} type="button" className={index === selectedIndex ? 'active' : ''} onClick={() => selectIndex(index)} aria-label={dateLabel(item.event_at)}><i /><span>{shortDate(item.event_at)}</span></button>)}</div>
                </div>
              </>
            )}
          </div>
        ) : (
          <div className="memory-portrait-space">
            {loading ? (
              <div className="memory-cosmos-loading"><span /><p>正在整理我真正记住的事…</p></div>
            ) : (
              <div className="memory-remembrance-shell">
                <div className="memory-remembrance-scroll">
                  {portrait.workingMemory && <section className="memory-remembrance-now"><div><Sparkles size={15} /><span>此刻的理解</span></div><p>{cleanWorkingMemory(portrait.workingMemory)}</p></section>}

                  {rememberedItems.length > 0 ? (
                    <div className="memory-remembrance-timeline">
                      {rememberedItems.map((item, index) => {
                        const open = expandedMemoryId === item.id
                        const understanding = text(item.understanding || item.content)
                        const userMessage = rememberedEvidence(item)
                        const evidenceContext = rememberedEvidenceContext(item)
                        const assistantReply = rememberedReply(item)
                        const sourceImage = item.image_uri || item.source?.imageUri
                        const sourceGalleryId = item.source_gallery_id || item.source?.galleryItemId
                        return <article key={item.id} className={`memory-remembrance ${open ? 'is-open' : ''}`}>
                          <i className="memory-remembrance-point" aria-hidden="true" />
                          <button className="memory-remembrance-summary" type="button" aria-expanded={open} onClick={() => setExpandedMemoryId(open ? null : item.id)}>
                            <span className="memory-remembrance-index">{String(index + 1).padStart(2, '0')}</span>
                            <span className="memory-remembrance-heading"><strong>{rememberedTitle(item)}</strong><small>{rememberedSourceLabel(item)} · {rememberedDate(item)}</small></span>
                            <ChevronDown size={17} />
                          </button>
                          {open && <div className="memory-remembrance-detail">
                            {sourceImage && <button className="memory-remembrance-image" type="button" onClick={() => { if (sourceGalleryId) { setSelectedId(sourceGalleryId); setView('gallery') } }}><img src={sourceImage} alt="这段记忆的照片" /><span>去看看那张照片</span></button>}
                            <div className="memory-remembrance-copy">
                              <div className="memory-remembrance-understanding"><Quote size={16} /><p>{understanding || rememberedTitle(item)}</p></div>
                              {userMessage && <blockquote><span>你当时说</span><p>{userMessage}</p></blockquote>}
                              {!userMessage && evidenceContext && <div className="memory-remembrance-context"><BookOpen size={15} /><div><span>形成这份理解时的背景</span><p>{evidenceContext}</p></div></div>}
                              {assistantReply && <div className="memory-remembrance-reply"><MessageCircle size={15} /><div><span>我当时回应</span><p>{assistantReply}</p></div></div>}
                              <div className="memory-remembrance-source"><BookOpen size={14} /><span>{rememberedSourceLabel(item)}，会随着新的相处继续修正</span></div>
                              <button className="memory-remembrance-correct" type="button" onClick={() => setPortraitDraft(`请更正你对我的这条理解：“${rememberedTitle(item)}”。\n实际情况是：`)}><RefreshCw size={13} />这条理解需要更正</button>
                            </div>
                          </div>}
                        </article>
                      })}
                    </div>
                  ) : (
                    <div className="memory-portrait-empty"><Sparkles size={26} /><h2>我还在认识你</h2><p>告诉我一件真实的事。等它在我们的对话里变得重要，我会把它留在这里。</p></div>
                  )}
                </div>

                <div className="memory-remembrance-composer">
                  {portraitReply && <p className="memory-remembrance-ack"><Sparkles size={13} />{portraitReply}</p>}
                  <div><textarea rows={1} value={portraitDraft} maxLength={1000} disabled={portraitSaving} onChange={event => setPortraitDraft(event.target.value)} onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void teachPortraitMemory() } }} placeholder="告诉 MindPet 一件关于你的事…" /><button type="button" disabled={portraitSaving || !portraitDraft.trim()} onClick={() => void teachPortraitMemory()} aria-label="告诉 MindPet">{portraitSaving ? <span className="memory-share-spinner" /> : <ArrowUp size={18} />}</button></div>
                  <small>我只会从你亲口说过的话里理解你，也允许你随时修正。</small>
                </div>
              </div>
            )}
          </div>
        )}

        {composerOpen && <div className="memory-share-layer">
          <button className="memory-share-backdrop" type="button" tabIndex={-1} aria-hidden="true" disabled={saving} onClick={closeComposer} />
          <section ref={shareDialogRef} className="memory-share-dialog" role="dialog" aria-modal="true" aria-labelledby="memory-share-title" aria-describedby="memory-share-description" tabIndex={-1}>
            <button ref={shareCloseRef} className="memory-share-close" type="button" disabled={saving} onClick={closeComposer} aria-label="关闭分享弹窗"><X size={17} /></button>
            <div className="memory-share-intro"><span>SHARE A MOMENT</span><h2 id="memory-share-title">把这个瞬间分享给我</h2><p id="memory-share-description">我会看见照片，听你说当时发生了什么，然后像一次真正的对话那样回应。它也会进入长期记忆与关系图谱。</p></div>
            <button className={`memory-share-photo ${draft.imageUri ? 'has-image' : ''}`} type="button" disabled={saving} onClick={() => void choosePhoto()}>
              {draft.imageUri ? <><img src={draft.imageUri} alt="准备分享的照片" /><span><ImagePlus size={15} />换一张照片</span></> : <><ImagePlus size={28} /><strong>选择一张照片</strong><small>这是这段分享的起点</small></>}
            </button>
            <div className="memory-share-fields">
              <label><span>你想对我说</span><textarea value={draft.story} maxLength={800} disabled={saving} onChange={event => setDraft(current => ({ ...current, story: event.target.value }))} placeholder="可以说说照片里的故事，也可以留空，让我先看看。" /></label>
              <div className="memory-share-meta">
                <div className="memory-share-date-row"><div className="memory-share-date-label"><CalendarDays size={14} /><span>发生在</span></div><MemoryDatePicker value={draft.eventAt} disabled={saving} onChange={eventAt => setDraft(current => ({ ...current, eventAt }))} /></div>
                <div className="memory-share-moods" aria-label="当时的心情">{['happy', 'neutral', 'excited', 'grateful', 'sad', 'stressed'].map(mood => <button key={mood} type="button" disabled={saving} className={draft.mood === mood ? 'active' : ''} onClick={() => setDraft(current => ({ ...current, mood }))}><i style={{ background: moodMeta[mood].color }} />{moodMeta[mood].label}</button>)}</div>
              </div>
              <button className="memory-share-submit" type="button" disabled={saving || !draft.imageUri} onClick={() => void shareMemory()}>{saving ? <><span className="memory-share-spinner" />MindPet 正在看这张照片…</> : <><Send size={16} />分享给 MindPet</>}</button>
            </div>
          </section>
        </div>}
      </section>
    </main>
  )
}
