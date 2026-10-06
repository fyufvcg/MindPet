/* eslint-disable react-hooks/set-state-in-effect */
import React from 'react'
import {
  Controls,
  ConnectionMode,
  Handle,
  MarkerType,
  MiniMap,
  Position,
  ReactFlow,
  ViewportPortal,
  useEdgesState,
  useNodesState,
  type Edge,
  type Connection,
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
  Link2,
  Pencil,
  Plus,
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
import { KnowledgeGraphEditor, type GraphEdit } from './KnowledgeGraphEditor'
import { KnowledgeGraphBackdrop } from './KnowledgeGraphBackdrop'

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
  importance?: number
  lastSeen?: string
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
  retention: number
  isHovered: boolean
  delay: number
  clusterId: string
}

type MemoryFlowNode = Node<MemoryNodeData, 'memory'>

const HANDLE_SIDES: Array<[HandleSide, Position]> = [
  ['top', Position.Top],
  ['right', Position.Right],
  ['bottom', Position.Bottom],
  ['left', Position.Left]
]

const TYPE_COLORS: Record<string, string> = {
  person: '#b98b46',
  project: '#638edd',
  technology: '#519eaf',
  tool: '#5b9d91',
  preference: '#b67d9c',
  goal: '#9582c6',
  topic: '#7898be',
  organization: '#b39274',
  place: '#7da487',
  event: '#bf8d83',
  other: '#8a99ae'
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
  const title = `${data.label} · ${TYPE_NAMES[data.type] || data.type} · 提及 ${data.mentionCount} 次 · ${retentionLabel(data.retention)}`
  return (
    <div
      className={[
        'memory-star-node',
        data.isCore ? 'is-core' : '',
        data.isDimmed ? 'is-dimmed' : '',
        data.isHovered ? 'is-hovered' : '',
        data.retention < 0.3 ? 'is-fading' : '',
        data.detailLevel === 'constellation' ? 'is-constellation' : '',
        data.dropAnimation === 'target' ? 'is-drop-target' : '',
        data.dropAnimation === 'related' ? 'is-drop-related' : '',
        selected ? 'is-selected' : ''
      ]
        .filter(Boolean)
        .join(' ')}
      style={
        {
          '--star-color': data.color,
          '--star-strength': 0.24 + data.retention * 0.76,
          '--star-delay': `${data.delay}s`,
          '--star-arrival': `${data.isCore ? 0 : 100 + Math.abs(data.delay) * 35}ms`
        } as React.CSSProperties
      }
      title={title}
      aria-label={title}
      role="button"
      tabIndex={0}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault()
          event.stopPropagation()
          event.currentTarget.click()
        }
      }}
    >
      <span className="memory-star-node__pulse" aria-hidden="true" />
      <span className="memory-star-node__halo" aria-hidden="true" />
      <span className="memory-star-node__orbit" aria-hidden="true" />
      <span className="memory-star-node__core" aria-hidden="true">
        <svg
          viewBox="0 0 48 48"
          className={data.isCore ? 'memory-star-glyph is-user' : 'memory-star-glyph'}
        >
          {data.isCore ? (
            <path d="M24 3 30.2 16.1 44.5 18 34 28 36.6 42.5 24 35.6 11.4 42.5 14 28 3.5 18 17.8 16.1Z" />
          ) : (
            <>
              <path d="M24 2C26.7 17.7 30.3 21.3 46 24 30.3 26.7 26.7 30.3 24 46 21.3 30.3 17.7 26.7 2 24 17.7 21.3 21.3 17.7 24 2Z" />
              <path
                className="memory-star-glyph__rays"
                d="m10 10 6 6m16 16 6 6m0-28-6 6M16 32l-6 6"
              />
            </>
          )}
        </svg>
      </span>
      <span className="memory-star-node__name">{data.label}</span>
      {data.retention < 0.3 && <span className="memory-star-node__fading" aria-hidden="true" />}
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

