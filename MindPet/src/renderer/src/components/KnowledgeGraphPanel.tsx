/* eslint-disable react-hooks/set-state-in-effect */
import React from 'react'
import {
  Background,
  BackgroundVariant,
  Controls,
  Handle,
  MarkerType,
  MiniMap,
  Position,
  ReactFlow,
  useEdgesState,
  useNodesState,
  type Edge,
  type Node,
  type NodeProps,
  type ReactFlowInstance
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import '../assets/knowledge-graph.css'
import {
  Database,
  Eye,
  EyeOff,
  Focus,
  LoaderCircle,
  MoreHorizontal,
  Network,
  RefreshCw,
  RotateCcw,
  Search,
  SlidersHorizontal,
  Sparkles,
  Trash2,
  X
} from 'lucide-react'

interface GraphNode {
  id: string
  label: string
  type: string
  summary: string
  importance: number
  mentionCount: number
  firstSeen: string
  lastSeen: string
}

interface GraphEdge {
  id: string
  source: string
  target: string
  label: string
  kind: 'fact' | 'semantic'
  confidence: number
}

interface GraphStats {
  entityCount: number
  relationCount: number
  evidenceCount: number
  pendingExtractions: number
}

interface GraphResponse {
  status: string
  nodes: GraphNode[]
  edges: GraphEdge[]
  stats: GraphStats
}

interface Evidence {
  id: number
  sessionId: string
  userMessage: string
  assistantMessage: string
  createdAt: string
  predicate?: string
  sourceName?: string
  targetName?: string
}

type DetailLevel = 'constellation' | 'detail'

type DropAnimation = 'target' | 'related' | null

type HandleSide = 'top' | 'right' | 'bottom' | 'left'

type MemoryEdgeData = {
  kind: 'fact' | 'semantic'
  confidence: number
  isFocused: boolean
}

type MemoryFlowEdge = Edge<MemoryEdgeData> & {
  pathOptions?: {
    curvature?: number
    borderRadius?: number
    offset?: number
    stepPosition?: number
  }
}

type MemoryNodeData = {
  label: string
  type: string
  importance: number
  mentionCount: number
  color: string
  isCore: boolean
  isDimmed: boolean
  detailLevel: DetailLevel
  dropAnimation: DropAnimation
}

type MemoryFlowNode = Node<MemoryNodeData, 'memory'>

const HANDLE_SIDES: Array<[HandleSide, Position]> = [
  ['top', Position.Top],
  ['right', Position.Right],
  ['bottom', Position.Bottom],
  ['left', Position.Left]
]

const TYPE_COLORS: Record<string, string> = {
  person: '#dd8a22',
  project: '#3d78e8',
  technology: '#12a4b5',
  tool: '#0b8a7a',
  preference: '#d35b8a',
  goal: '#8067d9',
  topic: '#4c70c5',
  organization: '#bc7040',
  place: '#41a565',
  event: '#d35d53',
  other: '#718096'
}

const TYPE_NAMES: Record<string, string> = {
  person: '人物',
  project: '项目',
  technology: '技术',
  tool: '工具',
  preference: '偏好',
  goal: '目标',
  topic: '主题',
  organization: '组织',
  place: '地点',
  event: '事件',
  other: '其他'
}

const RELATION_NAMES: Record<string, string> = {
  prefers: '偏好',
  dislikes: '不喜欢',
  uses: '使用',
  learns: '学习',
  builds: '构建',
  works_on: '参与',
  plans: '计划',
  knows: '认识',
  experienced: '经历',
  belongs_to: '属于',
  related_to: '相关'
}

const MemoryStarNode = React.memo(function MemoryStarNode({
  data,
  selected
}: NodeProps<MemoryFlowNode>): React.JSX.Element {
  const title = `${data.label} · ${TYPE_NAMES[data.type] || data.type} · 提及 ${data.mentionCount} 次`
  return (
    <div
      className={[
        'memory-star-node',
        data.isCore ? 'is-core' : '',
        data.isDimmed ? 'is-dimmed' : '',
        data.detailLevel === 'constellation' ? 'is-constellation' : '',
        data.dropAnimation === 'target' ? 'is-drop-target' : '',
        data.dropAnimation === 'related' ? 'is-drop-related' : '',
        selected ? 'is-selected' : ''
      ]
        .filter(Boolean)
        .join(' ')}
      style={{ '--star-color': data.color } as React.CSSProperties}
      title={title}
      aria-label={title}
    >
      <span className="memory-star-node__halo" aria-hidden="true" />
      <span className="memory-star-node__core" aria-hidden="true">
        {data.isCore ? <Sparkles size={15} strokeWidth={1.8} /> : <span />}
      </span>
      <span className="memory-star-node__name">{data.label}</span>
      {data.detailLevel === 'detail' && data.mentionCount > 1 && (
        <span className="memory-star-node__count">{data.mentionCount}</span>
      )}
      {HANDLE_SIDES.map(([side, position]) => (
        <Handle
          key={`target-${side}`}
          className="memory-star-handle"
          id={`target-${side}`}
          type="target"
          position={position}
          aria-hidden="true"
        />
      ))}
      {HANDLE_SIDES.map(([side, position]) => (
        <Handle
          key={`source-${side}`}
          className="memory-star-handle"
          id={`source-${side}`}
          type="source"
          position={position}
          aria-hidden="true"
        />
      ))}
    </div>
  )
})

const nodeTypes = { memory: MemoryStarNode }

function entityWeight(item: GraphNode): number {
  return item.importance * 100 + Math.min(item.mentionCount, 30)
}

function getCoreEntity(items: GraphNode[]): GraphNode | undefined {
  return [...items].sort((left, right) => {
    const leftCore = left.label.toLowerCase() === 'user' ? 1 : 0
    const rightCore = right.label.toLowerCase() === 'user' ? 1 : 0
    if (leftCore !== rightCore) return rightCore - leftCore
    const leftPerson = left.type === 'person' ? 1 : 0
    const rightPerson = right.type === 'person' ? 1 : 0
    if (leftPerson !== rightPerson) return rightPerson - leftPerson
    return entityWeight(right) - entityWeight(left)
  })[0]
}

function connectedIds(edges: GraphEdge[], selectedId: string | null): Set<string> {
  if (!selectedId) return new Set()
  const ids = new Set([selectedId])
  edges.forEach((edge) => {
    if (edge.source === selectedId) ids.add(edge.target)
    if (edge.target === selectedId) ids.add(edge.source)
  })
  return ids
}

const NODE_LABEL_CLEARANCE = 136
const RADIAL_LAYER_GAP = 178
const RADIAL_LAYER_BASE = [190, 360, 520]

function nodeSize(item: GraphNode, isCore: boolean): number {
  const importanceSize = 48 + item.importance * 22 + Math.min(item.mentionCount, 10) * 1.6
  return Math.round(Math.min(isCore ? 98 : 82, Math.max(isCore ? 72 : 48, importanceSize)))
}

function buildAdjacency(edges: GraphEdge[]): Map<string, Set<string>> {
  const adjacency = new Map<string, Set<string>>()
  edges.forEach((edge) => {
    if (!adjacency.has(edge.source)) adjacency.set(edge.source, new Set())
    if (!adjacency.has(edge.target)) adjacency.set(edge.target, new Set())
    adjacency.get(edge.source)?.add(edge.target)
    adjacency.get(edge.target)?.add(edge.source)
  })
  return adjacency
}

function radialDistance(count: number, base: number): number {
  if (count < 2) return base
  const minimumCircumference = count * NODE_LABEL_CLEARANCE
  return Math.max(base, minimumCircumference / (Math.PI * 2))
}

function angleFor(point: { x: number; y: number }): number {
  return Math.atan2(point.y, point.x)
}

function angleDifference(left: number, right: number): number {
  let difference = left - right
  while (difference > Math.PI) difference -= Math.PI * 2
  while (difference < -Math.PI) difference += Math.PI * 2
  return difference
}

function averageNeighborAngle(
  item: GraphNode,
  adjacency: Map<string, Set<string>>,
  placedAngles: Map<string, number>
): number | null {
  const angles = [...(adjacency.get(item.id) || [])]
    .map((id) => placedAngles.get(id))
    .filter((angle): angle is number => angle !== undefined)
  if (angles.length === 0) return null
  const vector = angles.reduce(
    (result, angle) => ({ x: result.x + Math.cos(angle), y: result.y + Math.sin(angle) }),
    { x: 0, y: 0 }
  )
  return angleFor(vector)
}

function buildNodes(
  items: GraphNode[],
  edges: GraphEdge[],
  selectedId: string | null,
  detailLevel: DetailLevel
): MemoryFlowNode[] {
  const core = getCoreEntity(items)
  const adjacency = buildAdjacency(edges)
  const depths = new Map<string, number>()
  if (core) {
    depths.set(core.id, 0)
    const queue = [core.id]
    while (queue.length > 0) {
      const currentId = queue.shift() as string
      const nextDepth = (depths.get(currentId) || 0) + 1
      adjacency.get(currentId)?.forEach((neighborId) => {
        if (!depths.has(neighborId)) {
          depths.set(neighborId, Math.min(nextDepth, 3))
          queue.push(neighborId)
        }
      })
    }
  }

  const layerEntries = new Map<number, GraphNode[]>()
  items
    .filter((item) => item.id !== core?.id)
    .forEach((item) => {
      const depth = depths.get(item.id) || 3
      layerEntries.set(depth, [...(layerEntries.get(depth) || []), item])
    })

  const focusIds = connectedIds(edges, selectedId)
  const hasFocus = focusIds.size > 0
  const result: MemoryFlowNode[] = []
  const placedAngles = new Map<string, number>()

  const pushNode = (item: GraphNode, position: { x: number; y: number }, isCore: boolean): void => {
    const size = nodeSize(item, isCore)
    result.push({
      id: item.id,
      type: 'memory',
      position,
      className: 'memory-star-flow-node',
      data: {
        label: item.label,
        type: item.type,
        importance: item.importance,
        mentionCount: item.mentionCount,
        color: TYPE_COLORS[item.type] || TYPE_COLORS.other,
        isCore,
        isDimmed: hasFocus && !focusIds.has(item.id),
        detailLevel,
        dropAnimation: null
      },
      style: {
        width: size,
        height: size
      } as React.CSSProperties
    })
  }

  if (core) pushNode(core, { x: 0, y: 0 }, true)

  const orderedLayers = [...layerEntries.entries()].sort(([left], [right]) => left - right)
  orderedLayers.forEach(([depth, entries]) => {
    const radius = radialDistance(
      entries.length,
      RADIAL_LAYER_BASE[Math.min(depth - 1, RADIAL_LAYER_BASE.length - 1)] +
        Math.max(0, depth - RADIAL_LAYER_BASE.length) * RADIAL_LAYER_GAP
    )
    const sortedEntries = [...entries].sort((left, right) => {
      const leftAngle = averageNeighborAngle(left, adjacency, placedAngles)
      const rightAngle = averageNeighborAngle(right, adjacency, placedAngles)
      if (leftAngle !== null && rightAngle !== null) {
        return angleDifference(leftAngle, rightAngle)
      }
      if (leftAngle !== null) return -1
      if (rightAngle !== null) return 1
      return entityWeight(right) - entityWeight(left)
    })
    const angleStep = (Math.PI * 2) / Math.max(sortedEntries.length, 1)
    const firstTarget = sortedEntries[0]
      ? averageNeighborAngle(sortedEntries[0], adjacency, placedAngles)
      : null
    const rotation = firstTarget === null ? -Math.PI / 2 : firstTarget - angleStep / 2

    sortedEntries.forEach((item, itemIndex) => {
      const angle = rotation + angleStep * itemIndex
      placedAngles.set(item.id, angle)
      pushNode(
        item,
        {
          x: Math.cos(angle) * radius,
          y: Math.sin(angle) * radius
        },
        false
      )
    })
  })

  return result
}

function getHandleSides(
  source: MemoryFlowNode,
  target: MemoryFlowNode
): { sourceSide: HandleSide; targetSide: HandleSide } {
  if (source.id === target.id) {
    return { sourceSide: 'right', targetSide: 'left' }
  }

  const deltaX = target.position.x - source.position.x
  const deltaY = target.position.y - source.position.y
  if (Math.abs(deltaX) >= Math.abs(deltaY)) {
    return deltaX >= 0
      ? { sourceSide: 'right', targetSide: 'left' }
      : { sourceSide: 'left', targetSide: 'right' }
  }
  return deltaY >= 0
    ? { sourceSide: 'bottom', targetSide: 'top' }
    : { sourceSide: 'top', targetSide: 'bottom' }
}

function buildEdges(
  items: GraphEdge[],
  selectedId: string | null,
  layoutNodes: MemoryFlowNode[]
): MemoryFlowEdge[] {
  const nodesById = new Map(layoutNodes.map((node) => [node.id, node]))
  return items.map((item) => {
    const factual = item.kind === 'fact'
    const isFocused = !selectedId || item.source === selectedId || item.target === selectedId
    const sourceNode = nodesById.get(item.source)
    const targetNode = nodesById.get(item.target)
    const handleSides =
      sourceNode && targetNode
        ? getHandleSides(sourceNode, targetNode)
        : { sourceSide: 'right' as HandleSide, targetSide: 'left' as HandleSide }
    return {
      id: item.id,
      source: item.source,
      target: item.target,
      type: 'bezier',
      sourceHandle: `source-${handleSides.sourceSide}`,
      targetHandle: `target-${handleSides.targetSide}`,
      pathOptions: { curvature: 0.16 },
      className: factual ? 'kg-edge-fact' : 'kg-edge-semantic',
      animated: !factual && Boolean(selectedId) && isFocused,
      data: {
        kind: item.kind,
        confidence: item.confidence,
        isFocused
      },
      label:
        selectedId && isFocused && factual ? RELATION_NAMES[item.label] || item.label : undefined,
      markerEnd:
        factual && isFocused ? { type: MarkerType.ArrowClosed, width: 12, height: 12 } : undefined,
      style: {
        stroke: factual ? 'var(--kg-fact-edge)' : 'var(--kg-semantic-edge)',
        strokeWidth: factual ? (isFocused ? 1.8 : 1.15) : isFocused ? 1.3 : 0.9,
        strokeDasharray: factual ? undefined : '2 8',
        strokeLinecap: 'round',
        opacity: isFocused ? (factual ? Math.max(0.52, item.confidence) : 0.48) : 0.08
      },
      labelStyle: {
        fill: 'var(--text-secondary)',
        fontSize: 10,
        fontWeight: 650
      },
      labelBgStyle: {
        fill: 'var(--bg-content)',
        fillOpacity: 0.94
      }
    }
  })
}

interface RelatedEntity {
  entity: GraphNode
  edge: GraphEdge
}

interface Props {
  showToast: (message: string, type?: 'success' | 'error' | 'info') => void
}

export function KnowledgeGraphPanel({ showToast }: Props): React.JSX.Element {
  const [rawNodes, setRawNodes] = React.useState<GraphNode[]>([])
  const [rawEdges, setRawEdges] = React.useState<GraphEdge[]>([])
  const [stats, setStats] = React.useState<GraphStats>({
    entityCount: 0,
    relationCount: 0,
    evidenceCount: 0,
    pendingExtractions: 0
  })
  const [nodes, setNodes, onNodesChange] = useNodesState<MemoryFlowNode>([])
  const [edges, setEdges, onEdgesChange] = useEdgesState<MemoryFlowEdge>([])
  const [query, setQuery] = React.useState('')
  const [activeTypes, setActiveTypes] = React.useState<Set<string>>(new Set())
  const [showSemantic, setShowSemantic] = React.useState(true)
  const [filterOpen, setFilterOpen] = React.useState(false)
  const [loading, setLoading] = React.useState(true)
  const [loadError, setLoadError] = React.useState<string | null>(null)
  const [rebuilding, setRebuilding] = React.useState(false)
  const [selectedId, setSelectedId] = React.useState<string | null>(null)
  const [inspectorClosing, setInspectorClosing] = React.useState(false)
  const [evidence, setEvidence] = React.useState<Evidence[]>([])
  const [evidenceLoading, setEvidenceLoading] = React.useState(false)
  const [detailLevel, setDetailLevel] = React.useState<DetailLevel>('detail')
  const [detailMenuOpen, setDetailMenuOpen] = React.useState(false)
  const [isDragging, setIsDragging] = React.useState(false)
  const dropAnimationRunRef = React.useRef(0)
  const inspectorCloseTimerRef = React.useRef<number | null>(null)
  const fitPendingRef = React.useRef(false)
  const [flowInstance, setFlowInstance] = React.useState<ReactFlowInstance<
    MemoryFlowNode,
    MemoryFlowEdge
  > | null>(null)
  const nodesRef = React.useRef<MemoryFlowNode[]>([])

  const allTypes = React.useMemo(
    () =>
      [...new Set(rawNodes.map((item) => item.type))].sort((left, right) =>
        (TYPE_NAMES[left] || left).localeCompare(TYPE_NAMES[right] || right)
      ),
    [rawNodes]
  )
  const visibleNodes = React.useMemo(
    () =>
      activeTypes.size === 0 ? rawNodes : rawNodes.filter((item) => activeTypes.has(item.type)),
    [activeTypes, rawNodes]
  )
  const visibleNodeIds = React.useMemo(
    () => new Set(visibleNodes.map((item) => item.id)),
    [visibleNodes]
  )
  const visibleEdges = React.useMemo(
    () =>
      rawEdges.filter(
        (item) =>
          (showSemantic || item.kind !== 'semantic') &&
          visibleNodeIds.has(item.source) &&
          visibleNodeIds.has(item.target)
      ),
    [rawEdges, showSemantic, visibleNodeIds]
  )
  const selected = rawNodes.find((item) => item.id === selectedId) || null

  React.useEffect(() => {
    return () => {
      if (inspectorCloseTimerRef.current !== null) {
        window.clearTimeout(inspectorCloseTimerRef.current)
      }
    }
  }, [])

  const closeInspector = React.useCallback((): void => {
    if (!selectedId || inspectorClosing) return
    setInspectorClosing(true)
    inspectorCloseTimerRef.current = window.setTimeout(() => {
      setSelectedId(null)
      setEvidence([])
      setDetailMenuOpen(false)
      setInspectorClosing(false)
      inspectorCloseTimerRef.current = null
    }, 180)
  }, [inspectorClosing, selectedId])
  const relatedEntities = React.useMemo<RelatedEntity[]>(() => {
    if (!selected) return []
    const byId = new Map(rawNodes.map((item) => [item.id, item]))
    return rawEdges
      .filter(
        (edge) =>
          edge.kind === 'fact' && (edge.source === selected.id || edge.target === selected.id)
      )
      .map((edge) => ({
        entity: byId.get(edge.source === selected.id ? edge.target : edge.source),
        edge
      }))
      .filter((item): item is RelatedEntity => Boolean(item.entity))
      .sort((left, right) => right.edge.confidence - left.edge.confidence)
  }, [rawEdges, rawNodes, selected])

  React.useEffect(() => {
    if (selectedId && !visibleNodeIds.has(selectedId)) {
      setSelectedId(null)
      setEvidence([])
    }
  }, [selectedId, visibleNodeIds])

  React.useEffect(() => {
    const nextNodes = buildNodes(visibleNodes, visibleEdges, null, 'detail')
    nodesRef.current = nextNodes
    setNodes(nextNodes)
    setEdges(buildEdges(visibleEdges, null, nextNodes))
  }, [setEdges, setNodes, visibleEdges, visibleNodes])

  React.useEffect(() => {
    const focusedIds = connectedIds(visibleEdges, selectedId)
    const hasFocus = focusedIds.size > 0
    setNodes((currentNodes) => {
      const nextNodes = currentNodes.map((node) => {
        const isDimmed = hasFocus && !focusedIds.has(node.id)
        if (node.data.isDimmed === isDimmed && node.data.detailLevel === detailLevel) return node
        return {
          ...node,
          data: { ...node.data, isDimmed, detailLevel }
        }
      })
      nodesRef.current = nextNodes
      return nextNodes
    })
    setEdges(buildEdges(visibleEdges, selectedId, nodesRef.current))
  }, [detailLevel, selectedId, setEdges, setNodes, visibleEdges])

  React.useEffect(() => {
    if (!fitPendingRef.current || !flowInstance || nodes.length === 0) return
    fitPendingRef.current = false
    const frame = window.requestAnimationFrame(() => {
      flowInstance.fitView({ padding: 0.1, maxZoom: 1.35, duration: 360 })
    })
    return () => window.cancelAnimationFrame(frame)
  }, [flowInstance, nodes])

  const resetView = React.useCallback((): void => {
    flowInstance?.fitView({ padding: 0.1, maxZoom: 1.35, duration: 360 })
  }, [flowInstance])

  const clearDropAnimation = React.useCallback((): void => {
    setNodes((currentNodes) => {
      let changed = false
      const nextNodes = currentNodes.map((node) => {
        if (!node.data.dropAnimation) return node
        changed = true
        return {
          ...node,
          data: { ...node.data, dropAnimation: null }
        }
      })
      return changed ? nextNodes : currentNodes
    })
  }, [setNodes])

  const loadGraph = React.useCallback(
    async (search = query): Promise<void> => {
      setLoading(true)
      setLoadError(null)
      try {
        const data = (await window.api.getKnowledgeGraph(
          search.trim() || undefined,
          250
        )) as GraphResponse
        if (data.status !== 'ok') throw new Error('backend unavailable')
        setRawNodes(data.nodes || [])
        setRawEdges(data.edges || [])
        setStats(
          data.stats || {
            entityCount: 0,
            relationCount: 0,
            evidenceCount: 0,
            pendingExtractions: 0
          }
        )
        setActiveTypes(new Set())
        setSelectedId(null)
        clearDropAnimation()
        setEvidence([])
        fitPendingRef.current = true
      } catch {
        setLoadError('知识图谱暂时无法连接')
        showToast('无法读取知识图谱，请确认 Java 后端和 PostgreSQL 已启动', 'error')
      } finally {
        setLoading(false)
      }
    },
    [clearDropAnimation, query, showToast]
  )

  // The first load is intentionally independent from later query changes.
  React.useEffect(() => {
    const timer = window.setTimeout(() => void loadGraph(''), 0)
    return () => window.clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const selectNode = async (nodeId: string): Promise<void> => {
    if (inspectorCloseTimerRef.current !== null) {
      window.clearTimeout(inspectorCloseTimerRef.current)
      inspectorCloseTimerRef.current = null
    }
    setInspectorClosing(false)
    setSelectedId(nodeId)
    setDetailMenuOpen(false)
    setEvidenceLoading(true)
    try {
      const data = await window.api.getKnowledgeGraphEvidence(nodeId, 20)
      setEvidence(data.evidence || [])
    } catch {
      setEvidence([])
      showToast('读取来源对话失败', 'error')
    } finally {
      setEvidenceLoading(false)
    }
  }

  const toggleType = (type: string): void => {
    setActiveTypes((current) => {
      const next = new Set(current)
      if (next.has(type)) next.delete(type)
      else next.add(type)
      return next
    })
  }

  const rebuild = async (): Promise<void> => {
    if (!confirm('将从历史会话中提取知识，可能产生模型调用费用。继续吗？')) return
    setRebuilding(true)
    try {
      const data = await window.api.rebuildKnowledgeGraph(50)
      showToast(`已提交 ${data.scheduled || 0} 个对话轮次，图谱会在后台逐步更新`, 'success')
      window.setTimeout(() => void loadGraph(query), 2500)
    } catch {
      showToast('历史图谱重建提交失败', 'error')
    } finally {
      setRebuilding(false)
    }
  }

  const deleteSelected = async (): Promise<void> => {
    if (!selected || !confirm(`删除“${selected.label}”及其关系和证据吗？`)) return
    const result = await window.api.deleteKnowledgeGraphEntity(selected.id)
    if (!result.deleted) {
      showToast('节点删除失败', 'error')
      return
    }
    setSelectedId(null)
    setEvidence([])
    setDetailMenuOpen(false)
    showToast('节点已删除', 'success')
    await loadGraph(query)
  }

  return (
    <div className="knowledge-graph-panel">
      <header className="knowledge-graph-toolbar">
        <div className="knowledge-graph-toolbar__start">
          <div className="knowledge-graph-title">
            <Network size={17} strokeWidth={1.8} aria-hidden="true" />
            <span>记忆星图</span>
          </div>
          <form
            className="knowledge-graph-search"
            onSubmit={(event) => {
              event.preventDefault()
              void loadGraph(query)
            }}
          >
            <Search size={15} aria-hidden="true" />
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="探索人物、项目、技术或目标"
              aria-label="搜索知识图谱"
            />
            {query && (
              <button
                type="button"
                title="清除搜索"
                aria-label="清除搜索"
                onClick={() => {
                  setQuery('')
                  void loadGraph('')
                }}
              >
                <X size={14} />
              </button>
            )}
          </form>
        </div>
        <div className="knowledge-graph-toolbar__end">
          <div className="kg-filter">
            <button
              className={`kg-tool-button ${activeTypes.size > 0 ? 'is-active' : ''}`}
              type="button"
              aria-expanded={filterOpen}
              title="按实体类型筛选"
              onClick={() => setFilterOpen((value) => !value)}
            >
              <SlidersHorizontal size={15} />
              <span>筛选</span>
              {activeTypes.size > 0 && <b>{activeTypes.size}</b>}
            </button>
            {filterOpen && (
              <div className="kg-filter-menu">
                <div className="kg-filter-menu__head">
                  <span>实体类型</span>
                  <button type="button" onClick={() => setActiveTypes(new Set())}>
                    全部显示
                  </button>
                </div>
                <div className="kg-filter-menu__options">
                  {allTypes.map((type) => (
                    <label key={type}>
                      <input
                        type="checkbox"
                        checked={activeTypes.size === 0 || activeTypes.has(type)}
                        onChange={() => toggleType(type)}
                      />
                      <i style={{ background: TYPE_COLORS[type] || TYPE_COLORS.other }} />
                      <span>{TYPE_NAMES[type] || type}</span>
                    </label>
                  ))}
                </div>
              </div>
            )}
          </div>
          <label className="kg-semantic-toggle" title="显示语义相似边">
            <input
              type="checkbox"
              checked={showSemantic}
              onChange={(event) => setShowSemantic(event.target.checked)}
            />
            {showSemantic ? <Eye size={15} /> : <EyeOff size={15} />}
            <span>语义</span>
          </label>
          <button
            className="kg-icon-button"
            type="button"
            title="重置视图"
            aria-label="重置视图"
            onClick={resetView}
          >
            <Focus size={16} />
          </button>
          <button
            className="kg-icon-button"
            type="button"
            title="刷新图谱"
            aria-label="刷新图谱"
            onClick={() => void loadGraph(query)}
            disabled={loading}
          >
            <RefreshCw size={16} className={loading ? 'kg-spin' : ''} />
          </button>
          <button
            className="btn-secondary kg-rebuild-button"
            type="button"
            onClick={() => void rebuild()}
            disabled={rebuilding}
          >
            {rebuilding ? <LoaderCircle size={15} className="kg-spin" /> : <RotateCcw size={15} />}
            历史重建
          </button>
        </div>
      </header>

      <div className="knowledge-graph-meta">
        <span>
          <strong>{stats.entityCount}</strong> 个记忆实体
        </span>
        <span>
          <strong>{stats.relationCount}</strong> 条确认关系
        </span>
        <span>
          <Database size={13} />
          <strong>{stats.evidenceCount}</strong> 条来源对话
        </span>
        {stats.pendingExtractions > 0 && (
          <span className="kg-pending">
            <LoaderCircle size={13} className="kg-spin" />
            {stats.pendingExtractions} 项提取中
          </span>
        )}
        <span className="kg-edge-key">
          <i className="fact" />
          事实 <i className="semantic" />
          语义相似
        </span>
      </div>

      <div
        className={['knowledge-graph-workspace', isDragging ? 'is-dragging' : '']
          .filter(Boolean)
          .join(' ')}
      >
        <section className="knowledge-graph-canvas" aria-label="知识图谱画布">
          {loading && rawNodes.length === 0 ? (
            <div className="kg-empty">
              <LoaderCircle size={22} className="kg-spin" />
              <strong>正在整理记忆星图</strong>
              <span>读取实体、确认关系和来源对话</span>
            </div>
          ) : loadError && rawNodes.length === 0 ? (
            <div className="kg-empty">
              <Network size={26} />
              <strong>{loadError}</strong>
              <span>请确认后端服务已启动，然后重新连接。</span>
              <button
                type="button"
                className="kg-empty-action"
                onClick={() => void loadGraph(query)}
              >
                <RefreshCw size={14} />
                重试
              </button>
            </div>
          ) : rawNodes.length === 0 ? (
            <div className="kg-empty">
              <Sparkles size={26} />
              <strong>还没有可展示的记忆</strong>
              <span>继续与 Agent 对话，或从历史会话提取已有信息。</span>
              <button type="button" className="kg-empty-action" onClick={() => void rebuild()}>
                <RotateCcw size={14} />
                开始历史重建
              </button>
            </div>
          ) : (
            <ReactFlow<MemoryFlowNode, MemoryFlowEdge>
              nodes={nodes}
              edges={edges}
              nodeTypes={nodeTypes}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              onNodeClick={(_, node) => void selectNode(node.id)}
              onNodeDragStart={() => {
                setIsDragging(true)
                dropAnimationRunRef.current += 1
                clearDropAnimation()
              }}
              onNodeDragStop={(_, node) => {
                setIsDragging(false)
                const animationRun = ++dropAnimationRunRef.current
                const relatedIds = connectedIds(visibleEdges, node.id)
                setNodes((currentNodes) =>
                  currentNodes.map((currentNode) => {
                    const dropAnimation =
                      currentNode.id === node.id
                        ? 'target'
                        : relatedIds.has(currentNode.id)
                          ? 'related'
                          : null
                    if (currentNode.data.dropAnimation === dropAnimation) return currentNode
                    return {
                      ...currentNode,
                      data: { ...currentNode.data, dropAnimation }
                    }
                  })
                )
                window.setTimeout(() => {
                  if (dropAnimationRunRef.current === animationRun) clearDropAnimation()
                }, 620)
              }}
              onPaneClick={() => {
                closeInspector()
              }}
              onMoveEnd={(_, viewport) =>
                setDetailLevel(viewport.zoom < 0.46 ? 'constellation' : 'detail')
              }
              onInit={setFlowInstance}
              minZoom={0.08}
              maxZoom={2.2}
              nodeOrigin={[0.5, 0.5]}
              onlyRenderVisibleElements
              nodesConnectable={false}
              deleteKeyCode={null}
              proOptions={{ hideAttribution: true }}
            >
              <Background
                variant={BackgroundVariant.Dots}
                gap={26}
                size={1}
                color="var(--kg-field-dot)"
              />
              <MiniMap
                pannable
                zoomable
                nodeColor={(node) => TYPE_COLORS[String(node.data.type)] || TYPE_COLORS.other}
              />
              <Controls showInteractive={false} />
            </ReactFlow>
          )}
          {rawNodes.length > 0 && (
            <div className="kg-field-label" aria-hidden="true">
              <Sparkles size={13} />
              {detailLevel === 'constellation' ? '星群视图' : '实体视图'}
            </div>
          )}
          {loading && rawNodes.length > 0 && (
            <div className="kg-canvas-status" role="status" aria-live="polite">
              <LoaderCircle size={14} className="kg-spin" />
              正在更新星图
            </div>
          )}
        </section>

        {selected && (
          <aside
            className={`knowledge-evidence-panel${inspectorClosing ? ' is-closing' : ''}`}
            aria-label="记忆检视器"
          >
            <div className="kg-inspector-head">
              <div className="kg-inspector-kind">
                <i style={{ background: TYPE_COLORS[selected.type] || TYPE_COLORS.other }} />
                {TYPE_NAMES[selected.type] || selected.type}
              </div>
              <div className="kg-inspector-actions">
                <div className="kg-detail-menu">
                  <button
                    className="kg-icon-button"
                    type="button"
                    title="更多操作"
                    aria-label="更多操作"
                    onClick={() => setDetailMenuOpen((value) => !value)}
                  >
                    <MoreHorizontal size={17} />
                  </button>
                  {detailMenuOpen && (
                    <button
                      className="kg-delete-action"
                      type="button"
                      onClick={() => void deleteSelected()}
                    >
                      <Trash2 size={14} />
                      删除节点
                    </button>
                  )}
                </div>
                <button
                  className="kg-icon-button"
                  type="button"
                  title="关闭检视器"
                  aria-label="关闭检视器"
                  onClick={closeInspector}
                >
                  <X size={16} />
                </button>
              </div>
            </div>
            <h3>{selected.label}</h3>
            {selected.summary && <p className="kg-summary">{selected.summary}</p>}
            <div className="kg-inspector-measures">
              <span>
                重要度 <b>{Math.round(selected.importance * 100)}%</b>
              </span>
              <span>
                提及 <b>{selected.mentionCount}</b> 次
              </span>
            </div>

            <section className="kg-related-section">
              <div className="kg-section-heading">
                <strong>确认关联</strong>
                <span>{relatedEntities.length}</span>
              </div>
              <div className="kg-related-list">
                {relatedEntities.length === 0 ? (
                  <span className="kg-detail-empty">暂无确认关系</span>
                ) : (
                  relatedEntities.map(({ entity, edge }) => (
                    <button key={edge.id} type="button" onClick={() => void selectNode(entity.id)}>
                      <i style={{ background: TYPE_COLORS[entity.type] || TYPE_COLORS.other }} />
                      <span>{entity.label}</span>
                      <small>{RELATION_NAMES[edge.label] || edge.label}</small>
                    </button>
                  ))
                )}
              </div>
            </section>

            <section className="kg-evidence-section">
              <div className="kg-section-heading">
                <strong>来源对话</strong>
                <span>{evidence.length}</span>
              </div>
              <div className="kg-evidence-list">
                {evidenceLoading ? (
                  <div className="kg-detail-empty">
                    <LoaderCircle size={17} className="kg-spin" />
                    读取中
                  </div>
                ) : evidence.length === 0 ? (
                  <div className="kg-detail-empty">暂无来源记录</div>
                ) : (
                  evidence.map((item) => (
                    <article className="kg-evidence-item" key={item.id}>
                      {item.predicate && (
                        <div className="kg-evidence-relation">
                          {item.sourceName} {RELATION_NAMES[item.predicate] || item.predicate}{' '}
                          {item.targetName}
                        </div>
                      )}
                      <p>{item.userMessage}</p>
                      <time>
                        {item.createdAt
                          ? new Date(item.createdAt).toLocaleString()
                          : item.sessionId}
                      </time>
                    </article>
                  ))
                )}
              </div>
            </section>
          </aside>
        )}
      </div>
    </div>
  )
}
