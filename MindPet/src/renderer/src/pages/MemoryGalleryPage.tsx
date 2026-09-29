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
interface MemoryReflection {
  title?: string
  thought?: string
  createdAt?: string
  updatedAt?: string
  stale?: boolean
}
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
  reflection?: MemoryReflection
  reflectionStatus?: string
}
interface PortraitData {
  profile: ProfileItem[]
  insights: InsightItem[]
  growth: GrowthItem[]
  memories: RememberedItem[]
  reflectionBackfill: { status?: string; remaining?: number; message?: string }
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

const COSMOS_LAYOUT = {
  initialDepth: 820,
  focusDepth: 700,
  edgeBuffer: 640,
  cardRenderDepth: 4_800,
  distantRenderDepth: 8_600,
  virtualizeAfter: 60,
  frameSampleMs: 110
} as const

interface CosmosNode {
  item: GalleryItem
  index: number
  worldZ: number
  focusZ: number
  side: 'left' | 'right'
  lane: number
  yaw: number
  roll: number
}

interface CameraTarget {
  from: number
  to: number
  startedAt: number
  duration: number
}

interface CameraMotion {
  z: number
  velocity: number
  impulse: number
  minZ: number
  maxZ: number
  target: CameraTarget | null
}

interface PointerTrailPoint { x: number; y: number; time: number }
interface StardustPointer {
  active: boolean
  x: number
  y: number
  lastMove: number
  fadingUntil: number
  samples: PointerTrailPoint[]
}

interface MemoryPhotoFlight {
  itemId: string
  imageUri: string
  from: { left: number; top: number; width: number; height: number }
}

const MEMORY_PHOTO_FLIGHT_MS = 240
const MEMORY_DETAIL_UNFOLD_MS = 210
const MEMORY_DETAIL_CLOSE_MS = 280

function makeSeededRandom(seedText: string): () => number {
  let seed = 2166136261
  for (let index = 0; index < seedText.length; index += 1) {
    seed ^= seedText.charCodeAt(index)
    seed = Math.imul(seed, 16777619)
  }
  return () => {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0
    return seed / 4294967296
  }
}

function buildCosmosLayout(items: GalleryItem[]): CosmosNode[] {
  const nodes: CosmosNode[] = []
  const sideVisits = { left: 0, right: 0 }
  let index = 0
  let cursorZ = COSMOS_LAYOUT.initialDepth

  while (index < items.length) {
    const groupRandom = makeSeededRandom(`${items[index].id}:memory-group`)
    const groupSize = Math.min(items.length - index, 2 + Math.floor(groupRandom() * 3))
    const innerSpacing = 180 + groupRandom() * 70
    const groupGap = 320 + groupRandom() * 141
    const firstSide = groupRandom() < 0.5 ? 'left' : 'right'

    for (let localIndex = 0; localIndex < groupSize; localIndex += 1) {
      const item = items[index]
      const itemRandom = makeSeededRandom(`${item.id}:memory-photo`)
      const side = (localIndex % 2 === 0) === (firstSide === 'left') ? 'left' : 'right'
      const yaw = 23 + itemRandom() * 6 + (itemRandom() - 0.5) * 3
      const roll = (itemRandom() - 0.5) * 3.2
      const worldZ = cursorZ + localIndex * innerSpacing
      const laneBase = (sideVisits[side]++ % 2 === 0 ? -128 : 128)
      nodes.push({
        item,
        index,
        worldZ,
        focusZ: worldZ - COSMOS_LAYOUT.focusDepth,
        side,
        lane: laneBase + (itemRandom() - 0.5) * 36,
        yaw: side === 'left' ? yaw : -yaw,
        roll: side === 'left' ? roll : -roll
      })
      index += 1
    }

    cursorZ += (groupSize - 1) * innerSpacing + groupGap
  }

  // Approximate the CSS perspective projection and separate any unusually close
  // same-side frames vertically before their layout becomes interactive.
  for (let nodeIndex = 1; nodeIndex < nodes.length; nodeIndex += 1) {
    const node = nodes[nodeIndex]
    for (let previousIndex = nodeIndex - 1; previousIndex >= 0; previousIndex -= 1) {
      const previous = nodes[previousIndex]
      if (node.worldZ - previous.worldZ > 1_150) break
      if (node.side !== previous.side) continue

      const cameraZ = Math.min(node.worldZ, previous.worldZ) - COSMOS_LAYOUT.focusDepth
      const nodeDepth = Math.max(120, node.worldZ - cameraZ)
      const previousDepth = Math.max(120, previous.worldZ - cameraZ)
      const nodeScale = 1_260 / (1_260 + nodeDepth)
      const previousScale = 1_260 / (1_260 + previousDepth)
      const sideSign = node.side === 'left' ? -1 : 1
      const nodeX = sideSign * 270 * nodeScale
      const previousX = sideSign * 270 * previousScale
      const overlapWidth = Math.max(0, (248 * nodeScale + 248 * previousScale) / 2 - Math.abs(nodeX - previousX))
      const nodeY = node.lane * nodeScale
      const previousY = previous.lane * previousScale
      const overlapHeight = Math.max(0, (330 * nodeScale + 330 * previousScale) / 2 - Math.abs(nodeY - previousY))
      const smallerArea = Math.min(248 * 330 * nodeScale * nodeScale, 248 * 330 * previousScale * previousScale)
      if (smallerArea > 0 && overlapWidth * overlapHeight / smallerArea > 0.2) {
        const direction = node.lane >= previous.lane ? 1 : -1
        const projectedGap = (330 * nodeScale + 330 * previousScale) / 2 - Math.abs(nodeY - previousY) + 28
        node.lane += direction * projectedGap / nodeScale
      }
    }
  }

  return nodes
}

function nearestCosmosIndex(nodes: CosmosNode[], cameraZ: number): number {
  if (nodes.length === 0) return 0
  const next = firstCosmosNodeAtOrAfter(nodes, cameraZ + COSMOS_LAYOUT.focusDepth)
  if (next === 0) return nodes[0].index
  if (next >= nodes.length) return nodes[nodes.length - 1].index
  const previousDistance = Math.abs(nodes[next - 1].focusZ - cameraZ)
  const nextDistance = Math.abs(nodes[next].focusZ - cameraZ)
  return (previousDistance <= nextDistance ? nodes[next - 1] : nodes[next]).index
}

function firstCosmosNodeAtOrAfter(nodes: CosmosNode[], worldZ: number): number {
  let low = 0
  let high = nodes.length
  while (low < high) {
    const middle = Math.floor((low + high) / 2)
    if (nodes[middle].worldZ < worldZ) low = middle + 1
    else high = middle
  }
  return low
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, value))
}

function positiveModulo(value: number, modulus: number): number {
  return ((value % modulus) + modulus) % modulus
}

interface MemoryParticleCanvasProps {
  active: boolean
  hasMemories: boolean
  reducedMotion: boolean
  cameraMotionRef: React.MutableRefObject<CameraMotion>
  worldRef: React.RefObject<HTMLDivElement | null>
  pointerRef: React.MutableRefObject<StardustPointer>
  timelineRangeRef: React.RefObject<HTMLInputElement | null>
  timelineMarkerRef: React.RefObject<HTMLSpanElement | null>
  timelineBounds: { minimum: number; maximum: number }
  onCameraSample: (cameraZ: number, velocity: number) => void
}

