import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { SSEClientTransport } from '@modelcontextprotocol/sdk/client/sse.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'
import * as fs from 'fs'
import { join } from 'path'
import { getDefaultDataDir } from '../../storage-path'
import type { ToolResult } from '../core/types'
import { McpNameMapper } from './mcp-name-mapper.js'
import {
  loadSecureSystemMcpConfig,
  sanitizeSystemMcpConfig,
  saveSecureSystemMcpConfig
} from '../../security/secure-mcp-config'
import type { RuntimeMcpConfig } from '../../security/mcp-config-store'

export interface McpServerConfig {
  id: string
  name: string
  url: string
  apiKey: string
  hasApiKey?: boolean
  type?: 'sse' | 'stream' | 'auto'
  enabled: boolean // legacy persistence field; task selection is controlled by /
  source?: 'DESKTOP_BUILTIN' | 'USER_MCP'
  description?: string
  tools?: any[] // 工具定义缓存字段
  timeout?: number // 超时时间（秒），可选
}

type McpClientTransport =
  | SSEClientTransport
  | StreamableHTTPClientTransport

export class McpManager {
  private static instance: McpManager
  private connections: Map<
    string,
    { client: Client; transport: McpClientTransport; tools: any[]; config: McpServerConfig }
  > = new Map()
  private pendingConfigs: McpServerConfig[] = []
  public systemMcpConfig: { servers: McpServerConfig[] } = { servers: [] }
  private toolsCache: Record<string, any[]> = {}
  private connecting = new Map<string, Promise<boolean>>()
  private failedConnections = new Map<string, { signature: string; until: number }>()
  private catalogRevision = 0
  private connectionSignature(config: McpServerConfig): string {
    return JSON.stringify([config.url, config.type, config.apiKey])
  }


  private constructor() {}

  private isLegacyPaddleMcpConfig(config: McpServerConfig): boolean {
    const legacy = config as unknown as { preset?: string; type?: string }
    return legacy.preset === 'paddleocr-aistudio' || legacy.type === 'stdio'
  }

  private isRunnable(config: McpServerConfig): boolean {
    return Boolean(config.url)
  }

  private displayEndpoint(config: McpServerConfig): string {
    try { const url = new URL(config.url); return url.origin + url.pathname }
    catch { return '无效的服务地址' }
  }

  private createTransport(config: McpServerConfig): McpClientTransport {
    const headers: Record<string, string> = {}
    if (config.apiKey) headers.Authorization = `Bearer ${config.apiKey}`
    if (config.type === 'sse') {
      return new SSEClientTransport(new URL(config.url), {
        eventSourceInitDict: { headers }
      } as any)
    }
    return new StreamableHTTPClientTransport(new URL(config.url), {
      requestInit: { headers }
    })
  }

  public static getInstance(): McpManager {
    if (!McpManager.instance) {
      McpManager.instance = new McpManager()
    }
    return McpManager.instance
  }

  // 设置配置但不立即连接（懒加载模式）
  public setConfigs(configs: McpServerConfig[]) {
    this.catalogRevision++
    // 1. 同步将缓存的 tools 还原到新配置的内存对象中，防 tools 缓存丢失
    this.systemMcpConfig.servers = configs.map(config => {
      const s = { ...config, enabled: true, source: config.id === 'desktop-tools' ? 'DESKTOP_BUILTIN' as const : 'USER_MCP' as const }
      if (this.toolsCache[s.id]) {
        return { ...s, tools: this.toolsCache[s.id] }
      }
      return s
    })

    this.pendingConfigs = this.systemMcpConfig.servers.filter(
      c => c.enabled && this.isRunnable(c)
    )
    console.log(`[MCP] 已加载 ${this.pendingConfigs.length} 个 MCP 服务配置（懒加载模式，将在首次使用时连接）`)

    // 同时断开已不再启用的旧连接
    const enabledIds = this.pendingConfigs.map(c => c.id)
    for (const [id, conn] of this.connections.entries()) {
      const current = this.pendingConfigs.find(config => config.id === id)
      if (!enabledIds.includes(id) || !current || this.connectionSignature(current) !== this.connectionSignature(conn.config)) {
        console.log(`[MCP] 断开已禁用的服务: ${conn.config.name} (${id})`)
        conn.client.close().catch(() => {})
        this.connections.delete(id)
      }
    }
  }