/** Visual projection of the existing KG decay policy; never changes stored state. */
function visualRetention(
  item: { importance: number; lastSeen?: string; type?: string },
  now = Date.now()
): number {
  const importance = Math.max(0, Math.min(1, item.importance))
  const seen = Date.parse(item.lastSeen || '')
  const hours = Number.isFinite(seen) ? Math.max(0, (now - seen) / 3_600_000) : 0
  const lifetime =
    importance >= 0.8
      ? 8760
      : !item.type
        ? 1440
        : ['person', 'preference', 'organization', 'technology', 'tool'].includes(item.type)
          ? 4320
          : ['project', 'goal'].includes(item.type)
            ? 1440
            : ['event', 'topic'].includes(item.type)
              ? 504
              : 720
  return importance * Math.exp(-hours / lifetime)
}

function retentionLabel(value: number): string {
  return value >= 0.6 ? '记忆清晰' : value >= 0.3 ? '记忆渐淡' : '记忆微弱'
}

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

function nodeSize(item: GraphNode, isCore: boolean): number {
  return Math.round(isCore ? 76 : 42 + item.importance * 16)
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

function buildNodes(
  items: GraphNode[],
  edges: GraphEdge[],
  selectedId: string | null,
  detailLevel: DetailLevel
): MemoryFlowNode[] {
  const core = getCoreEntity(items)
  if (!core) return []
  // Group by actual factual connectivity. Semantic visibility never moves the layout.
  const adjacency = buildAdjacency(
    edges.filter(
      (edge) => edge.kind === 'fact' && edge.source !== core.id && edge.target !== core.id
    )
  )
  const remaining = items.filter((item) => item.id !== core.id)
  const ranked = [...remaining].sort(
    (a, b) =>
      (adjacency.get(b.id)?.size || 0) * 12 +
      entityWeight(b) -
      (adjacency.get(a.id)?.size || 0) * 12 -
      entityWeight(a)
  )
  const seeds: GraphNode[] = []
  for (const candidate of ranked) {
    if (seeds.length >= Math.min(8, Math.max(1, Math.ceil(remaining.length / 8)))) break
    if (
      !seeds.some(
        (seed) =>
          adjacency.get(seed.id)?.has(candidate.id) && (adjacency.get(candidate.id)?.size || 0) < 4
      )
    )
      seeds.push(candidate)
  }
  const owners = new Map(seeds.map((seed) => [seed.id, seed.id]))
  const queue = seeds.map((seed) => seed.id)
  for (let index = 0; index < queue.length; index++) {
    const id = queue[index]
    for (const neighbor of adjacency.get(id) || []) {
      if (owners.has(neighbor)) continue
      owners.set(neighbor, owners.get(id) as string)
      queue.push(neighbor)
    }
  }
  const groups = new Map(seeds.map((seed) => [seed.id, [] as GraphNode[]]))
  for (const item of remaining) {
    let owner = owners.get(item.id)
    if (!owner)
      owner =
        seeds.find((seed) => seed.type === item.type)?.id ||
        [...groups].sort((a, b) => a[1].length - b[1].length)[0]?.[0]
    if (owner) groups.get(owner)?.push(item)
  }
  const focusIds = connectedIds(edges, selectedId)
  const hasFocus = focusIds.size > 0
  const result: MemoryFlowNode[] = []
  const pushNode = (
    item: GraphNode,
    position: { x: number; y: number },
    isCore: boolean,
    clusterId: string
  ): void => {
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
        dropAnimation: null,
        retention: visualRetention(item),
        isHovered: false,
        delay: -((result.length * 0.71) % 8),
        clusterId
      },
      style: {
        width: size,
        height: size
      } as React.CSSProperties
    })
  }

  pushNode(core, { x: 0, y: 0 }, true, '')
  const groupRadius = Math.max(300, Math.sqrt(remaining.length) * 61)
  ;[...groups].forEach(([seedId, entries], groupIndex) => {
    const angle = -Math.PI / 2 + (groupIndex * Math.PI * 2) / groups.size
    const center = { x: Math.cos(angle) * groupRadius * 1.24, y: Math.sin(angle) * groupRadius }
    const sorted = [...entries].sort((a, b) =>
      a.id === seedId ? -1 : b.id === seedId ? 1 : entityWeight(b) - entityWeight(a)
    )
    sorted.forEach((item, index) => {
      const ring = Math.ceil(index / 7)
      const localAngle = angle + ((index - 1) * Math.PI * 2) / 7 + ring * 0.36
      pushNode(
        item,
        index === 0
          ? center
          : {
              x: center.x + Math.cos(localAngle) * ring * 157,
              y: center.y + Math.sin(localAngle) * ring * 128
            },
        false,
        seedId
      )
    })
  })
  // Resolve label collisions once; the graph stays still during reading and hover.
  for (let pass = 0; pass < 45; pass++) {
    for (let i = 0; i < result.length; i++)
      for (let j = i + 1; j < result.length; j++) {
        const a = result[i],
          b = result[j]
        const dx = b.position.x - a.position.x,
          dy = b.position.y - a.position.y
        if (Math.abs(dx) >= 142 || Math.abs(dy) >= 103) continue
        const moveX = 142 - Math.abs(dx),
          moveY = 103 - Math.abs(dy)
        const axis = moveX < moveY ? 'x' : 'y'
        const push = ((axis === 'x' ? moveX : moveY) + 1) / 2
        const sign = (axis === 'x' ? dx : dy) >= 0 ? 1 : -1
        if (!a.data.isCore) a.position[axis] -= push * sign
        if (!b.data.isCore) b.position[axis] += push * sign
      }
  }
  return result
}

