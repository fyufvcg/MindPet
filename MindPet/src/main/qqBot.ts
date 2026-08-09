import { mkdirSync } from 'fs'
import { access, mkdir, writeFile } from 'fs/promises'
import { basename, extname, join } from 'path'
import QRCode from 'qrcode'
import type {
  FileKVStore,
  QQBot,
  QQBotInboundMessage,
  ReplyTarget
} from '@tencent-connect/qqbot-nodejs'
import { QQSecureStore } from './security/qq-secure-store'

type QQBotStatus = 'disconnected' | 'binding' | 'qrcode_ready' | 'connecting' | 'connected' | 'error'

interface QQLogEntry {
  level: 'info' | 'error'
  message: string
  timestamp: number
}

interface QQActiveChat {
  sessionId: string
  name: string
  scope: 'c2c' | 'group'
  targetId: string
  lastActiveAt: number
}

export interface QQBotState {
  status: QQBotStatus
  qrCodeDataUrl: string
  appId: string
  hasCredentials: boolean
  enabled: boolean
  messagesReceived: number
  messagesSent: number
  lastError: string
  activeChats: QQActiveChat[]
  logs: QQLogEntry[]
}

interface GeneratedFile {
  path?: string
  name?: string
  mimeType?: string
  url?: string
}

interface QQBotManagerOptions {
  secureStore: QQSecureStore
  getStorageDir: () => string
  callLlm: (
    config: Record<string, unknown>,
    messages: Array<{ role: string; content: unknown }>,
    sessionId: string,
    onToolEvent?: (event: { type: string; files?: GeneratedFile[] }) => void
  ) => Promise<string>
  ensureSession: (sessionId: string, name: string) => Promise<void>
  onStatusUpdated: () => void
  notifyRenderSessionUpdate: (sessionId?: string) => void
}

const IMAGE_EXTENSIONS = new Set(['.png', '.jpg', '.jpeg', '.gif', '.webp', '.bmp'])
const LOG_LIMIT = 80
const ACTIVE_CHAT_LIMIT = 30
const DEDUP_WINDOW_MS = 10 * 60 * 1000

function safeName(value: string): string {
  const sanitized = [...value]
    .map(character => character.charCodeAt(0) < 32 || '<>:"/\\|?*'.includes(character) ? '_' : character)
    .join('')
  return sanitized.slice(0, 120) || 'attachment'
}