  // 仅在首次加载、且发现有启用服务未缓存工具 Schema 时，才进行后台连接以拉取定义
  public async ensureConnected(): Promise<void> {
    const configsToConnect = this.systemMcpConfig.servers.filter(s => {
      const failed = this.failedConnections.get(s.id)
      return s.enabled && this.isRunnable(s) && (!s.tools || s.tools.length === 0)
        && !(failed?.signature === this.connectionSignature(s) && failed.until > Date.now())
    })
    if (configsToConnect.length === 0) return

    const needingConnection = configsToConnect.filter(config => !this.connections.has(config.id))
    if (needingConnection.length === 0) return

    console.log(`[MCP] 发现 ${needingConnection.length} 个未缓存工具定义的服务，执行初始化拉取连接...`)
    await this.connectAll(needingConnection)
  }

  public async connectAll(configs: McpServerConfig[]) {
    // This may be a missing-schema subset; setConfigs owns removal of old connections.
    await Promise.all(configs.filter(c => c.enabled && this.isRunnable(c)).map(config => this.connectSingleServer(config, false)))
  }

  // 针对单个服务进行单独连接（被呼叫时按需触发）
  public async connectSingleServer(config: McpServerConfig, force = true): Promise<boolean> {
    const signature = this.connectionSignature(config)
    const key = config.id + ':' + signature
    const pending = this.connecting.get(key)
    if (pending) return pending
    const existing = this.connections.get(config.id)
    if (existing && this.connectionSignature(existing.config) === signature) return true
    const failure = this.failedConnections.get(config.id)
    if (!force && failure?.signature === signature && failure.until > Date.now()) return false
    const operation = this.openSingleServer(config).then(ok => {
      if (ok) this.failedConnections.delete(config.id)
      else this.failedConnections.set(config.id, { signature, until: Date.now() + 60000 })
      return ok
    }).finally(() => this.connecting.delete(key))
    this.connecting.set(key, operation)
    return operation
  }

  private async openSingleServer(config: McpServerConfig): Promise<boolean> {
    console.log(
      `[MCP] 正在建立单体服务连接: ${config.name} -> ${this.displayEndpoint(config)}`
    )
    let failedClient: Client | undefined
    const timers: ReturnType<typeof setTimeout>[] = []
    try {
      let transport: McpClientTransport
      let client = new Client(
        { name: 'MindPet-Client', version: '1.0.0' },
        { capabilities: {} }
      )

      const connectTimeout = (ms: number) => new Promise<never>((_, reject) =>
        timers.push(setTimeout(() => reject(new Error(`连接超时 (${ms}ms)`)), ms))
      )

      failedClient = client
      const mcpType = config.type || 'stream'

      if (mcpType === 'stream') {
        transport = this.createTransport(config)
        await Promise.race([client.connect(transport), connectTimeout(5000)])
      } else if (mcpType === 'sse') {
        transport = this.createTransport(config)
        await Promise.race([client.connect(transport), connectTimeout(5000)])
      } else {
        try {
          transport = this.createTransport({ ...config, type: 'stream' })
          await Promise.race([client.connect(transport), connectTimeout(5000)])
        } catch (error: any) {
          if (error?.code === 401 || error?.code === 403) throw error
          await client.close().catch(() => {})
          client = new Client(
            { name: 'MindPet-Client', version: '1.0.0' },
            { capabilities: {} }
          )
          failedClient = client
          transport = this.createTransport({ ...config, type: 'sse' })
          await Promise.race([client.connect(transport), connectTimeout(5000)])
        }
      }

      const tools = await this.listClientTools(client)
      const current = this.systemMcpConfig.servers.find(server => server.id === config.id)
      if (!current || this.connectionSignature(current) !== this.connectionSignature(config)) {
        await client.close(); return false
      }

      this.connections.set(config.id, { client, transport, tools, config })
      this.updateServerToolsCache(config.id, tools)
      console.log(`[MCP] 服务 ${config.name} 按需握手成功！更新了 ${tools.length} 个工具`)
      return true
    } catch (err: any) {
      await failedClient?.close().catch(() => {})
      if (err?.code === 401 || err?.code === 403) console.warn(`[MCP] ${config.name} 返回 HTTP ${err.code}：服务拒绝访问，请核对服务地址、API Token 和账号权限；后台暂停重试 60 秒。`)
      console.error(`[MCP] 握手单体服务 ${config.name} 失败 (${err?.code || '连接或协议异常'})`)
      return false
    } finally { timers.forEach(clearTimeout) }
  }

  private async listClientTools(client: Client): Promise<any[]> {
    const tools: any[] = [], seen = new Set<string>()
    let cursor: string | undefined
    do {
      const response = await client.listTools(cursor ? { cursor } : undefined, { timeout: 15000 })
      tools.push(...(response.tools || []))
      cursor = response.nextCursor
      if (cursor && (seen.has(cursor) || seen.size >= 128)) throw new Error('MCP 工具目录分页异常')
      if (cursor) seen.add(cursor)
    } while (cursor)
    return tools
  }