function buildIslands(
  nodes: MemoryFlowNode[]
): Array<{ id: string; label: string; x: number; y: number; width: number; height: number }> {
  const groups = new Map<string, MemoryFlowNode[]>()
  nodes.forEach((node) => {
    if (node.data.clusterId)
      groups.set(node.data.clusterId, [...(groups.get(node.data.clusterId) || []), node])
  })
  return [...groups]
    .filter(([, group]) => group.length >= 3)
    .map(([id, group]) => {
      const minX = Math.min(...group.map((node) => node.position.x)) - 90
      const minY = Math.min(...group.map((node) => node.position.y)) - 80
      return {
        id,
        label: nodes.find((node) => node.id === id)?.data.label || '相关记忆',
        x: minX,
        y: minY,
        width: Math.max(...group.map((node) => node.position.x)) + 90 - minX,
        height: Math.max(...group.map((node) => node.position.y)) + 85 - minY
      }
    })
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
    const endpointRetention = Math.min(
      sourceNode?.data.retention ?? 1,
      targetNode?.data.retention ?? 1
    )
    const strength =
      factual && item.importance !== undefined
        ? Math.min(
            endpointRetention,
            visualRetention({ importance: item.importance, lastSeen: item.lastSeen })
          )
        : endpointRetention
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
      className: `${factual ? 'kg-edge-fact' : 'kg-edge-semantic'}${selectedId && isFocused ? ' is-focused' : ''}`,
      animated: !factual && Boolean(selectedId) && isFocused,
      data: {
        kind: item.kind,
        confidence: item.confidence,
        isFocused
      },
      label:
        selectedId && isFocused && factual ? RELATION_NAMES[item.label] || item.label : undefined,
      markerEnd: factual
        ? { type: MarkerType.ArrowClosed, width: 10, height: 10, color: 'var(--kg-fact-edge)' }
        : undefined,
      style: {
        stroke: factual ? 'var(--kg-fact-edge)' : 'var(--kg-semantic-edge)',
        strokeWidth: factual
          ? selectedId && isFocused
            ? 1.8
            : 1.15
          : selectedId && isFocused
            ? 1.4
            : 1,
        strokeDasharray: factual ? undefined : '4 7',
        strokeLinecap: 'round',
        opacity: isFocused
          ? selectedId
            ? 0.82
            : factual
              ? 0.28 + strength * 0.37
              : 0.18 + strength * 0.24
          : 0.07
      },
      labelStyle: {
        fill: 'var(--text-secondary)',
        fontSize: 12,
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
  const [hoveredId, setHoveredId] = React.useState<string | null>(null)
  const [filterOpen, setFilterOpen] = React.useState(false)
  const [loading, setLoading] = React.useState(true)
  const [loadError, setLoadError] = React.useState<string | null>(null)
  const [rebuilding, setRebuilding] = React.useState(false)
  const [selectedId, setSelectedId] = React.useState<string | null>(null)
  const [inspectorClosing, setInspectorClosing] = React.useState(false)
  const [evidence, setEvidence] = React.useState<Evidence[]>([])
  const [evidenceLoading, setEvidenceLoading] = React.useState(false)
  const [detailLevel, setDetailLevel] = React.useState<DetailLevel>('detail')
  const [viewportZoom, setViewportZoom] = React.useState(1)
  const [detailMenuOpen, setDetailMenuOpen] = React.useState(false)
  const [isDragging, setIsDragging] = React.useState(false)
  const [edit, setEdit] = React.useState<GraphEdit | null>(null)
  const [connecting, setConnecting] = React.useState(false)
  const [connectionSource, setConnectionSource] = React.useState<string | null>(null)
  const [motionPaused, setMotionPaused] = React.useState(document.hidden)
  React.useEffect(() => {
    const update = (): void => setMotionPaused(document.hidden)
    document.addEventListener('visibilitychange', update)
    return () => document.removeEventListener('visibilitychange', update)
  }, [])
  const dropAnimationRunRef = React.useRef(0)
  const inspectorCloseTimerRef = React.useRef<number | null>(null)
  const fitPendingRef = React.useRef(false)
  const [flowInstance, setFlowInstance] = React.useState<ReactFlowInstance<
    MemoryFlowNode,
    MemoryFlowEdge
  > | null>(null)
  const nodesRef = React.useRef<MemoryFlowNode[]>([])
  const evidenceRequestRef = React.useRef(0)
  const graphRequestRef = React.useRef(0)
  const seenItemsRef = React.useRef<Set<string> | null>(null)
  const canvasRef = React.useRef<HTMLElement | null>(null)
  const reducedMotion = React.useSyncExternalStore(
    React.useCallback((notify) => {
      const media = window.matchMedia('(prefers-reduced-motion: reduce)')
      media.addEventListener('change', notify)
      return () => media.removeEventListener('change', notify)
    }, []),
    () => window.matchMedia('(prefers-reduced-motion: reduce)').matches
  )
  React.useEffect(() => {
    if (loading || !nodes.length || !edges.length || !canvasRef.current) return
    const ids = new Set([...nodes, ...edges].map((item) => item.id))
    const previous = seenItemsRef.current
    seenItemsRef.current = ids
    if (reducedMotion) return
    const animations: Animation[] = []
    const frame = requestAnimationFrame(() => {
      canvasRef.current?.querySelectorAll<HTMLElement>('.react-flow__node').forEach((element) => {
        const id = element.dataset.id
        if (previous && id && !previous.has(id)) {
          const core = element.querySelector('.memory-star-node__core')
          if (core)
            animations.push(
              core.animate(
                [
                  { transform: 'scale(0.75)', opacity: 0.4 },
                  { transform: 'scale(1.12)', opacity: 1, offset: 0.45 },
                  { transform: 'scale(1)' }
                ],
                { duration: 600, easing: 'cubic-bezier(0.2,0.8,0.2,1)' }
              )
            )
        }
      })
      canvasRef.current
        ?.querySelectorAll<SVGPathElement>('.react-flow__edge-path')
        .forEach((path, index) => {
          const edge = path.closest('[data-id]')
          const id = edge?.getAttribute('data-id')
          if (previous && (!id || previous.has(id))) return
          const length = path.getTotalLength()
          // Preserve the semantic dash pattern after drawing finishes.
          animations.push(
            path.animate(
              [
                { strokeDasharray: String(length), strokeDashoffset: String(length) },
                { strokeDasharray: String(length), strokeDashoffset: '0' }
              ],
              { duration: 500, delay: previous ? 0 : Math.min(index * 5, 220), easing: 'ease-out' }
            )
          )
        })
    })
    return () => {
      cancelAnimationFrame(frame)
      animations.forEach((animation) => animation.cancel())
    }
  }, [loading, nodes.length, edges.length, reducedMotion])
  const focusId = connecting ? null : selectedId || hoveredId
  const islands = React.useMemo(() => buildIslands(nodes), [nodes])

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
  const layoutEdges = React.useMemo(
    () =>
      rawEdges.filter(
        (item) =>
          item.kind === 'fact' && visibleNodeIds.has(item.source) && visibleNodeIds.has(item.target)
      ),
    [rawEdges, visibleNodeIds]
  )
  const semanticRelated = React.useMemo(() => {
    const byId = new Map(rawNodes.map((item) => [item.id, item]))
    return rawEdges
      .filter(
        (edge) =>
          edge.kind === 'semantic' && (edge.source === selectedId || edge.target === selectedId)
      )
      .map((edge) => ({
        entity: byId.get(edge.source === selectedId ? edge.target : edge.source),
        edge
      }))
      .filter((item): item is RelatedEntity => Boolean(item.entity))
      .sort((a, b) => b.edge.confidence - a.edge.confidence)
  }, [rawEdges, rawNodes, selectedId])
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
    evidenceRequestRef.current += 1
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
    const nextNodes = buildNodes(visibleNodes, layoutEdges, null, 'detail')
    nodesRef.current = nextNodes
    setNodes(nextNodes)
  }, [setNodes, layoutEdges, visibleNodes])

  React.useEffect(() => {
    const focusedIds = connectedIds(visibleEdges, focusId)
    const hasFocus = focusedIds.size > 0
    setNodes((currentNodes) => {
      const nextNodes = currentNodes.map((node) => {
        const isDimmed = hasFocus && !focusedIds.has(node.id)
        const isHovered = node.id === hoveredId
        const selected = node.id === selectedId
        if (
          node.data.isDimmed === isDimmed &&
          node.data.detailLevel === detailLevel &&
          node.data.isHovered === isHovered &&
          node.selected === selected
        )
          return node
        return {
          ...node,
          selected,
          data: { ...node.data, isDimmed, detailLevel, isHovered }
        }
      })
      nodesRef.current = nextNodes
      return nextNodes
    })
    setEdges(buildEdges(visibleEdges, focusId, nodesRef.current))
  }, [detailLevel, focusId, hoveredId, selectedId, setEdges, setNodes, visibleEdges, visibleNodes])

  React.useEffect(() => {
    if (!fitPendingRef.current || !flowInstance || nodes.length === 0) return
    fitPendingRef.current = false
    const frame = window.requestAnimationFrame(() => {
      flowInstance.fitView({ padding: 0.13, maxZoom: 1.2, duration: reducedMotion ? 0 : 360 })
    })
    return () => window.cancelAnimationFrame(frame)
  }, [flowInstance, nodes, reducedMotion])

  const resetView = React.useCallback((): void => {
    flowInstance?.fitView({ padding: 0.13, maxZoom: 1.2, duration: reducedMotion ? 0 : 360 })
  }, [flowInstance, reducedMotion])

  React.useEffect(() => {
    if (!selectedId || !flowInstance || connecting || edit) return
    const ids = connectedIds(visibleEdges, selectedId)
    const narrow = window.matchMedia('(max-width: 760px)').matches
    let cancelled = false
    const frame = requestAnimationFrame(() =>
      flowInstance
        .fitView({
          nodes: [...ids].map((id) => ({ id })),
          padding: narrow ? 0.55 : 0.25,
          maxZoom: 1.15,
          duration: reducedMotion ? 0 : 320
        })
        .then(() => {
          if (!narrow || cancelled) return
          const viewport = flowInstance.getViewport()
          flowInstance.setViewport(
            { ...viewport, y: viewport.y - (canvasRef.current?.clientHeight || 520) * 0.25 },
            { duration: reducedMotion ? 0 : 180 }
          )
        })
    )
    return () => {
      cancelled = true
      cancelAnimationFrame(frame)
    }
  }, [selectedId, flowInstance, reducedMotion, visibleEdges, connecting, edit])

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
    async (search = query, focusAfter?: string): Promise<void> => {
      const requestId = ++graphRequestRef.current
      setLoading(true)
      setLoadError(null)
      try {
        const data = (await window.api.getKnowledgeGraph(
          search.trim() || undefined,
          250
        )) as GraphResponse
        if (requestId !== graphRequestRef.current) return
        if (data.status !== 'ok') throw new Error('backend unavailable')
        evidenceRequestRef.current += 1
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
        setSelectedId(data.nodes.some((node) => node.id === focusAfter) ? focusAfter! : null)
        setHoveredId(null)
        clearDropAnimation()
        setEvidence([])
        fitPendingRef.current = true
      } catch {
        if (requestId !== graphRequestRef.current) return
        setLoadError('知识图谱暂时无法连接')
        showToast('无法读取知识图谱，请确认 MindPet 本地后端已启动', 'error')
      } finally {
        if (requestId === graphRequestRef.current) setLoading(false)
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
    setEvidence([])
    const requestId = ++evidenceRequestRef.current
    try {
      const data = await window.api.getKnowledgeGraphEvidence(nodeId, 20)
      if (requestId === evidenceRequestRef.current) setEvidence(data.evidence || [])
    } catch {
      if (requestId === evidenceRequestRef.current) {
        setEvidence([])
        showToast('读取来源对话失败', 'error')
      }
    } finally {
      if (requestId === evidenceRequestRef.current) setEvidenceLoading(false)
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

  const openEdit = (value: GraphEdit): void => {
    setEdit(value)
    setConnecting(false)
    setConnectionSource(null)
    setDetailMenuOpen(false)
  }
  const stopConnecting = React.useCallback((): void => {
    setConnecting(false)
    setConnectionSource(null)
  }, [])
  React.useEffect(() => {
    if (!connecting) return
    const escape = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') stopConnecting()
    }
    window.addEventListener('keydown', escape)
    return () => window.removeEventListener('keydown', escape)
  }, [connecting, stopConnecting])
  const pickNode = (id: string): void => {
    if (edit) return
    if (!connecting) {
      void selectNode(id)
      return
    }
    if (!connectionSource) {
      setConnectionSource(id)
      setSelectedId(id)
      return
    }
    if (connectionSource === id) return
    openEdit({ kind: 'relation', source: connectionSource, target: id })
  }
  const connectNodes = ({ source, target }: Connection): void => {
    if (source && target && source !== target) openEdit({ kind: 'relation', source, target })
  }
  const editSaved = async (focusId?: string): Promise<void> => {
    setEdit(null)
    setQuery('')
    await loadGraph('', focusId)
    if (focusId) await selectNode(focusId)
    showToast('图谱已保存', 'success')
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
          <button
            className="kg-tool-button"
            type="button"
            onClick={() => openEdit({ kind: 'entity' })}
            disabled={Boolean(edit) || loading}
          >
            <Plus size={15} />
            新增实体
          </button>
          <button
            className={`kg-tool-button ${connecting ? 'is-active' : ''}`}
            type="button"
            aria-pressed={connecting}
            disabled={Boolean(edit) || loading || rawNodes.length < 2}
            onClick={() => {
              if (connecting) stopConnecting()
              else {
                setConnecting(true)
                setConnectionSource(selectedId)
              }
            }}
          >
            <Link2 size={15} />
            连线
          </button>
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
          <strong>{visibleNodes.length}</strong> / {stats.entityCount} 个实体
        </span>
        <span>
          <strong>{stats.relationCount}</strong> 条确认关系
        </span>
        <span>
          <Database size={13} />
          <strong>{stats.evidenceCount}</strong> 条来源记录
        </span>
        {stats.pendingExtractions > 0 && (
          <span className="kg-pending">
            <LoaderCircle size={13} className="kg-spin" />
            {stats.pendingExtractions} 项提取中
          </span>
        )}
        <span className="kg-edge-key">
          <i className="fact" />
          事实关系 <i className="semantic" />
          语义相似
        </span>
      </div>

      {connecting && (
        <div className="kg-connect-guide" role="status">
          <Link2 size={15} />
          <span>
            {connectionSource
              ? `已选择“${rawNodes.find((node) => node.id === connectionSource)?.label || '起点'}”，点击终点节点`
              : '点击起点节点，再点击终点；也可拖动节点边缘的连接点'}
          </span>
          <button type="button" onClick={stopConnecting}>
            取消连线
          </button>
        </div>
      )}

      <div
        className={[
          'knowledge-graph-workspace',
          isDragging ? 'is-dragging' : '',
          motionPaused || isDragging ? 'is-motion-paused' : '',
          connecting ? 'is-connecting' : ''
        ]
          .filter(Boolean)
          .join(' ')}
      >
        <section ref={canvasRef} className="knowledge-graph-canvas" aria-label="知识图谱画布">
          <KnowledgeGraphBackdrop />
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
              <span>手动创建第一颗记忆，或继续与 Agent 对话。</span>
              <button
                type="button"
                className="kg-empty-action"
                onClick={() => openEdit({ kind: 'entity' })}
              >
                <Plus size={14} />
                新增实体
              </button>
            </div>
          ) : (
            <ReactFlow<MemoryFlowNode, MemoryFlowEdge>
              style={
                {
                  '--kg-label-scale': Math.min(1.7, Math.max(1, 1 / viewportZoom))
                } as React.CSSProperties
              }
              nodes={nodes}
              edges={edges}
              nodeTypes={nodeTypes}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              onNodeClick={(_, node) => pickNode(node.id)}
              onEdgeClick={(_, edge) => {
                if (connecting || edit) return
                const fact = rawEdges.find((item) => item.id === edge.id && item.kind === 'fact')
                if (fact)
                  openEdit({
                    kind: 'relation',
                    id: fact.id,
                    source: fact.source,
                    target: fact.target,
                    label: fact.label,
                    importance: fact.importance
                  })
              }}
              onConnect={connectNodes}
              onNodeMouseEnter={(_, node) => setHoveredId(node.id)}
              onNodeMouseLeave={() => setHoveredId(null)}
              onNodeDrag={(_, node) => {
                nodesRef.current = nodesRef.current.map((item) =>
                  item.id === node.id ? node : item
                )
                setEdges(buildEdges(visibleEdges, focusId, nodesRef.current))
              }}
              onNodeDragStart={() => {
                setIsDragging(true)
                setHoveredId(null)
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
                if (!edit && !connecting) closeInspector()
              }}
              onMoveStart={() => setMotionPaused(true)}
              onMoveEnd={() => setMotionPaused(document.hidden)}
              onMove={(_, viewport) => {
                setViewportZoom(viewport.zoom)
                setDetailLevel(viewport.zoom < 0.36 ? 'constellation' : 'detail')
              }}
              onInit={setFlowInstance}
              minZoom={0.08}
              maxZoom={2.2}
              nodeOrigin={[0.5, 0.5]}
              onlyRenderVisibleElements
              nodesConnectable={connecting}
              connectionMode={ConnectionMode.Loose}
              isValidConnection={(connection) => connection.source !== connection.target}
              deleteKeyCode={null}
              proOptions={{ hideAttribution: true }}
            >
              <ViewportPortal>
                <div className="kg-islands" aria-hidden="true">
                  {islands.map((island) => (
                    <div
                      key={island.id}
                      className="kg-island"
                      style={{
                        left: island.x,
                        top: island.y,
                        width: island.width,
                        height: island.height
                      }}
                    >
                      <span>
                        {island.label}
                        <small>相关星群</small>
                      </span>
                    </div>
                  ))}
                </div>
              </ViewportPortal>
              <MiniMap
                pannable
                zoomable
                nodeColor={(node) => TYPE_COLORS[String(node.data.type)] || TYPE_COLORS.other}
              />
              <Controls showInteractive={false} />
            </ReactFlow>
          )}
          {rawNodes.length > 0 && (
            <div className="kg-field-label">
              <span>
                <Sparkles size={13} />
                {detailLevel === 'constellation'
                  ? '星群视图 · 放大查看名称'
                  : '拖动画布 · 滚轮缩放 · 点击查看来源'}
              </span>
              <span
                className="kg-strength-key"
                title="亮度依据已有重要度、类型和最后提及时间估算；仅用于展示，不改变检索和遗忘。"
              >
                <i />
                <i />
                <i />
                记忆由清晰到微弱
              </span>
            </div>
          )}
          {loading && rawNodes.length > 0 && (
            <div className="kg-canvas-status" role="status" aria-live="polite">
              <LoaderCircle size={14} className="kg-spin" />
              正在更新星图
            </div>
          )}
        </section>

        {edit && (
          <KnowledgeGraphEditor
            key={
              edit.kind === 'entity'
                ? `entity-${edit.entity?.id || 'new'}`
                : `relation-${edit.id || `${edit.source}-${edit.target}`}`
            }
            edit={edit}
            entities={rawNodes}
            typeNames={TYPE_NAMES}
            relationNames={RELATION_NAMES}
            onClose={() => setEdit(null)}
            onSaved={editSaved}
          />
        )}
        {selected && !edit && !connecting && (
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
                <button
                  className="kg-icon-button"
                  type="button"
                  title="编辑实体"
                  aria-label="编辑实体"
                  onClick={() => openEdit({ kind: 'entity', entity: selected })}
                >
                  <Pencil size={15} />
                </button>
                <button
                  className="kg-icon-button"
                  type="button"
                  title="从此节点连线"
                  aria-label="从此节点连线"
                  onClick={() => openEdit({ kind: 'relation', source: selected.id, target: '' })}
                >
                  <Link2 size={15} />
                </button>
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
            <div className="kg-retention" title="展示估算值，不改变后端记忆状态">
              <span>
                <i className={visualRetention(selected) < 0.3 ? 'is-fading' : ''} />
                {retentionLabel(visualRetention(selected))}
              </span>
              <small>展示保留度 {Math.round(visualRetention(selected) * 100)}%</small>
              <div aria-hidden="true">
                <i style={{ width: `${visualRetention(selected) * 100}%` }} />
              </div>
              {selected.lastSeen && (
                <p>最后提及 · {new Date(selected.lastSeen).toLocaleDateString('zh-CN')}</p>
              )}
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
                    <div className="kg-related-row" key={edge.id}>
                      <button type="button" onClick={() => void selectNode(entity.id)}>
                        <i style={{ background: TYPE_COLORS[entity.type] || TYPE_COLORS.other }} />
                        <span>{entity.label}</span>
                        <small>
                          {edge.target === selected.id ? '← ' : '→ '}
                          {RELATION_NAMES[edge.label] || edge.label}
                        </small>
                      </button>
                      <button
                        className="kg-icon-button kg-relation-edit"
                        type="button"
                        aria-label={`编辑${selected.label}与${entity.label}的关系`}
                        title="编辑关系"
                        onClick={() =>
                          openEdit({
                            kind: 'relation',
                            id: edge.id,
                            source: edge.source,
                            target: edge.target,
                            label: edge.label,
                            importance: edge.importance
                          })
                        }
                      >
                        <Pencil size={13} />
                      </button>
                    </div>
                  ))
                )}
              </div>
            </section>

            {semanticRelated.length > 0 && (
              <section className="kg-related-section kg-semantic-section">
                <div className="kg-section-heading">
                  <strong>语义相似</strong>
                  <span>{semanticRelated.length}</span>
                </div>
                <p className="kg-semantic-note">内容接近，表示相似程度。</p>
                <div className="kg-related-list">
                  {semanticRelated.map(({ entity, edge }) => (
                    <button key={edge.id} type="button" onClick={() => void selectNode(entity.id)}>
                      <i style={{ background: TYPE_COLORS[entity.type] || TYPE_COLORS.other }} />
                      <span>{entity.label}</span>
                      <small>{Math.round(edge.confidence * 100)}%</small>
                    </button>
                  ))}
                </div>
              </section>
            )}

            <section className="kg-evidence-section">
              <div className="kg-section-heading">
                <strong>来源记录</strong>
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
                      {item.sessionId === 'manual:knowledge-graph' && (
                        <span className="kg-manual-source">
                          <Pencil size={11} />
                          手动确认
                        </span>
                      )}
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