function resolveLocalPath(rawValue: string): string {
  let value = rawValue.trim().replace(/^<|>$/g, '')
  if (value.startsWith('local-file:///')) value = value.slice('local-file:///'.length)
  else if (value.startsWith('local-file://')) value = value.slice('local-file://'.length)
  else if (value.startsWith('file:///')) value = value.slice('file:///'.length)
  if (/^\/[A-Za-z]:\//.test(value)) value = value.slice(1)
  try { value = decodeURIComponent(value) } catch { /* keep original value */ }
  return value.replace(/\//g, '\\')
}

function splitText(text: string, maxLength = 1800): string[] {
  const chunks: string[] = []
  let remaining = text.trim()
  while (remaining.length > maxLength) {
    let index = remaining.lastIndexOf('\n', maxLength)
    if (index < maxLength / 2) index = remaining.lastIndexOf('。', maxLength) + 1
    if (index < maxLength / 2) index = maxLength
    chunks.push(remaining.slice(0, index).trim())
    remaining = remaining.slice(index).trim()
  }
  if (remaining) chunks.push(remaining)
  return chunks
}

export class QQBotManager {
  private readonly options: QQBotManagerOptions
  private readonly state: QQBotState
  private bot: QQBot | null = null
  private sessionStore: FileKVStore | null = null
  private stopQrConnect: (() => void) | null = null
  private qrGeneration = 0
  private connectionGeneration = 0
  private readonly seenMessages = new Map<string, number>()
  private readonly sessionQueues = new Map<string, Promise<void>>()

  constructor(options: QQBotManagerOptions) {
    this.options = options
    const publicCredentials = options.secureStore.getPublicState()
    this.state = {
      status: 'disconnected',
      qrCodeDataUrl: '',
      appId: publicCredentials.appId,
      hasCredentials: publicCredentials.hasCredentials,
      enabled: publicCredentials.enabled,
      messagesReceived: 0,
      messagesSent: 0,
      lastError: '',
      activeChats: [],
      logs: []
    }
  }

  getState(): QQBotState {
    return {
      ...this.state,
      activeChats: this.state.activeChats.map(chat => ({ ...chat })),
      logs: this.state.logs.map(log => ({ ...log }))
    }
  }

  async startQrLogin(): Promise<boolean> {
    this.cancelQrLogin()
    const generation = this.qrGeneration
    this.state.status = 'binding'
    this.state.qrCodeDataUrl = ''
    this.state.lastError = ''
    this.addLog('info', '正在向 QQ 获取绑定二维码')
    this.emitState()

    const { startQrConnect } = await import('@tencent-connect/qqbot-connector')
    if (generation !== this.qrGeneration || this.state.status !== 'binding') return false

    this.stopQrConnect = startQrConnect({
      onQrDisplayed: (url) => {
        if (generation !== this.qrGeneration) return
        void QRCode.toDataURL(url, { width: 280, margin: 1, errorCorrectionLevel: 'M' })
          .then((dataUrl) => {
            if (generation !== this.qrGeneration) return
            this.state.status = 'qrcode_ready'
            this.state.qrCodeDataUrl = dataUrl
            this.emitState()
          })
          .catch((error) => this.setError(`生成 QQ 绑定二维码失败: ${this.errorMessage(error)}`))
      },
      onQrExpired: () => {
        if (generation !== this.qrGeneration) return
        this.state.status = 'binding'
        this.state.qrCodeDataUrl = ''
        this.addLog('info', 'QQ 绑定二维码已过期，正在刷新')
        this.emitState()
      },
      onSuccess: (credentials) => {
        if (generation !== this.qrGeneration) return
        this.stopQrConnect = null
        const credential = credentials[0]
        if (!credential) {
          this.setError('QQ 扫码成功，但未返回机器人凭证')
          return
        }
        try {
          this.options.secureStore.save({ ...credential, enabled: true })
          this.updateCredentialState()
          this.addLog('info', 'QQ 机器人绑定成功，正在建立连接')
          void this.connect(credential.appId, credential.appSecret)
        } catch (error) {
          this.setError(`安全保存 QQ 凭证失败: ${this.errorMessage(error)}`)
        }
      },
      onFailure: (error) => {
        if (generation !== this.qrGeneration) return
        this.stopQrConnect = null
        if (this.state.status !== 'disconnected') this.setError(`QQ 扫码绑定失败: ${error.message}`)
      }
    }, {
      displayQrCodeToConsole: false,
      source: 'mindpet'
    })
    return true
  }

  async connectManual(appId: string, appSecret: string): Promise<boolean> {
    const normalizedAppId = appId.trim()
    const normalizedSecret = appSecret.trim()
    if (!normalizedAppId || !normalizedSecret) throw new Error('请输入完整的 AppID 和 AppSecret')
    this.cancelQrLogin()
    this.options.secureStore.save({ appId: normalizedAppId, appSecret: normalizedSecret, enabled: true })
    this.updateCredentialState()
    await this.connect(normalizedAppId, normalizedSecret)
    return true
  }

  async reconnect(): Promise<boolean> {
    const credentials = this.options.secureStore.load()
    if (!credentials) throw new Error('尚未保存 QQ 机器人凭证，请先扫码或手动绑定')
    this.options.secureStore.setEnabled(true)
    this.updateCredentialState()
    await this.connect(credentials.appId, credentials.appSecret)
    return true
  }

  async disconnect(): Promise<boolean> {
    this.cancelQrLogin()
    this.options.secureStore.setEnabled(false)
    this.updateCredentialState()
    this.stopBot()
    this.state.status = 'disconnected'
    this.state.qrCodeDataUrl = ''
    this.state.lastError = ''
    this.addLog('info', 'QQ 机器人连接已断开')
    this.emitState()
    return true
  }

  async forgetCredentials(): Promise<boolean> {
    this.cancelQrLogin()
    this.stopBot()
    this.options.secureStore.clear()
    this.updateCredentialState()
    this.state.status = 'disconnected'
    this.state.qrCodeDataUrl = ''
    this.state.lastError = ''
    this.addLog('info', 'QQ 机器人绑定信息已删除')
    this.emitState()
    return true
  }

  autoReconnect(): void {
    try {
      const credentials = this.options.secureStore.load()
      if (credentials?.enabled) void this.connect(credentials.appId, credentials.appSecret)
    } catch (error) {
      this.setError(`读取 QQ 机器人凭证失败: ${this.errorMessage(error)}`)
    }
  }

  async shutdown(): Promise<void> {
    this.cancelQrLogin()
    this.stopBot()
    this.sessionStore?.flush()
    this.sessionStore = null
  }

  private async connect(appId: string, appSecret: string): Promise<void> {
    this.stopBot()
    const generation = ++this.connectionGeneration
    const sessionDir = join(this.options.getStorageDir(), 'qq-bot')
    mkdirSync(sessionDir, { recursive: true })
    const { FileKVStore, QQBot, kvSessionPersistence } = await import('@tencent-connect/qqbot-nodejs')
    if (generation !== this.connectionGeneration) return
    this.sessionStore = new FileKVStore({ dir: sessionDir, fileName: 'gateway-session.json' })

    const bot = new QQBot({
      appId,
      appSecret,
      accountId: appId,
      transport: 'websocket',
      tokenPrefetch: 'sync',
      sessionPersistence: kvSessionPersistence({ store: this.sessionStore, accountId: appId }),
      logger: {
        debug: (message) => console.debug('[QQBot]', message),
        info: (message) => console.info('[QQBot]', message),
        warn: (message) => console.warn('[QQBot]', message),
        error: (message) => console.error('[QQBot]', message)
      }
    })
    this.bot = bot
    this.state.status = 'connecting'
    this.state.qrCodeDataUrl = ''
    this.state.lastError = ''
    this.emitState()

    bot.on('ready', () => {
      if (generation !== this.connectionGeneration || this.bot !== bot) return
      this.state.status = 'connected'
      this.addLog('info', 'QQ 机器人已连接')
      this.emitState()
    })
    bot.on('resumed', () => {
      if (generation !== this.connectionGeneration || this.bot !== bot) return
      this.state.status = 'connected'
      this.addLog('info', 'QQ 机器人连接已恢复')
      this.emitState()
    })
    bot.on('error', (error) => {
      if (generation !== this.connectionGeneration || this.bot !== bot) return
      this.setError(`QQ 连接异常: ${this.errorMessage(error)}`)
    })
    bot.on('message', async (_context, message) => {
      if (generation !== this.connectionGeneration || this.bot !== bot) return
      await this.enqueueMessage(message)
    })

    void bot.start().catch((error) => {
      if (generation !== this.connectionGeneration || this.bot !== bot) return
      this.bot = null
      this.setError(`QQ 连接失败: ${this.errorMessage(error)}`)
    })
  }

  private enqueueMessage(message: QQBotInboundMessage): Promise<void> {
    if (message.senderIsBot || !this.acceptMessage(message.messageId)) return Promise.resolve()
    const sessionId = this.sessionIdFor(message)
    const previous = this.sessionQueues.get(sessionId) ?? Promise.resolve()
    const current = previous
      .catch(() => undefined)
      .then(() => this.handleMessage(message))
      .catch((error) => this.handleMessageError(message.replyTarget, error))
      .finally(() => {
        if (this.sessionQueues.get(sessionId) === current) this.sessionQueues.delete(sessionId)
      })
    this.sessionQueues.set(sessionId, current)
    return current
  }

  private async handleMessage(message: QQBotInboundMessage): Promise<void> {
    const sessionId = this.sessionIdFor(message)
    const chatName = message.kind === 'group'
      ? `QQ群聊 ${message.groupOpenid?.slice(-6) || message.replyTarget.targetId.slice(-6)}`
      : (message.senderName?.trim() || `QQ 用户 ${message.senderId.slice(-6)}`)
    this.updateActiveChat({
      sessionId,
      name: chatName,
      scope: message.replyTarget.scope,
      targetId: message.replyTarget.targetId,
      lastActiveAt: Date.now()
    })
    this.state.messagesReceived += 1
    this.addLog('info', `收到${message.kind === 'group' ? '群聊' : '单聊'}消息: ${chatName}`)
    this.emitState()

    await this.options.ensureSession(sessionId, chatName)
    const userContent = await this.buildUserContent(message, sessionId)
    if (typeof userContent === 'string' && !userContent.trim()) return

    const generatedFiles: GeneratedFile[] = []
    const reply = await this.options.callLlm(
      { messageId: Date.now(), contextRounds: 6 },
      [{ role: 'user', content: userContent }],
      sessionId,
      (event) => {
        if (event.type === 'generated_files' && Array.isArray(event.files)) {
          generatedFiles.push(...event.files)
        }
      }
    )
    await this.sendReply(message.replyTarget, reply, generatedFiles)
    this.options.notifyRenderSessionUpdate(sessionId)
  }

  private async buildUserContent(message: QQBotInboundMessage, sessionId: string): Promise<string | unknown[]> {
    const textParts: string[] = []
    if (message.kind === 'group') {
      textParts.push(`[QQ 群聊发送者：${message.senderName?.trim() || message.senderId}]`)
    }
    const messageText = message.content?.replace(/<@!?[^>]+>/g, '').trim()
    if (messageText) textParts.push(messageText)
    const imagePaths: string[] = []

    for (const attachment of message.attachments ?? []) {
      try {
        const downloaded = await this.downloadAttachment(
          attachment.url,
          attachment.filename || `qq-${Date.now()}${this.extensionForMime(attachment.content_type)}`,
          sessionId
        )
        if (attachment.content_type?.startsWith('image/')) imagePaths.push(downloaded)
        else textParts.push(`[QQ 附件已下载到本机，可使用文件工具读取：${downloaded}]`)
      } catch (error) {
        textParts.push(`[QQ 附件下载失败：${attachment.filename || attachment.url}；${this.errorMessage(error)}]`)
      }
    }

    const text = textParts.join('\n') || (imagePaths.length > 0 ? '请查看并处理我发送的图片。' : '')
    if (imagePaths.length === 0) return text
    return [
      { type: 'text', text },
      ...imagePaths.map(path => ({
        type: 'image_url',
        image_url: { url: `local-file:///${path.replace(/\\/g, '/')}` }
      }))
    ]
  }

  private async downloadAttachment(url: string, fileName: string, sessionId: string): Promise<string> {
    const directory = join(
      this.options.getStorageDir(),
      'chat',
      safeName(sessionId),
      'qq_inbox'
    )
    await mkdir(directory, { recursive: true })
    const targetPath = join(directory, `${Date.now()}_${safeName(basename(fileName))}`)
    const response = await fetch(url)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    await writeFile(targetPath, Buffer.from(await response.arrayBuffer()))
    return targetPath
  }

  private async sendReply(target: ReplyTarget, reply: string, generatedFiles: GeneratedFile[]): Promise<void> {
    const bot = this.bot
    if (!bot) throw new Error('QQ 机器人连接已断开')
    const localFiles = await this.collectLocalFiles(reply, generatedFiles)
    const localFileValues = new Set(localFiles.map(file => file.rawValue))
    const cleanedReply = reply
      .replace(/!?\[([^\]]*)\]\(([^)]+)\)/g, (full, label: string, value: string) => {
        return localFileValues.has(value.trim()) ? (label ? `\n${label}\n` : '\n') : full
      })
      .trim()

    for (const chunk of splitText(cleanedReply)) {
      await bot.sendText(target, chunk)
      this.state.messagesSent += 1
    }
    for (const file of localFiles) {
      if (IMAGE_EXTENSIONS.has(extname(file.path).toLowerCase()) || file.mimeType?.startsWith('image/')) {
        await bot.sendImage(target, { localPath: file.path })
      } else {
        await bot.sendFile(target, { localPath: file.path }, { fileName: file.name || basename(file.path) })
      }
      this.state.messagesSent += 1
    }
    this.emitState()
  }

  private async collectLocalFiles(reply: string, generatedFiles: GeneratedFile[]): Promise<Array<GeneratedFile & { path: string; rawValue: string }>> {
    const candidates: Array<GeneratedFile & { rawValue: string }> = []
    for (const file of generatedFiles) {
      const rawValue = file.path || file.url || ''
      if (rawValue) candidates.push({ ...file, rawValue })
    }
    const linkRegex = /!?\[([^\]]*)\]\(([^)]+)\)/g
    let match: RegExpExecArray | null
    while ((match = linkRegex.exec(reply)) !== null) {
      candidates.push({ name: match[1], path: match[2].trim(), rawValue: match[2].trim() })
    }

    const unique = new Map<string, GeneratedFile & { path: string; rawValue: string }>()
    for (const candidate of candidates) {
      const path = resolveLocalPath(candidate.path || candidate.url || '')
      if (!path || unique.has(path)) continue
      if (await access(path).then(() => true).catch(() => false)) {
        unique.set(path, { ...candidate, path })
      }
    }
    return [...unique.values()]
  }

  private async handleMessageError(target: ReplyTarget, error: unknown): Promise<void> {
    const message = this.errorMessage(error)
    this.addLog('error', `处理 QQ 消息失败: ${message}`)
    this.emitState()
    try {
      if (this.bot) {
        await this.bot.sendText(target, `处理消息时出现问题：${message}`)
        this.state.messagesSent += 1
        this.emitState()
      }
    } catch { /* connection may already be gone */ }
  }

  private sessionIdFor(message: QQBotInboundMessage): string {
    const scope = message.replyTarget.scope === 'group' ? 'group' : 'c2c'
    return `qq:${scope}:${message.replyTarget.targetId}`
  }

  private acceptMessage(messageId: string): boolean {
    const now = Date.now()
    for (const [id, timestamp] of this.seenMessages) {
      if (now - timestamp > DEDUP_WINDOW_MS) this.seenMessages.delete(id)
    }
    if (this.seenMessages.has(messageId)) return false
    this.seenMessages.set(messageId, now)
    return true
  }

  private updateActiveChat(chat: QQActiveChat): void {
    const remaining = this.state.activeChats.filter(item => item.sessionId !== chat.sessionId)
    this.state.activeChats = [chat, ...remaining].slice(0, ACTIVE_CHAT_LIMIT)
  }

  private stopBot(): void {
    this.connectionGeneration += 1
    this.bot?.stop()
    this.bot = null
    this.sessionStore?.flush()
    this.sessionStore = null
  }

  private cancelQrLogin(): void {
    this.qrGeneration += 1
    const stop = this.stopQrConnect
    this.stopQrConnect = null
    if (stop) stop()
  }

  private updateCredentialState(): void {
    const credentials = this.options.secureStore.getPublicState()
    this.state.appId = credentials.appId
    this.state.hasCredentials = credentials.hasCredentials
    this.state.enabled = credentials.enabled
  }

  private setError(message: string): void {
    this.state.status = 'error'
    this.state.lastError = message
    this.addLog('error', message)
    this.emitState()
  }

  private addLog(level: QQLogEntry['level'], message: string): void {
    this.state.logs = [
      { level, message: message.replace(/appsecret\s*[:=]\s*\S+/gi, 'AppSecret: [REDACTED]'), timestamp: Date.now() },
      ...this.state.logs
    ].slice(0, LOG_LIMIT)
  }

  private emitState(): void {
    this.options.onStatusUpdated()
  }

  private errorMessage(error: unknown): string {
    return error instanceof Error ? error.message : String(error)
  }

  private extensionForMime(mimeType: string): string {
    const normalized = mimeType?.split(';')[0].trim().toLowerCase()
    const extensions: Record<string, string> = {
      'image/png': '.png',
      'image/jpeg': '.jpg',
      'image/gif': '.gif',
      'image/webp': '.webp',
      'application/pdf': '.pdf',
      'text/plain': '.txt'
    }
    return extensions[normalized] || ''
  }
}
