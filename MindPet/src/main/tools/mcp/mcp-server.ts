/**
 * MCP Server — 将前端 ToolRegistry 以 MCP JSON-RPC 协议暴露给 Java 后端。
 *
 * 后端 McpManager 连接到 http://127.0.0.1:{port} 后自动发现全部前端工具，
 * 注册为 Spring AI ToolCallback，LLM 即可直接调用。
 *
 * 工具命名格式: desktop__{category}__{toolName}
 *   例: desktop__computer__screenshot
 * 后端 AiService 按 category 段做分组路由过滤。
 */

import http from 'http'
import { webContents } from 'electron'
import * as fs from 'fs/promises'
import { basename, extname } from 'path'
import { toolRegistry } from '../core/tool-registry'
import { unifiedToolExecutor } from '../core/tool-executor'
import type { ToolContext, ToolApi } from '../core/types'
import { backendBaseUrl } from '../../backend-endpoint'
import { getSessionFilesDir, resolveSessionPath } from '../utils/paths'
import { permissionManager } from '../security/permission-manager'

export const MCP_SERVER_PORT = 9339
export const MCP_SERVER_ID = 'desktop-tools'
const SERVER_ID = 'desktop'
const taskContexts = new Map<string, { workspacePath?: string; senderId?: number }>()
const activeTools = new Map<string, Set<AbortController>>()

function taskKey(sessionId: string, messageId: string): string { return JSON.stringify([sessionId, messageId]) }

export function registerDesktopTaskContext(sessionId: string, messageId: string, context: { workspacePath?: string; senderId?: number }): () => void {
  const key = taskKey(sessionId, messageId)
  taskContexts.set(key, context)
  return () => {
    if (taskContexts.get(key) === context) taskContexts.delete(key)
    permissionManager.clearTurnApproval(`${sessionId}::${messageId}`)
  }
}

export function cancelDesktopTask(sessionId: string, messageId: string): void {
  for (const controller of activeTools.get(taskKey(sessionId, messageId)) || []) controller.abort()
}

// ── JSON-RPC types ──────────────────────────────────────────────

interface JsonRpcRequest {
  jsonrpc: '2.0'
  method: string
  params?: Record<string, unknown>
  id: number | string
}

interface JsonRpcResponse {
  jsonrpc: '2.0'
  id: number | string
  result?: unknown
  error?: { code: number; message: string }
}

// ── helpers ─────────────────────────────────────────────────────

function json(res: http.ServerResponse, status: number, body: unknown): void {
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': backendBaseUrl(),
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization, Mcp-Session-Id',
  })
  res.end(JSON.stringify(body))
}

function ok(id: number | string, result: unknown): JsonRpcResponse {
  return { jsonrpc: '2.0', id, result }
}

function err(id: number | string, code: number, message: string): JsonRpcResponse {
  return { jsonrpc: '2.0', id, error: { code, message } }
}

function readBody(req: http.IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    let data = ''
    req.on('data', (chunk: Buffer) => (data += chunk.toString()))
    req.on('end', () => resolve(data))
    req.on('error', reject)
  })
}

// ── MCP tool schema 转换 ────────────────────────────────────────

/** 将 ToolApi parameters 转为 MCP inputSchema (JSON Schema) */
function toInputSchema(api: ToolApi): Record<string, unknown> {
  // 如果 parameters 已经是 JSON Schema 格式就直接用
  const params = api.parameters
  if (params && params.type === 'object') {
    return {
      type: 'object',
      properties: params.properties || {},
      required: params.required || [],
    }
  }
  // 否则构造最小 schema
  return {
    type: 'object',
    properties: params || {},
    required: [] as string[],
  }
}

// ── request handler ─────────────────────────────────────────────