  // 更新工具缓存并持久化写入磁盘缓存文件
  private updateServerToolsCache(serverId: string, tools: any[]) {
    let changed = false
    const oldTools = this.toolsCache[serverId] || []
    if (JSON.stringify(oldTools) !== JSON.stringify(tools)) {
      this.toolsCache[serverId] = tools
      changed = true
    }

    // 同时也要更新内存中 systemMcpConfig 的内容，保证后续 getTools 等在内存中可用
    this.systemMcpConfig.servers = this.systemMcpConfig.servers.map(s => {
      if (s.id === serverId) {
        return { ...s, tools }
      }
      return s
    })

    if (changed) {
      try {
        const cachePath = join(getDefaultDataDir(), 'mcp_tools_cache.json')
        fs.writeFileSync(cachePath, JSON.stringify(this.toolsCache, null, 2), 'utf8')
        console.log(`[MCP] 已将服务 ${serverId} 的工具描述列表成功写入本地缓存磁盘文件`)
      } catch (e) {
        console.error('[MCP] 写入本地工具缓存文件失败:', e)
      }
    }
  }


  public async disconnectAll() {
    for (const conn of this.connections.values()) {
      try {
        await conn.client.close()
      } catch {}
    }
    this.connections.clear()
  }

  public getTools(): any[] {
    const allTools: any[] = []
    for (const server of this.systemMcpConfig.servers) {
      if (!server.enabled) continue
      
      const conn = this.connections.get(server.id)
      let serverTools: any[] = []
      if (conn) {
        serverTools = conn.tools
      } else if (server.tools && Array.isArray(server.tools)) {
        serverTools = server.tools
      }

      // 将每一个工具名称转化为安全的模型端 API 名字
      for (const t of serverTools) {
        allTools.push({
          ...t,
          name: McpNameMapper.forServer(server.id, t.name)
        })
      }
    }
    return allTools
  }

  public getActiveServers(): any[] {
    const list: any[] = []
    for (const server of this.systemMcpConfig.servers) {
      if (!server.enabled || !this.isRunnable(server)) continue
      
      const conn = this.connections.get(server.id)
      let toolsCount = 0
      let status = 'disconnected'
      
      if (conn) {
        toolsCount = conn.tools.length
        status = 'connected'
      } else if (server.tools && Array.isArray(server.tools)) {
        toolsCount = server.tools.length
        status = 'loaded'
      }

      list.push({
        id: server.id,
        name: server.name,
        url: this.displayEndpoint(server),
        description: server.description || '',
        toolsCount,
        status
      })
    }
    return list
  }

  public resolveTool(name: string): { server: McpServerConfig; tool: any } | undefined {
    const target = McpNameMapper.target(name)
    const realName = target?.toolName || McpNameMapper.toOriginalName(name)
    const matches = this.systemMcpConfig.servers.filter(server => server.enabled && (!target || server.id === target.serverId))
      .flatMap(server => (this.connections.get(server.id)?.tools || server.tools || [])
        .filter(tool => tool.name === realName).map(tool => ({ server, tool })))
    return matches.length === 1 ? matches[0] : undefined
  }

  public hasTool(name: string): boolean { return Boolean(this.resolveTool(name)) }

  public async getServerTools(serverId: string): Promise<any[]> {
    const server = this.systemMcpConfig.servers.find(item => item.id === serverId)
    if (!server || !server.enabled || !this.isRunnable(server)) return []
    const existing = this.connections.has(server.id)
    if (!await this.connectSingleServer(server, false)) throw new Error('MCP 服务连接失败或正在冷却，请稍后重试')
    const connection = this.connections.get(server.id)!
    if (existing) {
      connection.tools = await this.listClientTools(connection.client)
      this.updateServerToolsCache(server.id, connection.tools)
    }
    return [...connection.tools]
  }