function MemoryParticleCanvas({
  active,
  hasMemories,
  reducedMotion,
  cameraMotionRef,
  worldRef,
  pointerRef,
  timelineRangeRef,
  timelineMarkerRef,
  timelineBounds,
  onCameraSample
}: MemoryParticleCanvasProps): React.JSX.Element {
  const canvasRef = React.useRef<HTMLCanvasElement | null>(null)
  const cursorCanvasRef = React.useRef<HTMLCanvasElement | null>(null)

  React.useEffect(() => {
    const canvas = canvasRef.current
    const context = canvas?.getContext('2d', { alpha: true })
    const cursorCanvas = cursorCanvasRef.current
    const cursorContext = cursorCanvas?.getContext('2d', { alpha: true })
    if (!canvas || !context || !cursorCanvas || !cursorContext || !active) return

    const themeRoot = canvas.closest('.memory-cosmos-page')
    const isLightTheme = (): boolean => Boolean(themeRoot?.closest('.agent-window-container.light'))
    let lightTheme = isLightTheme()
    const readThemeColor = (property: string, fallback: [number, number, number]): [number, number, number] => {
      const value = themeRoot ? getComputedStyle(themeRoot).getPropertyValue(property).trim() : ''
      const hex = /^#([0-9a-f]{6})$/i.exec(value)
      if (hex) {
        const color = Number.parseInt(hex[1], 16)
        return [(color >> 16) & 255, (color >> 8) & 255, color & 255]
      }
      const rgb = /^rgba?\(\s*(\d+)\D+(\d+)\D+(\d+)/i.exec(value)
      return rgb ? [Number(rgb[1]), Number(rgb[2]), Number(rgb[3])] : fallback
    }
    let cosmosBlue = readThemeColor('--cosmos-blue', [113, 155, 184])
    let cosmosGold = readThemeColor('--cosmos-gold', [186, 141, 88])
    const rgba = (color: [number, number, number], alpha: number): string => `rgba(${color[0]}, ${color[1]}, ${color[2]}, ${alpha})`
    const visibleParticleColor = (color: [number, number, number], gold = false): [number, number, number] => {
      if (!lightTheme) return color
      const [red, green, blue] = color.map(channel => channel / 255)
      const maximum = Math.max(red, green, blue)
      const minimum = Math.min(red, green, blue)
      const delta = maximum - minimum
      const originalLightness = (maximum + minimum) / 2
      let hue = 0
      let saturation = 0
      if (delta > 0) {
        saturation = delta / (1 - Math.abs(2 * originalLightness - 1))
        if (maximum === red) hue = ((green - blue) / delta) % 6
        else if (maximum === green) hue = (blue - red) / delta + 2
        else hue = (red - green) / delta + 4
        hue = (hue * 60 + 360) % 360
      }
      saturation = Math.min(0.84, Math.max(gold ? 0.68 : 0.62, saturation * 1.85))
      const lightness = clamp(originalLightness + (gold ? 0.09 : 0.05), 0.55, 0.68)
      const chroma = (1 - Math.abs(2 * lightness - 1)) * saturation
      const second = chroma * (1 - Math.abs((hue / 60) % 2 - 1))
      const offset = lightness - chroma / 2
      const rgb = hue < 60 ? [chroma, second, 0]
        : hue < 120 ? [second, chroma, 0]
          : hue < 180 ? [0, chroma, second]
            : hue < 240 ? [0, second, chroma]
              : hue < 300 ? [second, 0, chroma]
                : [chroma, 0, second]
      return rgb.map(channel => Math.round((channel + offset) * 255)) as [number, number, number]
    }

    interface Particle {
      x: number
      y: number
      depth: number
      radius: number
      alpha: number
      phase: number
      flicker: number
      tint: number
    }
    const createLayer = (count: number, depthRange: number): Particle[] => Array.from({ length: count }, () => {
      const sign = Math.random() < 0.5 ? -1 : 1
      return {
        x: sign * (0.22 + Math.random() * 0.78),
        y: (Math.random() - 0.5) * 1.9,
        depth: Math.random() * depthRange,
        radius: 0.55 + Math.random() * 1.65,
        alpha: 0.16 + Math.random() * 0.52,
        phase: Math.random() * Math.PI * 2,
        flicker: 0.18 + Math.random() * 0.75,
        tint: Math.random()
      }
    })
    const farParticles = createLayer(140, 7600)
    const middleParticles = createLayer(96, 4800)
    const nearParticles = createLayer(56, 2500)
    let width = 0
    let height = 0
    let pixelRatio = 1
    let frameId = 0
    let previousFrame = 0
    let previousSample = 0
    let previousQualityCheck = 0
    let slowFrames = 0
    let qualityLevel = 0
    const qualityByLevel = [1, 0.86, 0.72, 0.58, 0.48]

    const resize = (): void => {
      const bounds = canvas.getBoundingClientRect()
      width = bounds.width
      height = bounds.height
      pixelRatio = Math.min(1.5, window.devicePixelRatio || 1)
      canvas.width = Math.max(1, Math.round(width * pixelRatio))
      canvas.height = Math.max(1, Math.round(height * pixelRatio))
      context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0)
      cursorCanvas.width = canvas.width
      cursorCanvas.height = canvas.height
      cursorContext.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0)
    }
    resize()
    const resizeObserver = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(resize) : null
    resizeObserver?.observe(canvas)
    if (!resizeObserver) window.addEventListener('resize', resize)

    const drawLayer = (
      particles: Particle[],
      depthRange: number,
      parallax: number,
      layer: 'far' | 'middle' | 'near',
      count: number,
      time: number,
      cameraZ: number,
      velocity: number
    ): void => {
      const centerX = width * 0.5
      const centerY = height * 0.49
      const focalLength = Math.max(520, Math.min(1180, width * 0.9))
      const cycleShift = cameraZ * parallax
      const speedRatio = Math.min(1, Math.abs(velocity) / 1900)
      const baseTrail = (layer === 'near' ? 23 : layer === 'middle' ? 10 : 3) * (lightTheme ? 1.32 : 1)
      const trailLength = reducedMotion ? 0 : speedRatio * baseTrail

      for (let index = 0; index < count; index += 1) {
        const particle = particles[index]
        const depth = 220 + positiveModulo(particle.depth - cycleShift, depthRange)
        const scale = focalLength / (focalLength + depth)
        const x = centerX + particle.x * width * 0.66 * scale
        const y = centerY + particle.y * height * 0.58 * scale
        if (x < -36 || x > width + 36 || y < -36 || y > height + 36) continue

        const pulse = reducedMotion ? 0.94 : 0.76 + Math.sin(time * 0.001 * particle.flicker + particle.phase) * 0.16
        const alpha = Math.min(0.94, particle.alpha * pulse * (layer === 'far' ? 0.72 : 0.92) * (lightTheme ? 1.58 : 1))
        const radius = Math.min(lightTheme ? 4.2 : 3.4, particle.radius * (lightTheme ? 0.62 + scale * 1.22 : 0.45 + scale * 0.95))
        const isGoldParticle = particle.tint > (lightTheme ? 0.5 : 0.72)
        const particleColor = visibleParticleColor(isGoldParticle ? cosmosGold : cosmosBlue, isGoldParticle)
        if (lightTheme && radius > 1.05) {
          context.beginPath()
          context.arc(x, y, radius * 2.65, 0, Math.PI * 2)
          context.fillStyle = rgba(particleColor, alpha * 0.2)
          context.fill()
        }
        if (trailLength > 0.45) {
          const directionX = x - centerX
          const directionY = y - centerY
          const distance = Math.max(1, Math.hypot(directionX, directionY))
          const outwardX = (directionX / distance) * trailLength
          const outwardY = (directionY / distance) * trailLength
          context.beginPath()
          if (velocity > 0) context.moveTo(x - outwardX, y - outwardY)
          else context.moveTo(x, y)
          if (velocity > 0) context.lineTo(x, y)
          else context.lineTo(x + outwardX, y + outwardY)
          context.strokeStyle = rgba(particleColor, alpha * (lightTheme ? 0.72 : 0.58))
          context.lineWidth = Math.max(0.5, radius * 0.65)
          context.stroke()
        }

        context.beginPath()
        context.arc(x, y, radius, 0, Math.PI * 2)
        context.fillStyle = rgba(particleColor, alpha)
        context.fill()
      }
    }

    const drawPointer = (time: number): void => {
      const pointer = pointerRef.current
      const trailFade = pointer.active ? 1 : clamp((pointer.fadingUntil - time) / 160, 0, 1)
      if (trailFade <= 0) {
        pointer.samples.length = 0
        return
      }
      const age = Math.max(0, time - pointer.lastMove)
      const idleFade = age > 1200 ? Math.max(0.48, 1 - (age - 1200) / 1800) : 1

      if (!reducedMotion) {
        const visibleSamples = pointer.samples.filter(sample => time - sample.time <= 260)
        if (visibleSamples.length > 1) {
          cursorContext.beginPath()
          visibleSamples.forEach((sample, index) => {
            if (index === 0) cursorContext.moveTo(sample.x, sample.y)
            else cursorContext.lineTo(sample.x, sample.y)
          })
          cursorContext.strokeStyle = lightTheme ? `rgba(39, 91, 143, ${0.34 * trailFade})` : `rgba(245, 211, 153, ${0.38 * trailFade})`
          cursorContext.lineWidth = 1.6
          cursorContext.lineCap = 'round'
          cursorContext.stroke()
        }
        for (let index = pointer.samples.length - 1; index >= 0; index -= 1) {
          const sample = pointer.samples[index]
          const sampleAge = time - sample.time
          if (sampleAge < 0 || sampleAge > 260) continue
          const life = 1 - sampleAge / 260
          const along = 1 - index / Math.max(1, pointer.samples.length)
          const particleCount = 1 + (index % 3)
          for (let particleIndex = 0; particleIndex < particleCount; particleIndex += 1) {
            const scatter = (particleIndex - (particleCount - 1) / 2) * 2.2
            cursorContext.beginPath()
            cursorContext.arc(sample.x - scatter * 0.65, sample.y + scatter, Math.max(0.85, 1.9 * life * (1 - along * 0.2)), 0, Math.PI * 2)
            cursorContext.fillStyle = rgba(visibleParticleColor(cosmosGold, true), life * (lightTheme ? 0.94 : 0.72) * trailFade)
            cursorContext.fill()
          }
        }
      }

      if (!pointer.active) return

      const glowRadius = (lightTheme ? 21 : 18) + Math.min(2, Math.sin(time * 0.003) * 0.8)
      const glow = cursorContext.createRadialGradient(pointer.x, pointer.y, 0, pointer.x, pointer.y, glowRadius)
      glow.addColorStop(0, `rgba(255, 245, 221, ${0.65 * idleFade})`)
      glow.addColorStop(0.35, rgba(visibleParticleColor(cosmosGold, true), (lightTheme ? 0.46 : 0.32) * idleFade))
      glow.addColorStop(1, rgba(visibleParticleColor(cosmosBlue), 0))
      cursorContext.beginPath()
      cursorContext.arc(pointer.x, pointer.y, glowRadius, 0, Math.PI * 2)
      cursorContext.fillStyle = glow
      cursorContext.fill()

      for (let index = 0; index < 3; index += 1) {
        const angle = time * 0.00045 + index * (Math.PI * 2 / 3)
        const orbit = 10.5 + (index % 2) * 2.1
        cursorContext.beginPath()
        cursorContext.arc(pointer.x + Math.cos(angle) * orbit, pointer.y + Math.sin(angle) * orbit, index === 0 ? 2 : 1.4, 0, Math.PI * 2)
        cursorContext.fillStyle = rgba(visibleParticleColor(index === 0 ? cosmosGold : cosmosBlue, index === 0), (lightTheme ? 1 : 0.86) * idleFade)
        cursorContext.fill()
      }

      cursorContext.beginPath()
      cursorContext.moveTo(pointer.x, pointer.y - 10)
      cursorContext.quadraticCurveTo(pointer.x + 1.6, pointer.y - 1.6, pointer.x + 10, pointer.y)
      cursorContext.quadraticCurveTo(pointer.x + 1.6, pointer.y + 1.6, pointer.x, pointer.y + 10)
      cursorContext.quadraticCurveTo(pointer.x - 1.6, pointer.y + 1.6, pointer.x - 10, pointer.y)
      cursorContext.quadraticCurveTo(pointer.x - 1.6, pointer.y - 1.6, pointer.x, pointer.y - 10)
      cursorContext.closePath()
      cursorContext.fillStyle = lightTheme ? `rgba(31, 73, 116, ${0.95 * idleFade})` : `rgba(255, 242, 210, ${0.96 * idleFade})`
      cursorContext.fill()
      cursorContext.strokeStyle = lightTheme ? `rgba(255, 255, 255, ${0.96 * idleFade})` : `rgba(26, 48, 76, ${0.94 * idleFade})`
      cursorContext.lineWidth = 1.35
      cursorContext.stroke()
      cursorContext.beginPath()
      cursorContext.arc(pointer.x, pointer.y, 2.8, 0, Math.PI * 2)
      cursorContext.fillStyle = lightTheme ? `rgba(255, 218, 142, ${idleFade})` : `rgba(197, 145, 71, ${idleFade})`
      cursorContext.shadowColor = lightTheme ? 'rgba(255, 255, 255, 0.9)' : 'rgba(255, 227, 177, 0.8)'
      cursorContext.shadowBlur = 7
      cursorContext.fill()
      cursorContext.shadowBlur = 0
    }

    const frame = (time: number): void => {
      const rawDelta = previousFrame === 0 ? 16 : time - previousFrame
      const deltaSeconds = clamp(rawDelta / 1000, 0.001, 0.032)
      previousFrame = time
      if (rawDelta > 36) slowFrames += 1

      const motion = cameraMotionRef.current
      const previousZ = motion.z
      if (!hasMemories) {
        motion.velocity = 0
        motion.impulse = 0
        motion.target = null
      } else if (motion.target) {
        const progress = clamp((time - motion.target.startedAt) / motion.target.duration, 0, 1)
        const eased = 1 - Math.pow(1 - progress, 4)
        motion.z = motion.target.from + (motion.target.to - motion.target.from) * eased
        motion.velocity = (motion.z - previousZ) / deltaSeconds
        if (progress >= 1) {
          motion.z = motion.target.to
          motion.velocity = 0
          motion.target = null
        }
      } else {
        motion.velocity = clamp(motion.velocity + motion.impulse, -1900, 1900)
        motion.impulse = 0
        const towardEdge = motion.velocity < 0 ? motion.z - motion.minZ : motion.maxZ - motion.z
        const edgeResistance = 0.14 + 0.86 * clamp(towardEdge / 460, 0, 1)
        motion.z += motion.velocity * deltaSeconds * edgeResistance
        if (motion.z <= motion.minZ) {
          motion.z = motion.minZ
          if (motion.velocity < 0) motion.velocity = 0
        }
        if (motion.z >= motion.maxZ) {
          motion.z = motion.maxZ
          if (motion.velocity > 0) motion.velocity = 0
        }
        motion.velocity *= Math.exp(-4.65 * deltaSeconds)
        if (Math.abs(motion.velocity) < 4) motion.velocity = 0
      }

      const world = worldRef.current
      world?.style.setProperty('--camera-z', `${motion.z.toFixed(2)}px`)
      if (world) {
        for (let childIndex = 0; childIndex < world.children.length; childIndex += 1) {
          const node = world.children[childIndex] as HTMLElement
          const worldZ = Number(node.dataset.worldZ)
          if (!Number.isFinite(worldZ)) continue
          const depth = worldZ - motion.z
          const approachFade = clamp((depth - 135) / 190, 0, 1)
          const distanceFade = clamp((8_500 - depth) / 2_600, 0, 1)
          const pointScale = node.classList.contains('is-distant') ? 0.34 : 1
          node.style.opacity = String(approachFade * distanceFade * pointScale)
          node.style.pointerEvents = !node.classList.contains('is-distant') && depth > 150 && depth < COSMOS_LAYOUT.distantRenderDepth ? 'auto' : 'none'
        }
      }
      const range = timelineRangeRef.current
      if (range) range.value = String(clamp(motion.z, timelineBounds.minimum, timelineBounds.maximum))
      const timelineProgress = timelineBounds.maximum > timelineBounds.minimum
          ? clamp((motion.z - timelineBounds.minimum) / (timelineBounds.maximum - timelineBounds.minimum), 0, 1)
          : 0.5
      timelineMarkerRef.current?.style.setProperty('left', `${timelineProgress * 100}%`)

      if (time - previousSample >= COSMOS_LAYOUT.frameSampleMs) {
        previousSample = time
        onCameraSample(motion.z, motion.velocity)
      }

      if (time - previousQualityCheck > 2600) {
        if (slowFrames >= 8) qualityLevel = Math.min(4, qualityLevel + 1)
        else if (slowFrames <= 1) qualityLevel = Math.max(0, qualityLevel - 1)
        cosmosBlue = readThemeColor('--cosmos-blue', cosmosBlue)
        cosmosGold = readThemeColor('--cosmos-gold', cosmosGold)
        lightTheme = isLightTheme()
        slowFrames = 0
        previousQualityCheck = time
      }

      context.clearRect(0, 0, width, height)
      const quality = qualityByLevel[qualityLevel]
      const velocity = !hasMemories || reducedMotion ? 0 : motion.velocity
      const ambientScale = hasMemories ? 1 : 0.48
      drawLayer(farParticles, 7600, 0.12, 'far', Math.floor(farParticles.length * quality * ambientScale), time, motion.z, velocity)
      drawLayer(middleParticles, 4800, 0.42, 'middle', hasMemories ? Math.floor(middleParticles.length * quality) : 7, time, motion.z, velocity)
      drawLayer(nearParticles, 2500, 1, 'near', hasMemories ? Math.floor(nearParticles.length * quality) : 0, time, motion.z, velocity)
      cursorContext.clearRect(0, 0, width, height)
      drawPointer(time)
      frameId = requestAnimationFrame(frame)
    }

    const start = (): void => {
      if (frameId || document.visibilityState === 'hidden') return
      previousFrame = performance.now()
      frameId = requestAnimationFrame(frame)
    }
    const stop = (): void => {
      if (frameId) cancelAnimationFrame(frameId)
      frameId = 0
      context.clearRect(0, 0, width, height)
      cursorContext.clearRect(0, 0, width, height)
    }
    const onVisibilityChange = (): void => {
      if (document.visibilityState === 'hidden') stop()
      else start()
    }
    document.addEventListener('visibilitychange', onVisibilityChange)
    start()

    return () => {
      stop()
      resizeObserver?.disconnect()
      if (!resizeObserver) window.removeEventListener('resize', resize)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [active, cameraMotionRef, hasMemories, onCameraSample, pointerRef, reducedMotion, timelineBounds.maximum, timelineBounds.minimum, timelineMarkerRef, timelineRangeRef, worldRef])

  return <>
    <canvas ref={canvasRef} className="memory-particle-canvas" aria-hidden="true" />
    <canvas ref={cursorCanvasRef} className="memory-cursor-canvas" aria-hidden="true" />
  </>
}

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

function memoryTitle(item: GalleryItem): string {
  if (item.story?.trim()) return item.story.trim().slice(0, 28)
  if (item.title?.trim() && item.title !== '一段新回忆') return item.title.trim()
  return `留在 ${dateLabel(item.event_at)} 的瞬间`
}

function rememberedTitle(item: RememberedItem): string {
  if (item.reflection?.stale) {
    if (item.reflectionStatus === 'failed') return '这段想法暂未整理成功'
    if (item.reflectionStatus === 'unavailable') return '这段想法等待模型配置'
    return '我正在重新整理这段想法'
  }
  const explicit = text(item.reflection?.title).trim()
  if (explicit) return explicit
  if (item.reflectionStatus === 'failed') return '这段想法暂未整理成功'
  if (item.reflectionStatus === 'unavailable') return '这段想法等待模型配置'
  return '我正在想这段记忆'
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

function correctionPrompt(understanding: string, thought: string): string {
  const source = understanding.slice(0, 400) || '这段记忆'
  const reflection = thought.slice(0, 240) || '这段想法还在整理中'
  return `请更正你对我的这条理解：“${source}”。MindPet 从这段理解中产生的想法是：“${reflection}”。请根据下面的实际情况，一起修正这条理解和对应的想法。\n实际情况是：`
}

export function MemoryGalleryPage(): React.JSX.Element {
  const [view, setView] = React.useState<ViewMode>('gallery')
  const [items, setItems] = React.useState<GalleryItem[]>([])
  const [selectedId, setSelectedId] = React.useState<string | null>(null)
  const [portrait, setPortrait] = React.useState<PortraitData>({ profile: [], insights: [], growth: [], memories: [], reflectionBackfill: {} })
  const [expandedMemoryId, setExpandedMemoryId] = React.useState<string | null>(null)
  const [openSourceMemoryId, setOpenSourceMemoryId] = React.useState<string | null>(null)
  const [portraitDraft, setPortraitDraft] = React.useState('')
  const [portraitReply, setPortraitReply] = React.useState('')
  const [portraitSaving, setPortraitSaving] = React.useState(false)
  const [curatorStatus, setCuratorStatus] = React.useState<any>(null)
  const [curatorRetrying, setCuratorRetrying] = React.useState(false)
  const [loading, setLoading] = React.useState(true)
  const [error, setError] = React.useState('')
  const [composerOpen, setComposerOpen] = React.useState(false)
  const [detailId, setDetailId] = React.useState<string | null>(null)
  const [detailPhase, setDetailPhase] = React.useState<'flying' | 'unfolding' | 'ready' | 'closing'>('ready')
  const [detailFlight, setDetailFlight] = React.useState<MemoryPhotoFlight | null>(null)
  const [failedGalleryImages, setFailedGalleryImages] = React.useState<Set<string>>(() => new Set())
  const [cameraZ, setCameraZ] = React.useState(0)
  const [cameraMoving, setCameraMoving] = React.useState(false)
  const [confirmDeleteId, setConfirmDeleteId] = React.useState<string | null>(null)
  const [reducedMotion, setReducedMotion] = React.useState(false)
  const [draft, setDraft] = React.useState({
    story: '',
    mood: 'neutral',
    eventAt: localDateValue(new Date()),
    imageUri: '',
    imageName: ''
  })
  const [saving, setSaving] = React.useState(false)
  const sceneRef = React.useRef<HTMLElement | null>(null)
  const worldRef = React.useRef<HTMLDivElement | null>(null)
  const cameraMotionRef = React.useRef<CameraMotion>({ z: 0, velocity: 0, impulse: 0, minZ: 0, maxZ: 0, target: null })
  const pointerRef = React.useRef<StardustPointer>({ active: false, x: 0, y: 0, lastMove: 0, fadingUntil: 0, samples: [] })
  const timelineRangeRef = React.useRef<HTMLInputElement | null>(null)
  const timelineMarkerRef = React.useRef<HTMLSpanElement | null>(null)
  const detailCameraPositionRef = React.useRef(0)
  const shareDialogRef = React.useRef<HTMLElement | null>(null)
  const shareCloseRef = React.useRef<HTMLButtonElement | null>(null)
  const detailDialogRef = React.useRef<HTMLElement | null>(null)
  const detailLayerRef = React.useRef<HTMLDivElement | null>(null)
  const detailPhotoRef = React.useRef<HTMLDivElement | null>(null)
  const detailFlyRef = React.useRef<HTMLDivElement | null>(null)
  const detailAnimationRef = React.useRef<Animation | null>(null)
  const detailCloseRef = React.useRef<HTMLButtonElement | null>(null)
  const detailReturnFocusRef = React.useRef<HTMLElement | null>(null)
  const detailSourceRef = React.useRef<HTMLElement | null>(null)
  const returnFocusRef = React.useRef<HTMLElement | null>(null)
  const savingRef = React.useRef(false)
  const selectedIdRef = React.useRef<string | null>(selectedId)
  selectedIdRef.current = selectedId

  const loadAll = React.useCallback(async (focusId?: string, silent = false): Promise<void> => {
    if (!silent) {
      setLoading(true)
      setError('')
    }
    try {
      const [galleryResult, portraitResult, curatorResult] = await Promise.all([
        window.api.getMemoryGallery(),
        window.api.getMemoryPortrait(),
        window.api.getMemoryCuratorStatus()
      ])
      if (galleryResult?.status !== 'ok') throw new Error(galleryResult?.message || '记忆星河暂时无法打开')
      const nextItems = Array.isArray(galleryResult.items) ? galleryResult.items : []
      const nextLayout = buildCosmosLayout(nextItems)
      setItems(nextItems)
      const currentId = selectedIdRef.current
      const targetId = focusId && nextItems.some((item: GalleryItem) => item.id === focusId)
        ? focusId
        : currentId && nextItems.some((item: GalleryItem) => item.id === currentId)
          ? currentId
          : nextItems[0]?.id || null
      const targetNode = nextLayout.find(node => node.item.id === targetId)
      const firstFocusZ = nextLayout[0]?.focusZ ?? 0
      const lastFocusZ = nextLayout[nextLayout.length - 1]?.focusZ ?? 0
      cameraMotionRef.current.minZ = firstFocusZ - COSMOS_LAYOUT.edgeBuffer
      cameraMotionRef.current.maxZ = lastFocusZ + COSMOS_LAYOUT.edgeBuffer
      if (focusId || (!silent && !currentId)) {
        const targetZ = focusId
          ? targetNode?.focusZ ?? 0
          : 0
        cameraMotionRef.current.z = clamp(targetZ, cameraMotionRef.current.minZ, cameraMotionRef.current.maxZ)
        cameraMotionRef.current.velocity = 0
        cameraMotionRef.current.impulse = 0
        cameraMotionRef.current.target = null
        setCameraZ(cameraMotionRef.current.z)
      } else {
        const preservedZ = clamp(cameraMotionRef.current.z, cameraMotionRef.current.minZ, cameraMotionRef.current.maxZ)
        if (preservedZ !== cameraMotionRef.current.z) {
          cameraMotionRef.current.z = preservedZ
          cameraMotionRef.current.velocity = 0
          cameraMotionRef.current.impulse = 0
          cameraMotionRef.current.target = null
          setCameraZ(preservedZ)
        }
      }
      if (targetNode) selectedIdRef.current = targetNode.item.id
      setSelectedId(targetId)
      if (portraitResult?.status === 'ok') {
        const nextPortrait = {
          profile: Array.isArray(portraitResult.profile) ? portraitResult.profile : [],
          insights: Array.isArray(portraitResult.insights) ? portraitResult.insights : [],
          growth: Array.isArray(portraitResult.growth) ? portraitResult.growth : [],
          memories: Array.isArray(portraitResult.memories) ? portraitResult.memories : [],
          reflectionBackfill: portraitResult.reflectionBackfill || {}
        }
        setPortrait(nextPortrait)
        setExpandedMemoryId(current => {
          if (current && nextPortrait.memories.some((item: RememberedItem) => item.id === current)) return current
          return null
        })
        if (!silent) setOpenSourceMemoryId(null)
      } else {
        if (!silent) setError(portraitResult?.message || 'MindPet 暂时无法整理长期记忆')
      }
      if (curatorResult?.status === 'ok') setCuratorStatus(curatorResult.curator || null)
    } catch (loadError) {
      if (!silent) setError(loadError instanceof Error ? loadError.message : '记忆星河暂时无法打开')
    } finally {
      if (!silent) setLoading(false)
    }
  }, [])

  const retryCurator = async (): Promise<void> => {
    if (curatorRetrying) return
    setCuratorRetrying(true)
    try {
      const result = await window.api.retryMemoryCurator()
      if (result?.status !== 'ok') setError(result?.message || '记忆馆长重试失败')
      await loadAll(undefined, true)
    } finally {
      setCuratorRetrying(false)
    }
  }

  React.useEffect(() => { void loadAll() }, [loadAll])

  React.useEffect(() => {
    if (view !== 'portrait' || portrait.reflectionBackfill.status !== 'pending') return
    const interval = setInterval(() => { void loadAll(undefined, true) }, 5000)
    return () => clearInterval(interval)
  }, [loadAll, portrait.reflectionBackfill.status, view])

  React.useEffect(() => {
    const preference = window.matchMedia('(prefers-reduced-motion: reduce)')
    const updatePreference = (): void => setReducedMotion(preference.matches)
    updatePreference()
    preference.addEventListener('change', updatePreference)
    return () => preference.removeEventListener('change', updatePreference)
  }, [])

  React.useEffect(() => {
    const clearPointer = (): void => {
      pointerRef.current.active = false
      pointerRef.current.fadingUntil = 0
      pointerRef.current.samples.length = 0
    }
    window.addEventListener('blur', clearPointer)
    return () => window.removeEventListener('blur', clearPointer)
  }, [])

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
    cameraMotionRef.current.velocity = 0
    cameraMotionRef.current.impulse = 0
    cameraMotionRef.current.target = null
    pointerRef.current.active = false
    pointerRef.current.fadingUntil = 0
    pointerRef.current.samples.length = 0
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

  const finishMemoryDetailClose = React.useCallback((): void => {
    const returnTarget = detailReturnFocusRef.current
    detailAnimationRef.current?.cancel()
    detailAnimationRef.current = null
    cameraMotionRef.current.z = detailCameraPositionRef.current
    cameraMotionRef.current.velocity = 0
    cameraMotionRef.current.impulse = 0
    cameraMotionRef.current.target = null
    setCameraZ(detailCameraPositionRef.current)
    setDetailId(null)
    setDetailPhase('ready')
    setDetailFlight(null)
    detailSourceRef.current = null
    requestAnimationFrame(() => {
      if (returnTarget?.isConnected) returnTarget.focus()
      else sceneRef.current?.focus()
      detailReturnFocusRef.current = null
    })
  }, [])

  const closeMemoryDetail = React.useCallback((): void => {
    if (!detailId || detailPhase === 'closing') return
    const dialog = detailDialogRef.current
    const source = detailSourceRef.current
    const from = dialog?.getBoundingClientRect()
    const to = source?.isConnected ? source.getBoundingClientRect() : null
    if (reducedMotion || detailPhase !== 'ready' || !dialog || !from || !to
        || from.width < 1 || from.height < 1 || to.width < 24 || to.height < 24) {
      finishMemoryDetailClose()
      return
    }

    const moveX = to.left + to.width / 2 - from.left - from.width / 2
    const moveY = to.top + to.height / 2 - from.top - from.height / 2
    const scaleX = to.width / from.width
    const scaleY = to.height / from.height
    setDetailPhase('closing')
    const animation = dialog.animate([
      { transform: 'translate3d(0, 0, 0) scale(1)', opacity: 1 },
      { transform: `translate3d(${moveX * 0.78}px, ${moveY * 0.78}px, 0) scale(${1 + (scaleX - 1) * 0.78}, ${1 + (scaleY - 1) * 0.78})`, opacity: 1, offset: 0.78 },
      { transform: `translate3d(${moveX}px, ${moveY}px, 0) scale(${scaleX}, ${scaleY})`, opacity: 0 }
    ], { duration: MEMORY_DETAIL_CLOSE_MS, easing: 'cubic-bezier(.55, 0, .85, .36)', fill: 'forwards' })
    detailAnimationRef.current = animation
    animation.onfinish = () => {
      if (detailAnimationRef.current !== animation) return
      // Keep the finished fill effect in place until React removes the dialog.
      // Canceling it here briefly restores the full-size dialog before unmount.
      detailAnimationRef.current = null
      finishMemoryDetailClose()
    }
  }, [detailId, detailPhase, finishMemoryDetailClose, reducedMotion])

  React.useEffect(() => () => detailAnimationRef.current?.cancel(), [])

  React.useLayoutEffect(() => {
    if (detailPhase !== 'flying' || !detailFlight || detailFlight.itemId !== detailId) return
    const layer = detailLayerRef.current
    const photo = detailPhotoRef.current
    const fly = detailFlyRef.current
    if (!layer || !photo || !fly || reducedMotion) {
      setDetailPhase(reducedMotion ? 'ready' : 'unfolding')
      if (reducedMotion) setDetailFlight(null)
      return
    }

    const layerRect = layer.getBoundingClientRect()
    const target = photo.getBoundingClientRect()
    const origin = detailFlight.from
    if (target.width < 1 || target.height < 1 || origin.width < 1 || origin.height < 1) {
      setDetailPhase('unfolding')
      return
    }
    const scale = Math.min(target.width / origin.width, target.height / origin.height)
    const targetLeft = target.left + (target.width - origin.width * scale) / 2
    const targetTop = target.top + (target.height - origin.height * scale) / 2
    const destination = `translate3d(${targetLeft - origin.left}px, ${targetTop - origin.top}px, 0) scale(${scale})`

    fly.style.left = `${origin.left - layerRect.left}px`
    fly.style.top = `${origin.top - layerRect.top}px`
    fly.style.width = `${origin.width}px`
    fly.style.height = `${origin.height}px`
    fly.style.opacity = '1'

    const animation = fly.animate([
      { transform: 'translate3d(0, 0, 0) scale(1)', borderRadius: '15px' },
      { transform: destination, borderRadius: '6px' }
    ], { duration: MEMORY_PHOTO_FLIGHT_MS, easing: 'cubic-bezier(.22, 1, .36, 1)', fill: 'forwards' })
    detailAnimationRef.current = animation
    animation.onfinish = () => {
      if (detailAnimationRef.current !== animation) return
      fly.style.transform = destination
      fly.style.borderRadius = '6px'
      animation.cancel()
      detailAnimationRef.current = null
      setDetailPhase('unfolding')
    }
    return () => {
      animation.onfinish = null
      if (detailAnimationRef.current === animation) {
        animation.cancel()
        detailAnimationRef.current = null
      }
    }
  }, [detailFlight, detailId, detailPhase, reducedMotion])

  React.useEffect(() => {
    if (detailPhase !== 'unfolding') return
    const timer = window.setTimeout(() => {
      setDetailPhase('ready')
      setDetailFlight(null)
    }, MEMORY_DETAIL_UNFOLD_MS)
    return () => window.clearTimeout(timer)
  }, [detailPhase])

  React.useEffect(() => {
    if (!detailId) return
    const focusFrame = requestAnimationFrame(() => {
      if (detailPhase !== 'closing') {
        if (detailPhase === 'ready') (detailCloseRef.current || detailDialogRef.current)?.focus()
        else detailDialogRef.current?.focus()
      }
    })
    const handleKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        event.preventDefault()
        closeMemoryDetail()
        return
      }
      if (event.key !== 'Tab' || !detailDialogRef.current) return
      if (detailPhase === 'closing') {
        event.preventDefault()
        return
      }
      const controls = Array.from(detailDialogRef.current.querySelectorAll<HTMLElement>(
        'button:not(:disabled), [tabindex]:not([tabindex="-1"])'))
      if (controls.length === 0) {
        event.preventDefault()
        detailDialogRef.current.focus()
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
    return () => {
      cancelAnimationFrame(focusFrame)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [closeMemoryDetail, detailId, detailPhase])

  const layoutNodes = React.useMemo(() => buildCosmosLayout(items), [items])
  const currentIndex = nearestCosmosIndex(layoutNodes, cameraZ)
  const detailItem = items.find(item => item.id === detailId) || null
  const timelineMinimum = layoutNodes.length === 1
    ? layoutNodes[0].focusZ - 300
    : layoutNodes[0]?.focusZ ?? cameraZ - 300
  const timelineMaximum = layoutNodes.length === 1
    ? layoutNodes[0].focusZ + 300
    : layoutNodes[layoutNodes.length - 1]?.focusZ ?? cameraZ + 300
  const timelineSpan = timelineMaximum - timelineMinimum
  const timelineNodes = React.useMemo(() => {
    if (layoutNodes.length <= 36) return layoutNodes
    const stride = Math.ceil(layoutNodes.length / 36)
    return layoutNodes.filter((_, index) => index % stride === 0 || index === layoutNodes.length - 1)
  }, [layoutNodes])
  const visibleGalleryEntries = React.useMemo(() => {
    if (layoutNodes.length <= COSMOS_LAYOUT.virtualizeAfter) {
      return layoutNodes.map(node => ({ ...node, distant: false, depth: node.worldZ - cameraZ }))
    }
    const entries: (CosmosNode & { distant: boolean; depth: number })[] = []
    const start = firstCosmosNodeAtOrAfter(layoutNodes, cameraZ + 150)
    const end = firstCosmosNodeAtOrAfter(layoutNodes, cameraZ + COSMOS_LAYOUT.distantRenderDepth)
    for (let nodeIndex = start; nodeIndex < end; nodeIndex += 1) {
      const node = layoutNodes[nodeIndex]
      const depth = node.worldZ - cameraZ
      if (depth < COSMOS_LAYOUT.cardRenderDepth) entries.push({ ...node, distant: false, depth })
      else if (node.index % 3 === 0) entries.push({ ...node, distant: true, depth })
    }
    return entries
  }, [cameraZ, layoutNodes])

  const onCameraSample = React.useCallback((nextZ: number, velocity: number): void => {
    setCameraZ(nextZ)
    setCameraMoving(Math.abs(velocity) > 12)
    const nearest = layoutNodes[nearestCosmosIndex(layoutNodes, nextZ)]
    if (nearest && selectedIdRef.current !== nearest.item.id) {
      selectedIdRef.current = nearest.item.id
      setSelectedId(nearest.item.id)
    }
  }, [layoutNodes])

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
    const nextLayout = buildCosmosLayout(nextItems)
    const firstFocusZ = nextLayout[0]?.focusZ ?? 0
    const lastFocusZ = nextLayout[nextLayout.length - 1]?.focusZ ?? 0
    const nextMinimum = firstFocusZ - COSMOS_LAYOUT.edgeBuffer
    const nextMaximum = lastFocusZ + COSMOS_LAYOUT.edgeBuffer
    cameraMotionRef.current.minZ = nextMinimum
    cameraMotionRef.current.maxZ = nextMaximum
    const currentZ = cameraMotionRef.current.z
    const preservedZ = clamp(currentZ, nextMinimum, nextMaximum)
    if (preservedZ !== cameraMotionRef.current.z) {
      cameraMotionRef.current.target = {
        from: currentZ,
        to: preservedZ,
        startedAt: performance.now(),
        duration: reducedMotion ? 160 : 360
      }
    } else cameraMotionRef.current.target = null
    cameraMotionRef.current.velocity = 0
    cameraMotionRef.current.impulse = 0
    if (preservedZ === currentZ) cameraMotionRef.current.z = currentZ
    setCameraZ(preservedZ === currentZ ? currentZ : preservedZ)
    setItems(nextItems)
    const nextSelected = nextLayout[nearestCosmosIndex(nextLayout, preservedZ)]?.item.id || null
    selectedIdRef.current = nextSelected
    setSelectedId(nextSelected)
    setDetailId(null)
    setConfirmDeleteId(null)
    requestAnimationFrame(() => sceneRef.current?.focus())
  }

  const applyCameraPosition = React.useCallback((nextZ: number): void => {
    const motion = cameraMotionRef.current
    motion.target = null
    motion.z = clamp(nextZ, motion.minZ, motion.maxZ)
    motion.velocity = 0
    motion.impulse = 0
    setCameraZ(motion.z)
    worldRef.current?.style.setProperty('--camera-z', `${motion.z.toFixed(2)}px`)
    const nearest = layoutNodes[nearestCosmosIndex(layoutNodes, motion.z)]
    if (nearest && selectedIdRef.current !== nearest.item.id) {
      selectedIdRef.current = nearest.item.id
      setSelectedId(nearest.item.id)
    }
    if (timelineRangeRef.current) timelineRangeRef.current.value = String(clamp(motion.z, timelineMinimum, timelineMaximum))
    timelineMarkerRef.current?.style.setProperty('left', `${(timelineSpan > 0 ? clamp((motion.z - timelineMinimum) / timelineSpan, 0, 1) : 0.5) * 100}%`)
  }, [layoutNodes, timelineMaximum, timelineMinimum, timelineSpan])

  const navigateToIndex = React.useCallback((nextIndex: number): void => {
    if (layoutNodes.length === 0) return
    const index = Math.max(0, Math.min(layoutNodes.length - 1, nextIndex))
    const node = layoutNodes[index]
    setConfirmDeleteId(null)
    const motion = cameraMotionRef.current
    motion.velocity = 0
    motion.impulse = 0
    motion.target = {
      from: motion.z,
      to: clamp(node.focusZ, motion.minZ, motion.maxZ),
      startedAt: performance.now(),
      duration: reducedMotion ? 160 : 640
    }
    selectedIdRef.current = node.item.id
    setSelectedId(node.item.id)
  }, [layoutNodes, reducedMotion])

  const navigateByDepth = React.useCallback((distance: number): void => {
    const motion = cameraMotionRef.current
    motion.velocity = 0
    motion.impulse = 0
    motion.target = {
      from: motion.z,
      to: clamp(motion.z + distance, motion.minZ, motion.maxZ),
      startedAt: performance.now(),
      duration: reducedMotion ? 160 : 520
    }
  }, [reducedMotion])

  const onSceneWheel = (event: React.WheelEvent<HTMLElement>): void => {
    if (items.length < 2 || detailId || composerOpen || view !== 'gallery') return
    if ((event.target as HTMLElement).closest('.memory-time-axis, .memory-share-layer, .memory-detail-layer, .memory-orbit-controls')) return
    const lineHeight = 40
    const movement = event.deltaMode === 1
      ? event.deltaY * lineHeight
      : event.deltaMode === 2
        ? event.deltaY * Math.max(500, sceneRef.current?.clientHeight || 700)
        : event.deltaY
    if (Math.abs(movement) < 1) return
    event.preventDefault()
    const boundedMovement = clamp(movement, -180, 180)
    if (reducedMotion) {
      applyCameraPosition(cameraMotionRef.current.z + boundedMovement * 1.7)
      return
    }
    cameraMotionRef.current.target = null
    cameraMotionRef.current.impulse += boundedMovement * 7.8
  }

  const onSceneKeyDown = (event: React.KeyboardEvent<HTMLElement>): void => {
    const target = event.target as HTMLElement
    const corridorCard = target.closest('.memory-corridor-card')
    if (detailId || composerOpen || target.closest('input, textarea, .memory-time-axis, .memory-orbit-controls')
        || (target.closest('button') && !corridorCard)) return
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
      event.preventDefault()
      navigateToIndex(currentIndex + 1)
      if (target.closest('.memory-corridor-card')) requestAnimationFrame(() => sceneRef.current?.focus())
    }
    if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
      event.preventDefault()
      navigateToIndex(currentIndex - 1)
      if (target.closest('.memory-corridor-card')) requestAnimationFrame(() => sceneRef.current?.focus())
    }
    if (event.key === 'PageDown') {
      event.preventDefault()
      navigateByDepth(1_100)
      if (target.closest('.memory-corridor-card')) requestAnimationFrame(() => sceneRef.current?.focus())
    }
    if (event.key === 'PageUp') {
      event.preventDefault()
      navigateByDepth(-1_100)
      if (target.closest('.memory-corridor-card')) requestAnimationFrame(() => sceneRef.current?.focus())
    }
  }

  const onTimelineChange = (event: React.ChangeEvent<HTMLInputElement>): void => {
    applyCameraPosition(Number(event.currentTarget.value))
  }

  const onScenePointerMove = (event: React.PointerEvent<HTMLElement>): void => {
    const pointer = pointerRef.current
    const target = event.target as HTMLElement
    const interactive = target.closest('button:not(.memory-corridor-card), a, input, textarea, select, [role="slider"], .memory-cosmos-heading, .memory-time-axis, .memory-detail-layer, .memory-share-layer')
    if (event.pointerType !== 'mouse' || interactive || view !== 'gallery' || detailId || composerOpen) {
      if (pointer.active && interactive && !detailId && !composerOpen && view === 'gallery') pointer.fadingUntil = performance.now() + 160
      pointer.active = false
      return
    }
    const bounds = sceneRef.current?.getBoundingClientRect()
    if (!bounds) return
    const now = performance.now()
    pointer.active = true
    pointer.fadingUntil = 0
    pointer.x = event.clientX - bounds.left
    pointer.y = event.clientY - bounds.top
    pointer.lastMove = now
    pointer.samples.push({ x: pointer.x, y: pointer.y, time: now })
    while (pointer.samples.length > 24 || (pointer.samples[0] && now - pointer.samples[0].time > 280)) pointer.samples.shift()
    let trailLength = 0
    for (let index = 1; index < pointer.samples.length; index += 1) {
      trailLength += Math.hypot(
        pointer.samples[index].x - pointer.samples[index - 1].x,
        pointer.samples[index].y - pointer.samples[index - 1].y
      )
    }
    while (pointer.samples.length > 1 && trailLength > 104) {
      const oldest = pointer.samples.shift()
      const next = pointer.samples[0]
      if (oldest && next) trailLength -= Math.hypot(next.x - oldest.x, next.y - oldest.y)
    }
  }

  const onScenePointerLeave = (): void => {
    pointerRef.current.active = false
    pointerRef.current.fadingUntil = 0
    pointerRef.current.samples.length = 0
  }

  const openMemoryDetail = (item: GalleryItem, origin?: HTMLElement): void => {
    detailReturnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
    detailCameraPositionRef.current = cameraMotionRef.current.z
    cameraMotionRef.current.velocity = 0
    cameraMotionRef.current.impulse = 0
    cameraMotionRef.current.target = null
    pointerRef.current.active = false
    pointerRef.current.fadingUntil = 0
    pointerRef.current.samples.length = 0
    selectedIdRef.current = item.id
    setSelectedId(item.id)
    const source = origin?.querySelector<HTMLElement>('.memory-corridor-photo') || origin
    detailSourceRef.current = source || null
    const bounds = source?.getBoundingClientRect()
    if (!reducedMotion && item.image_uri && !failedGalleryImages.has(item.id)
        && bounds && bounds.width >= 24 && bounds.height >= 24) {
      setDetailFlight({
        itemId: item.id,
        imageUri: item.image_uri,
        from: { left: bounds.left, top: bounds.top, width: bounds.width, height: bounds.height }
      })
      setDetailPhase('flying')
    } else {
      setDetailFlight(null)
      setDetailPhase(reducedMotion ? 'ready' : 'unfolding')
    }
    setDetailId(item.id)
  }

  const onCorridorClickCapture = (event: React.MouseEvent<HTMLElement>): void => {
    if (detailId || composerOpen || view !== 'gallery') return

    const target = event.target as HTMLElement
    if (target.closest('.memory-cosmos-heading, .memory-cosmos-error, .memory-orbit-controls, .memory-time-axis, .memory-detail-layer, .memory-share-layer, button:not(.memory-corridor-card), a, input, textarea, select')) return

    const stage = sceneRef.current?.querySelector<HTMLElement>('.memory-corridor-stage')
    if (!stage) return
    const stageBounds = stage.getBoundingClientRect()
    if (event.clientX < stageBounds.left || event.clientX > stageBounds.right
        || event.clientY < stageBounds.top || event.clientY > stageBounds.bottom) return

    let card = target.closest<HTMLButtonElement>('.memory-corridor-stage .memory-corridor-card')
    if (!card) {
      const candidates = Array.from(stage.querySelectorAll<HTMLButtonElement>('.memory-corridor-card'))
        .map(candidate => {
          const bounds = candidate.getBoundingClientRect()
          const node = candidate.closest<HTMLElement>('.memory-corridor-node')
          return {
            candidate,
            bounds,
            opacity: Number.parseFloat(getComputedStyle(node || candidate).opacity),
            zIndex: Number.parseInt(getComputedStyle(node || candidate).zIndex, 10) || 0
          }
        })
        .filter(({ bounds, opacity }) => opacity > 0.08
          && event.clientX >= bounds.left && event.clientX <= bounds.right
          && event.clientY >= bounds.top && event.clientY <= bounds.bottom)
        .sort((a, b) => b.zIndex - a.zIndex)
      card = candidates[0]?.candidate || null
    }

    const itemId = card?.dataset.memoryId
    const item = items.find(entry => entry.id === itemId)
    if (!item) return

    event.preventDefault()
    event.stopPropagation()
    openMemoryDetail(item, card || undefined)
  }

  const changeView = (nextView: ViewMode): void => {
    pointerRef.current.active = false
    pointerRef.current.fadingUntil = 0
    pointerRef.current.samples.length = 0
    if (nextView === 'portrait') {
      cameraMotionRef.current.velocity = 0
      cameraMotionRef.current.impulse = 0
      cameraMotionRef.current.target = null
    }
    setView(nextView)
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
      changeView('portrait')
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
          <button type="button" className={view === 'gallery' ? 'active' : ''} onClick={() => changeView('gallery')}><Images size={18} /><span><strong>回忆星河</strong><small>在照片间穿行</small></span></button>
          <button type="button" className={view === 'portrait' ? 'active' : ''} onClick={() => changeView('portrait')}><Brain size={18} /><span><strong>MindPet 记得</strong><small>关于你的长期理解</small></span></button>
        </div>
        <button className="memory-cosmos-share" type="button" onClick={() => { changeView('gallery'); openComposer() }}><ImagePlus size={18} /><span><strong>分享一段回忆</strong><small>原图保存在本机</small></span></button>
        <div className="memory-cosmos-sidebar-note"><i />原图保存在本机</div>
      </aside>

      <section
        ref={sceneRef}
        className={`memory-cosmos-scene is-${view} ${cameraMoving ? 'is-camera-moving' : ''}`}
        tabIndex={0}
        onClickCapture={onCorridorClickCapture}
        onWheel={view === 'gallery' ? onSceneWheel : undefined}
        onKeyDown={view === 'gallery' ? onSceneKeyDown : undefined}
        onPointerMove={onScenePointerMove}
        onPointerLeave={onScenePointerLeave}
      >
        <div className="memory-cosmos-nebula" aria-hidden="true"><i /><i /><i /></div>
        {(view === 'portrait' || loading) && <div className="memory-cosmos-stars" aria-hidden="true">
          {cosmosStars.map((star, index) => <i key={index} style={{
            '--star-x': `${star.x}%`, '--star-y': `${star.y}%`, '--star-size': `${star.size}px`,
            '--star-delay': `${star.delay}s`, '--star-duration': `${star.duration}s`,
            '--star-opacity': 0.18 + star.depth * 0.14, '--star-glow': `${star.size * 5}px`
          } as React.CSSProperties} />)}
        </div>}
        {view === 'gallery' && <MemoryParticleCanvas
          active={!loading && !detailId && !composerOpen}
          hasMemories={items.length > 1}
          reducedMotion={reducedMotion}
          cameraMotionRef={cameraMotionRef}
          worldRef={worldRef}
          pointerRef={pointerRef}
          timelineRangeRef={timelineRangeRef}
          timelineMarkerRef={timelineMarkerRef}
          timelineBounds={{ minimum: timelineMinimum, maximum: timelineMaximum }}
          onCameraSample={onCameraSample}
        />}

        <header className="memory-cosmos-heading">
          <div><span>{view === 'gallery' ? 'MEMORY ORBIT' : `WHAT MINDPET REMEMBERS · 共 ${rememberedItems.length} 件`}</span><h1>{view === 'gallery' ? '回忆在时间里发光' : 'MindPet 记得'}</h1><p>{view === 'gallery' ? '滚动前进或后退，在任意位置停下来看看。' : '这些是我从与你相处的片段里生出的想法。'}</p></div>
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
                <div className="memory-orbit-controls" role="group" aria-label="切换回忆">
                  <button type="button" disabled={currentIndex === 0} onClick={() => navigateToIndex(currentIndex - 1)} aria-label="前往上一段更新的回忆"><ChevronLeft size={18} /></button>
                  <span>滚轮前后 · 任意位置停留</span>
                  <button type="button" disabled={currentIndex === items.length - 1} onClick={() => navigateToIndex(currentIndex + 1)} aria-label="前往下一段更早的回忆"><ChevronRight size={18} /></button>
                </div>

                <div className="memory-corridor-stage" role="group" aria-label="回忆长廊">
                  <div className="memory-corridor-lightline" aria-hidden="true" />
                  <div ref={worldRef} className="memory-corridor-world" style={{ '--camera-z': `${cameraZ}px` } as React.CSSProperties}>
                    {visibleGalleryEntries.map(({ item, index, worldZ, side, lane, yaw, roll, distant, depth }) => {
                      if (distant) return <article
                        key={item.id}
                        data-world-z={worldZ}
                        className={`memory-corridor-node is-${side} is-distant`}
                        aria-hidden="true"
                        style={{
                          '--corridor-depth': `${worldZ}px`,
                          '--corridor-lane': `${lane}px`,
                          '--corridor-yaw': `${yaw}deg`,
                          '--corridor-roll': `${roll}deg`,
                          zIndex: 1
                        } as React.CSSProperties}
                      ><i /></article>
                      const imageFailed = failedGalleryImages.has(item.id)
                      const hiddenFromAssistiveTech = depth < 250 || depth > 3_300
                      return <article
                        key={item.id}
                        data-world-z={worldZ}
                        aria-hidden={hiddenFromAssistiveTech ? true : undefined}
                        className={`memory-corridor-node is-${side} ${index === currentIndex ? 'is-nearest' : ''}`}
                        style={{
                          '--corridor-depth': `${worldZ}px`,
                          '--corridor-lane': `${lane}px`,
                          '--corridor-yaw': `${yaw}deg`,
                          '--corridor-roll': `${roll}deg`,
                          '--corridor-opacity': clamp(1 - Math.max(0, depth - 1_000) / 5_000, 0.15, 1),
                          zIndex: Math.max(1, 60 - Math.floor(depth / 120))
                        } as React.CSSProperties}
                      >
                        <button
                          className={`memory-corridor-card ${item.image_uri && !imageFailed ? 'has-photo' : ''}`}
                          type="button"
                          data-memory-id={item.id}
                          style={{
                            '--photo-float-duration': `${(6.4 + ((index * 7) % 23) / 10).toFixed(1)}s`,
                            '--photo-float-delay': `${-((index * 13) % 65) / 10}s`,
                            '--sparkle-duration': `${(3.6 + ((index * 19) % 14) / 10).toFixed(1)}s`,
                            '--sparkle-phase': `${-((index * 17) % 31) / 10}s`
                          } as React.CSSProperties}
                          tabIndex={hiddenFromAssistiveTech ? -1 : 0}
                          aria-hidden={hiddenFromAssistiveTech ? true : undefined}
                          aria-label={`查看 ${memoryTitle(item)}，${dateLabel(item.event_at)}`}
                        >
                          <span className="memory-corridor-photo">
                            {item.image_uri && !imageFailed
                              ? <img src={item.image_uri} alt="" onError={() => setFailedGalleryImages(current => new Set(current).add(item.id))} />
                              : <span className="memory-corridor-photo-fallback"><Heart size={30} strokeWidth={1.2} /></span>}
                            <span className="memory-corridor-caption">
                              <time>{dateLabel(item.event_at)}</time>
                              <strong>{memoryTitle(item)}</strong>
                            </span>
                          </span>
                          {item.image_uri && !imageFailed && <span className="memory-corridor-sparkles" aria-hidden="true">
                            {Array.from({ length: 8 }, (_, sparkleIndex) => <i key={sparkleIndex} />)}
                          </span>}
                        </button>
                      </article>
                    })}
                  </div>
                </div>

                <div className="memory-time-axis" role="group" aria-label="回忆时间轴">
                  <div className="memory-time-direction"><span>最新</span><i /><span>更早</span></div>
                  <div className="memory-time-track">
                    <span className="memory-time-rail" aria-hidden="true" />
                    <span className="memory-time-progress" aria-hidden="true" style={{ width: `${timelineSpan > 0 ? clamp((cameraZ - timelineMinimum) / timelineSpan, 0, 1) * 100 : 50}%` }} />
                    <span ref={timelineMarkerRef} className="memory-time-cursor" aria-hidden="true" style={{ left: `${timelineSpan > 0 ? clamp((cameraZ - timelineMinimum) / timelineSpan, 0, 1) * 100 : 50}%` }} />
                    {timelineNodes.map(node => {
                      const position = timelineSpan > 0 ? clamp((node.focusZ - timelineMinimum) / timelineSpan, 0, 1) * 100 : 50
                      const showDateLabel = node.index === 0 || node.index === items.length - 1 || node.index % Math.max(1, Math.ceil(items.length / 4)) === 0
                      return <button
                        key={node.item.id}
                        type="button"
                        className={`memory-time-node ${node.index === currentIndex ? 'active' : ''} ${showDateLabel ? 'has-label' : ''}`}
                        style={{ left: `${position}%` }}
                        onClick={() => navigateToIndex(node.index)}
                        aria-label={`前往 ${dateLabel(node.item.event_at)} 的回忆：${memoryTitle(node.item)}`}
                        title={`${dateLabel(node.item.event_at)} · ${memoryTitle(node.item)}`}
                      ><i />{showDateLabel && <span>{shortDate(node.item.event_at)}</span>}</button>
                    })}
                    <input
                      ref={timelineRangeRef}
                      key={`${items[0]?.id || 'empty'}-${items[items.length - 1]?.id || 'empty'}-${items.length}`}
                      type="range"
                      min={timelineMinimum}
                      max={timelineMaximum}
                      step="any"
                      defaultValue={clamp(cameraZ, timelineMinimum, timelineMaximum)}
                      onChange={onTimelineChange}
                      aria-label="沿回忆时间轴连续浏览"
                      aria-valuetext={layoutNodes[currentIndex] ? `${dateLabel(layoutNodes[currentIndex].item.event_at)}附近` : '回忆星河'}
                    />
                  </div>
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
                  {curatorStatus?.due && <p className="memory-curator-status" role="status">
                    <span>记忆馆长待整理 {curatorStatus.pending_turns || 0} 个回合</span>
                    <button type="button" onClick={() => void retryCurator()} disabled={curatorRetrying}>
                      <RefreshCw size={13} />{curatorRetrying ? '整理中' : '立即整理'}
                    </button>
                  </p>}
                  {portrait.reflectionBackfill.status === 'pending' && <p className="memory-reflection-status" role="status">MindPet 正在把这些记忆整理成自己的想法…</p>}
                  {(portrait.reflectionBackfill.status === 'unavailable' || portrait.reflectionBackfill.status === 'failed') && <p className="memory-reflection-status is-error" role="status">{portrait.reflectionBackfill.message || '部分想法暂时没有整理成功。配置模型后可以刷新重试。'}</p>}

                  {rememberedItems.length > 0 ? (
                    <div className="memory-remembrance-timeline">
                      {rememberedItems.map((item, index) => {
                        const open = expandedMemoryId === item.id
                        const understanding = text(item.understanding || item.content)
                        const reflection = item.reflection
                        const thought = reflection?.stale ? '' : text(reflection?.thought)
                        const sourceOpen = openSourceMemoryId === item.id
                        const userMessage = rememberedEvidence(item)
                        const evidenceContext = rememberedEvidenceContext(item)
                        const assistantReply = rememberedReply(item)
                        const sourceImage = item.image_uri || item.source?.imageUri
                        const sourceGalleryId = item.source_gallery_id || item.source?.galleryItemId
                        return <article key={item.id} className={`memory-remembrance ${open ? 'is-open' : ''}`}>
                          <i className="memory-remembrance-point" aria-hidden="true" />
                          <button className="memory-remembrance-summary" type="button" aria-expanded={open} onClick={() => setExpandedMemoryId(open ? null : item.id)}>
                            <span className="memory-remembrance-index">{String(index + 1).padStart(2, '0')}</span>
                            <span className="memory-remembrance-heading">
                              <strong>{rememberedTitle(item)}</strong>
                              <span className="memory-remembrance-teaser">{thought || (item.reflectionStatus === 'failed' || item.reflectionStatus === 'unavailable' ? '这段想法暂时没有整理好。' : '我正在重新想起这段记忆…')}</span>
                              <small>{rememberedSourceLabel(item)} · {rememberedDate(item)}</small>
                            </span>
                            <ChevronDown size={17} />
                          </button>
                          {open && <div className="memory-remembrance-detail">
                            <div className="memory-remembrance-copy">
                              <div className="memory-remembrance-thought">
                                <span><Quote size={15} />这段记忆让我想到</span>
                                <p>{thought || (item.reflectionStatus === 'failed' || item.reflectionStatus === 'unavailable' ? '这段想法暂时没有整理好，请稍后重试。' : '我正在重新整理这段想法，完成后它会保存在这里。')}</p>
                              </div>
                              <button className="memory-remembrance-source-toggle" type="button" aria-expanded={sourceOpen} onClick={() => setOpenSourceMemoryId(sourceOpen ? null : item.id)}>
                                <BookOpen size={14} />{sourceOpen ? '收起原文' : '查看原文'}<ChevronDown size={14} />
                              </button>
                              {sourceOpen && <div className="memory-remembrance-evidence">
                                {sourceImage && sourceGalleryId && <button className="memory-remembrance-image" type="button" onClick={() => {
                                  const galleryIndex = items.findIndex(galleryItem => galleryItem.id === sourceGalleryId)
                                  if (galleryIndex >= 0) {
                                    changeView('gallery')
                                    navigateToIndex(galleryIndex)
                                    requestAnimationFrame(() => sceneRef.current?.focus())
                                  }
                                }}><img src={sourceImage} alt="这段记忆关联的照片" /><span>去回忆星河中查看</span></button>}
                                {userMessage
                                  ? <blockquote><span>你当时说</span><p>{userMessage}</p></blockquote>
                                  : <div className="memory-remembrance-context"><BookOpen size={15} /><div><span>没有找到可核对的原始对话</span>{evidenceContext && <p>{evidenceContext}</p>}</div></div>}
                                {assistantReply && <div className="memory-remembrance-reply"><MessageCircle size={15} /><div><span>我当时回应</span><p>{assistantReply}</p></div></div>}
                                {sourceImage && !sourceGalleryId && <div className="memory-remembrance-image is-unlinked"><img src={sourceImage} alt="这段记忆关联的照片" /></div>}
                              </div>}
                              <div className="memory-remembrance-source"><BookOpen size={14} /><span>{rememberedSourceLabel(item)} · {rememberedDate(item)}</span></div>
                              <button className="memory-remembrance-correct" type="button" onClick={() => setPortraitDraft(correctionPrompt(understanding || text(item.title), text(reflection?.thought)))}><RefreshCw size={13} />这条理解需要更正</button>
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

        {detailItem && <div ref={detailLayerRef} className={`memory-detail-layer is-${detailPhase}`}>
          <button className="memory-detail-backdrop" type="button" tabIndex={-1} aria-hidden="true" onClick={closeMemoryDetail} />
          <section ref={detailDialogRef} className="memory-detail-dialog" role="dialog" aria-modal="true" aria-labelledby="memory-detail-title" tabIndex={-1}>
            <button ref={detailCloseRef} className="memory-share-close" type="button" onClick={closeMemoryDetail} aria-label="关闭回忆详情"><X size={17} /></button>
            <div ref={detailPhotoRef} className="memory-detail-photo">
              {detailItem.image_uri && !failedGalleryImages.has(detailItem.id)
                ? <img src={detailItem.image_uri} alt={detailItem.story || detailItem.title || '回忆照片'} onError={() => setFailedGalleryImages(current => new Set(current).add(detailItem.id))} />
                : <div className="memory-corridor-photo-fallback"><Heart size={34} strokeWidth={1.2} /></div>}
            </div>
            <div className="memory-detail-copy">
              <div className="memory-detail-meta"><time>{dateLabel(detailItem.event_at)}</time><span><i style={{ background: (moodMeta[detailItem.mood || 'neutral'] || moodMeta.neutral).color }} />{(moodMeta[detailItem.mood || 'neutral'] || moodMeta.neutral).label}</span></div>
              <h2 id="memory-detail-title">{memoryTitle(detailItem)}</h2>
              {detailItem.story && <p className="memory-detail-story">{detailItem.story}</p>}
              <div className="memory-detail-response"><MessageCircle size={16} /><div><span>MindPet 当时对你说</span><p>{detailItem.ai_summary || detailItem.source_context || '这段旧回忆没有留下当时的回应。以后再次聊起它时，我会重新认识这个瞬间。'}</p></div></div>
              {(detailItem.source_type === 'manual' || detailItem.source_type === 'shared') && <button
                className={`memory-orbit-delete ${confirmDeleteId === detailItem.id ? 'is-confirming' : ''}`}
                type="button"
                aria-live="polite"
                onBlur={() => setConfirmDeleteId(current => current === detailItem.id ? null : current)}
                onClick={() => {
                  if (confirmDeleteId === detailItem.id) void removeMemory(detailItem)
                  else setConfirmDeleteId(detailItem.id)
                }}
              ><Trash2 size={14} />{confirmDeleteId === detailItem.id ? '再次点击，确认移除' : '移除这段回忆'}</button>}
            </div>
          </section>
          {detailFlight?.itemId === detailItem.id && detailPhase !== 'ready' && <div ref={detailFlyRef} className="memory-detail-fly" aria-hidden="true"><img src={detailFlight.imageUri} alt="" draggable={false} /></div>}
        </div>}

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