async function handleRequest(
  req: http.IncomingMessage,
  res: http.ServerResponse
): Promise<void> {
  // CORS preflight
  if (req.method === 'OPTIONS') {
    json(res, 204, '')
    return
  }

  if (req.method !== 'POST') {
    json(res, 405, err('', -32600, 'Method Not Allowed'))
    return
  }

  let raw: string
  try {
    raw = await readBody(req)
  } catch {
    json(res, 400, err('', -32700, 'Parse error'))
    return
  }

  let rpc: JsonRpcRequest
  try {
    rpc = JSON.parse(raw)
  } catch {
    json(res, 400, err('', -32700, 'Parse error'))
    return
  }

  const { method, params, id } = rpc
  console.log(`[MCP-Server] ← ${method}`)

  try {
    switch (method) {
      // ── initialize ──────────────────────────────────────
      case 'initialize': {
        console.log('[MCP-Server] → initialized (protocol 2025-03-26)')
        json(
          res,
          200,
          ok(id, {
            protocolVersion: '2025-03-26',
            serverInfo: { name: 'mindpet-desktop', version: '1.0.0' },
            capabilities: { tools: {} },
          })
        )
        break
      }

      // ── notifications/initialized ───────────────────────
      case 'notifications/initialized': {
        json(res, 200, ok(id, {}))
        break
      }

      // ── tools/list ──────────────────────────────────────
      case 'tools/list': {
        const allTools = toolRegistry.getAllToolsInfo()
        const tools = Object.values(allTools)
          .filter((t: any) => !t.hidden)
          .map((t: any) => ({
            name: `${SERVER_ID}__${t.category}__${t.name}`,
            description: t.description || '',
            inputSchema: t.parameters
              ? toInputSchema({ name: t.name, description: t.description, parameters: t.parameters } as ToolApi)
              : { type: 'object', properties: {}, required: [] },
          }))

        console.log(`[MCP-Server] → tools/list: ${tools.length} tools`)
        json(res, 200, ok(id, { tools }))
        break
      }

      // ── tools/call ──────────────────────────────────────
      case 'tools/call': {
        const toolName = (params as any)?.name as string
        const args = ((params as any)?.arguments as Record<string, any>) || {}

        if (!toolName) {
          json(res, 400, err(id, -32602, 'Missing tool name'))
          return
        }

        // 解析 desktop__{category}__{toolName} → 真实的工具名
        const realName = toolName.startsWith(`${SERVER_ID}__`)
          ? toolName.replace(`${SERVER_ID}__`, '').replace(/^[^_]+__/, '')
          : toolName

        const executor = toolRegistry.getExecutor(realName)
        if (!executor) {
          json(
            res,
            200,
            ok(id, {
              content: [{ type: 'text', text: `未知工具: ${realName}` }],
              isError: true,
            })
          )
          return
        }

        const metadata = (params as any)?._meta?.['mindpet/context'] || {}
        const sessionId = typeof metadata.sessionId === 'string' ? metadata.sessionId : undefined
        const messageId = String(metadata.messageId || '')
        const key = taskKey(sessionId || '', messageId)
        const local = taskContexts.get(key)
        const sender = local?.senderId ? webContents.fromId(local.senderId) : undefined
        const controller = new AbortController()
        const running = activeTools.get(key) || new Set<AbortController>()
        activeTools.set(key, running)
        running.add(controller)
        const onClose = (): void => { if (!res.writableEnded) controller.abort() }
        res.on('close', onClose)
        const remaining = Number(metadata.deadline) - Date.now()
        if (Number.isFinite(remaining) && remaining <= 0) controller.abort()
        const timer = remaining > 0 ? setTimeout(() => controller.abort(), Math.min(remaining, 2147483647)) : undefined
        const context: ToolContext = {
          workspacePath: local?.workspacePath || getSessionFilesDir(sessionId),
          sessionId,
          messageId,
          isFrontend: false,
          sandboxMode: false,
          abortSignal: controller.signal,
          ...(sender && !sender.isDestroyed() ? { event: { sender } as Electron.IpcMainInvokeEvent } : {})
        }

        console.log(`[MCP-Server] tools/call: ${realName}`)

        try {
          const result = await unifiedToolExecutor.execute(realName, args, context)
          let resultState = result.state
          if (!resultState && result.content.trim().startsWith('{')) {
            try { resultState = JSON.parse(result.content) } catch { /* Plain text remains valid output. */ }
          }
          const producesFile = ['generate_file', 'modify_docx', 'modify_excel'].includes(realName)
            || (realName === 'run_office_skill' && ['create', 'modify', 'convert'].includes(String(args.action)))
          if (result.success && producesFile && typeof resultState?.file_path === 'string') {
            try {
              const stat = await fs.stat(resultState.file_path)
              if (!stat.isFile() || stat.size === 0) throw new Error('输出文件为空或不是文件')
              resultState = { ...resultState, artifact_verification: { exists: true, sizeBytes: stat.size } }
              if (sender && !sender.isDestroyed()) sender.send('api:llm-tool-event', {
                type: 'generated_files', sessionId, messageId,
                files: [{ path: resultState.file_path, name: basename(resultState.file_path), url: `local-file:///${resultState.file_path.replace(/\\/g, '/')}` }]
              })
            } catch (error: any) {
              result.success = false
              result.content += `\n产物验证失败：${error.message}`
            }
          }
          if (Array.isArray(resultState?.sources) && sender && !sender.isDestroyed()) sender.send('api:llm-tool-event', {
            type: 'web_sources', sessionId, messageId, sources: resultState.sources
          })
          const content: Record<string, unknown>[] = [{ type: 'text', text: result.content }]
          const imagePaths = [
            ...(Array.isArray(resultState?.imagePaths) ? resultState.imagePaths : []),
            ...(realName === 'screenshot' && resultState?.filePath ? [resultState.filePath] : []),
            ...(realName === 'read_file' && /\.(png|jpe?g|webp|gif)$/i.test(String(args.file_path || '')) ? [resolveSessionPath(args.file_path, sessionId)] : [])
          ]
          let imageBytes = 0
          for (const imagePath of imagePaths.slice(0, 4)) {
            try {
              const stat = await fs.stat(imagePath)
              if (stat.size > 8_000_000 || imageBytes + stat.size > 16_000_000) continue
              const extension = extname(imagePath).toLowerCase()
              const mimeType = ({ '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.gif': 'image/gif' } as Record<string, string>)[extension]
              if (!mimeType) continue
              const bytes = await fs.readFile(imagePath)
              imageBytes += bytes.length
              content.push({ type: 'image', mimeType, data: bytes.toString('base64') })
            } catch { /* A failed preview must not replay a completed operation. */ }
          }

          // MCP 协议要求返回 content 数组
          json(
            res,
            200,
            ok(id, {
              content,
              isError: !result.success,
              structuredContent: { success: result.success, content: result.content, state: resultState },
              ...(result.error && { meta: { error: result.error.message } }),
            })
          )
        } finally {
          if (timer) clearTimeout(timer)
          res.removeListener('close', onClose)
          running.delete(controller)
          if (running.size === 0) activeTools.delete(key)
        }
        break
      }

      case 'notifications/cancelled': {
        const metadata = (params as any)?.['mindpet/context'] || {}
        cancelDesktopTask(String(metadata.sessionId || ''), String(metadata.messageId || ''))
        json(res, 200, ok(id, {}))
        break
      }

      case 'mindpet/task-finished': {
        const sessionId = String((params as any)?.sessionId || '')
        const messageId = String((params as any)?.messageId || '')
        permissionManager.clearTurnApproval(messageId ? `${sessionId}::${messageId}` : sessionId)
        json(res, 200, ok(id, {}))
        break
      }

      // ── ping ────────────────────────────────────────────
      case 'ping': {
        json(res, 200, ok(id, {}))
        break
      }

      default: {
        json(res, 200, err(id, -32601, `Method not found: ${method}`))
      }
    }
  } catch (e: any) {
    console.error(`[MCP-Server] ERROR in ${method}:`, e.message)
    json(res, 500, err(id, -32603, e.message || 'Internal error'))
  }
}

// ── server lifecycle ────────────────────────────────────────────

let server: http.Server | null = null

export function startMcpServer(port = MCP_SERVER_PORT): void {
  if (server) return

  server = http.createServer(handleRequest)
  server.listen(port, '127.0.0.1', () => {
    console.log(`[MCP-Server] 已启动 → http://127.0.0.1:${port}`)
    console.log(`[MCP-Server] 工具数量: ${Object.keys(toolRegistry.getAllToolsInfo()).length}`)
  })

  server.on('error', (e: NodeJS.ErrnoException) => {
    if (e.code === 'EADDRINUSE') {
      console.warn(`[MCP-Server] 端口 ${port} 已被占用，可能已有实例在运行`)
    } else {
      console.error(`[MCP-Server] 启动失败:`, e.message)
    }
  })
}

export function stopMcpServer(): void {
  if (server) {
    server.close()
    server = null
    console.log('[MCP-Server] 已停止')
  }
}