  public async executeTool(name: string, args: any, abortSignal?: AbortSignal): Promise<ToolResult> {
    const target = this.resolveTool(name)
    const fail = (message: string, outcome: 'unknown' | 'not_executed'): ToolResult => ({
      content: message, success: false, outcome, error: { message }
    })
    if (!target) return fail(`工具不存在或名称有歧义: ${name}`, 'not_executed')
    if (abortSignal?.aborted) throw new Error('UserAborted')
    if (!this.connections.has(target.server.id) && !await this.connectSingleServer(target.server))
      return fail(`无法连接 MCP 服务 ${target.server.name}`, 'not_executed')
    if (abortSignal?.aborted) throw new Error('UserAborted')
    const conn = this.connections.get(target.server.id)
    if (!conn) return fail('MCP 连接不可用', 'not_executed')
    const seconds = Number(target.server.timeout || 60)
    const timeout = Math.min(600, Math.max(1, Number.isFinite(seconds) ? seconds : 60)) * 1000
    const controller = new AbortController()
    const onAbort = (): void => controller.abort()
    abortSignal?.addEventListener('abort', onAbort, { once: true })
    if (abortSignal?.aborted) controller.abort()
    const timer = setTimeout(() => controller.abort(), timeout)
    try {
      const response = await conn.client.callTool({ name: target.tool.name, arguments: args }, undefined,
        { signal: controller.signal, timeout }) as any
      const blocks = Array.isArray(response.content) ? response.content : []
      const text = blocks.filter(c => c.type === 'text').map(c => c.text).join('\n')
      return {
        content: text || 'MCP 已返回结果，详见结构化内容。',
        success: response.isError !== true, outcome: 'confirmed',
        state: { contentBlocks: blocks, structuredContent: response.structuredContent },
        ...(response.isError ? { error: { message: text || 'MCP 工具返回失败' } } : {})
      }
    } catch {
      return fail('MCP 调用中断或超时，操作结果未知。请先检查实际状态，禁止直接重复提交。', 'unknown')
    } finally {
      clearTimeout(timer)
      abortSignal?.removeEventListener('abort', onAbort)
    }
  }

  public loadSystemMcpConfig(): { servers: McpServerConfig[] } {
    try {
      const cachePath = join(getDefaultDataDir(), 'mcp_tools_cache.json')
      const loaded = loadSecureSystemMcpConfig()
      const loadedServers = loaded.servers as McpServerConfig[]
      const servers = loadedServers.filter(server => !this.isLegacyPaddleMcpConfig(server))
      if (servers.length !== loadedServers.length) {
        saveSecureSystemMcpConfig({ servers })
      }
      this.systemMcpConfig = { servers }
      if (loaded.secretMigrationPending) {
        console.warn('[Secrets] MCP credential migration is pending because OS encryption is unavailable')
      }

      // 尝试加载工具定义缓存
      if (fs.existsSync(cachePath)) {
        try {
          const cacheData = fs.readFileSync(cachePath, 'utf8')
          this.toolsCache = JSON.parse(cacheData)
        } catch {
          this.toolsCache = {}
        }
      }

      // 将缓存的 tools 还原到内存中的 servers 对象中，供系统运行时调用
      this.systemMcpConfig.servers = this.systemMcpConfig.servers.map(s => {
        if (this.toolsCache[s.id]) {
          return { ...s, tools: this.toolsCache[s.id] }
        }
        return s
      })

      this.setConfigs(this.systemMcpConfig.servers)
    } catch (e) {
      console.error('加载全局 MCP 配置文件失败:', e)
      this.systemMcpConfig = { servers: [] }
    }
    return this.systemMcpConfig
  }

  public saveSystemMcpConfig(config: Record<string, unknown>): { servers: McpServerConfig[] } {
    const rawServers = Array.isArray(config.servers) ? config.servers as McpServerConfig[] : []
    // 保护 Desktop MCP Server：前端同步时不会携带，始终从内存保留
    const desktopEntry = this.systemMcpConfig.servers.find(s => s.id === 'desktop-tools')
    const external = rawServers.filter(server => server.id !== 'desktop-tools' && !this.isLegacyPaddleMcpConfig(server))
      .map(server => ({ ...server, enabled: true, source: 'USER_MCP' as const }))
    // The bridge capability is process-local. Persist only user-configured external services.
    const saved = saveSecureSystemMcpConfig({
      ...config,
      servers: external
    })
    this.setConfigs([...(saved.servers as McpServerConfig[]), ...(desktopEntry ? [desktopEntry] : [])])
    return this.systemMcpConfig
  }

  public getSanitizedSystemMcpConfig(): RuntimeMcpConfig {
    return sanitizeSystemMcpConfig(this.systemMcpConfig as RuntimeMcpConfig)
  }

  public getBackendConfig(): { servers: McpServerConfig[] } {
    return { servers: this.systemMcpConfig.servers.map(server => server.source === 'DESKTOP_BUILTIN'
      ? { ...server }
      : { ...server, url: `mindpet-mcp:${encodeURIComponent(server.id)}?revision=${this.catalogRevision}`, apiKey: '', hasApiKey: false }) }
  }

  public registerDesktopBridge(config: McpServerConfig): void {
    this.setConfigs([...this.systemMcpConfig.servers.filter(server => server.id !== 'desktop-tools'),
      { ...config, id: 'desktop-tools', source: 'DESKTOP_BUILTIN', enabled: true }])
  }

}

export const mcpManager = McpManager.getInstance()
