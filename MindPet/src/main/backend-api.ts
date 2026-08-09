/**
 * Java 后端适配器 — 将 MindPet 的 LLM 调用转发到小晴 Java 后端。
 *
 * 流式协议：Java 后端返回 NDJSON（每行一个 JSON 对象），
 * 适配器将其转换为 MindPet 的 step 格式。
 *
 * Java 后端 NDJSON 格式（每行一个 JSON）：
 *   {"type":"text_delta","content":"增量文本"}
 *   {"type":"text","content":"最终完整回复"}
 *   {"type":"error","message":"错误信息"}
 *
 * 未来可扩展支持 tool_call / tool_result 等事件。
 */

/** Java 后端返回的 NDJSON 事件 */
interface BackendEvent {
  type: 'text_delta' | 'text' | 'error' | 'token_usage' | string
  content?: string
  message?: string
  /** 可能携带的状态 */
  status?: string
  /** token 用量（token_usage 事件携带） */
  promptTokens?: number
  completionTokens?: number
  totalTokens?: number
  model?: string
  provider?: string
  files?: BackendGeneratedFile[]
}

export interface BackendGeneratedFile {
  path: string
  name: string
  mimeType?: string
  url?: string
}

/** MindPet 内部的 step 类型（与 AgentExecutor 输出兼容） */
export interface BackendStep {
  type: string  // 'text_delta' | 'text' | 'error' | 'token_usage' | future types
  content?: string
  message?: string
  /** token 用量 */
  promptTokens?: number
  completionTokens?: number
  totalTokens?: number
  model?: string
  provider?: string
  files?: BackendGeneratedFile[]
}

/** LLM 调用配置 */
export interface ChatConfig {
  sessionId?: string
  messageId?: number
  apiKey?: string
  provider?: string
  model?: string
  temperature?: number
  maxTokens?: number
  [key: string]: any
}

/** Java 后端消息格式 */
interface BackendMessage {
  role: string
  content: string
  images?: string[]  // base64 encoded images
}

/** 后端 API 基地址 */
const BACKEND_BASE_URL = process.env.XIAOQING_API_URL || 'http://127.0.0.1:8080'
const DESKTOP_USER_ID = 'desktop-user'

export interface DesktopNotification {
  id: string
  title: string
  body: string
  category?: string
  createdAt: number
}

async function acknowledgeDesktopNotification(notificationId: string): Promise<boolean> {
  const response = await fetch(
    `${BACKEND_BASE_URL}/api/desktop/notifications/${encodeURIComponent(notificationId)}/ack?userId=${encodeURIComponent(DESKTOP_USER_ID)}`,
    { method: 'POST', signal: AbortSignal.timeout?.(10_000) }
  )
  return response.ok
}

/**
 * Polls the backend's durable notification outbox. The callback returns false
 * when delivery should be retried; otherwise the event is acknowledged.
 */
export function startDesktopNotificationPolling(
  deliver: (notification: DesktopNotification) => Promise<boolean> | boolean,
  intervalMs = 15_000
): () => void {
  let stopped = false
  let inFlight = false
  let lastErrorAt = 0
  const deliveredIds = new Set<string>()

  const reportFailure = (error: unknown): void => {
    const now = Date.now()
    if (now - lastErrorAt < 60_000) return
    lastErrorAt = now
    console.warn('[BackendAPI] Desktop notification poll failed:', error)
  }

  const poll = async (): Promise<void> => {
    if (stopped || inFlight) return
    inFlight = true
    try {
      const response = await fetch(
        `${BACKEND_BASE_URL}/api/desktop/notifications?userId=${encodeURIComponent(DESKTOP_USER_ID)}&limit=20`,
        { signal: AbortSignal.timeout?.(10_000) }
      )
      if (!response.ok) throw new Error(`Java backend returned ${response.status}`)
      const payload = await response.json()
      const notifications = Array.isArray(payload?.notifications) ? payload.notifications : []
      for (const notification of notifications) {
        if (!notification?.id || !notification?.title || !notification?.body) continue
        if (!deliveredIds.has(notification.id)) {
          const delivered = await deliver(notification as DesktopNotification)
          if (!delivered) continue
          deliveredIds.add(notification.id)
          if (deliveredIds.size > 200) {
            const oldestId = deliveredIds.values().next().value
            if (oldestId) deliveredIds.delete(oldestId)
          }
        }
        await acknowledgeDesktopNotification(notification.id)
      }
    } catch (error) {
      reportFailure(error)
    } finally {
      inFlight = false
    }
  }

  void poll()
  const timer = setInterval(() => void poll(), Math.max(5_000, intervalMs))
  return () => {
    stopped = true
    clearInterval(timer)
  }
}

/**
 * 将 MindPet 的消息格式转换为 Java 后端可接受的格式。
 * 提取文本、图片 base64、文件内容。
 */
function convertMessages(messages: any[]): BackendMessage[] {
  return messages
    .filter((m) => m.role === 'user' || m.role === 'assistant')
    .map((m) => {
      let text = ''
      const images: string[] = []

      if (typeof m.content === 'string') {
        text = m.content
      } else if (Array.isArray(m.content)) {
        for (const part of m.content) {
          if (part.type === 'text') {
            text += (text ? '\n' : '') + part.text
          } else if (part.type === 'image_url') {
            // 读取 local-file:/// 图片转为 base64
            try {
              const url = part.image_url?.url || ''
              const filePath = url
                .replace('local-file:///', '')
                .replace(/^\/+([A-Za-z]:)/, '$1')
              if (filePath) {
                const fs = require('fs')
                if (fs.existsSync(filePath)) {
                  const buf = fs.readFileSync(filePath)
                  images.push(buf.toString('base64'))
                }
              }
            } catch { /* ignore */ }
          }
        }
      }

      return { role: m.role, content: text, images: images.length > 0 ? images : undefined }
    })
}

/**
 * 调用 Java 后端流式聊天接口。
 * 返回一个 AsyncGenerator，逐个产出 step 事件。
 */
export async function* callJavaBackend(
  config: ChatConfig,
  messages: any[],
  signal?: AbortSignal
): AsyncGenerator<BackendStep> {
  const backendMessages = convertMessages(messages)
  // 所有桌面会话统一使用固定 userId，共享同一套记忆
  const userId = DESKTOP_USER_ID

  const lastMsg = backendMessages.length > 0 ? backendMessages[backendMessages.length - 1] : null
  const body = JSON.stringify({
    userId,
    message: lastMsg ? lastMsg.content : '',
    images: lastMsg?.images || [],
    sessionId: config.sessionId,
    messageId: config.messageId,
    mode: config.mode || 'chat',
    contextRounds: (config as any).contextRounds || 6,
    activeSkills: (config as any).activeSkills || [],
    history: backendMessages.slice(0, -1)
  })

  const url = `${BACKEND_BASE_URL}/api/desktop/chat/stream`

  console.log('[BackendAPI] POST', url, 'userId:', userId)

  // Combine user abort signal with 2-minute timeout
  const timeoutSignal = AbortSignal.timeout?.(120_000)
  const combinedSignal = signal
    ? AbortSignal.any?.([signal, timeoutSignal].filter(Boolean) as AbortSignal[])
    : timeoutSignal

  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/x-ndjson, text/plain'
    },
    body,
    signal: combinedSignal
  })

  if (!response.ok) {
    const errText = await response.text().catch(() => '')
    throw new Error(`Java backend returned ${response.status}: ${errText}`)
  }

  const reader = response.body?.getReader()
  if (!reader) {
    throw new Error('No response body from Java backend')
  }

  const decoder = new TextDecoder()
  let buffer = ''

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || '' // 最后不完整的行保留到下次

      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed) continue

        try {
          const event: BackendEvent = JSON.parse(trimmed)
          yield {
            type: event.type,
            content: event.content,
            message: event.message,
            promptTokens: event.promptTokens,
            completionTokens: event.completionTokens,
            totalTokens: event.totalTokens,
            model: event.model,
            provider: event.provider,
            files: event.files
          }
        } catch {
          // NDJSON 每行必须是有效 JSON
          console.warn('[BackendAPI] Failed to parse NDJSON line:', trimmed.slice(0, 100))
        }
      }
    }

    // 处理最后的 buffer
    if (buffer.trim()) {
      try {
        const event: BackendEvent = JSON.parse(buffer.trim())
        yield {
          type: event.type,
          content: event.content,
          message: event.message,
          promptTokens: event.promptTokens,
          completionTokens: event.completionTokens,
          totalTokens: event.totalTokens,
          model: event.model,
          provider: event.provider,
          files: event.files
        }
      } catch {
        // ignore incomplete last line
      }
    }
  } finally {
    reader.releaseLock()
  }
}

/**
 * 调用 Java 后端非流式聊天（用于快捷聊天窗口）。
 * 直接返回完整文本。
 */
export async function callJavaBackendSimple(
  message: string,
  sessionId: string,
  activeSkills?: string[]
): Promise<string> {
  const url = `${BACKEND_BASE_URL}/api/desktop/chat`

  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      userId: DESKTOP_USER_ID,
      message,
      sessionId,
      activeSkills: activeSkills || []
    })
  })

  if (!response.ok) {
    throw new Error(`Java backend returned ${response.status}`)
  }

  const data = await response.json()
  return data.reply || data.content || data.text || JSON.stringify(data)
}
