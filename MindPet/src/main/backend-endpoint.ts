/**
 * 后端地址解析 — 单一事实来源。
 *
 * MindPet 支持两种部署形态，同一个客户端安装包要能同时服务它们：
 *   1) 本地部署：后端跑在用户自己机器上（docker compose），地址 http://127.0.0.1:8080
 *   2) 云端部署：后端跑在远程服务器上，地址由用户/管理员提供
 *
 * 因此地址必须在**运行时**解析，而不是编译期写死。
 *
 * 优先级（高 → 低）：
 *   1. 命令行参数  --backend=https://example.com
 *   2. 环境变量    MINDPET_SERVER_URL / XIAOQING_API_URL（兼容旧变量名）
 *   3. 配置文件    <userData>/server.json 或 <exe 同级>/server.json 中的 backendUrl
 *   4. 内置默认    http://127.0.0.1:8080
 *
 * 后 3 级都指向同一个持久化文件，设置页改地址即写 server.json。
 */

import { app } from 'electron'
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'fs'
import { dirname, join } from 'path'

/** 本地部署默认地址 */
export const DEFAULT_BACKEND_BASE_URL = 'http://127.0.0.1:8080'

/** 持久化文件名（放在便携模式的 data/ 目录下，与 exe 同级） */
const ENDPOINT_FILE_NAME = 'server.json'

let cachedBaseUrl: string | null = null

/** 规整用户输入：补协议、去尾部斜杠、去空白 */
export function normalizeBackendBaseUrl(raw: string): string {
  let url = String(raw ?? '').trim()
  if (!url) return DEFAULT_BACKEND_BASE_URL
  if (!/^https?:\/\//i.test(url)) url = 'http://' + url
  return url.replace(/\/+$/, '')
}

/** server.json 的候选位置：优先 userData，其次 exe 同级（便携/绿色分发） */
function endpointFileCandidates(): string[] {
  const list: string[] = []
  try {
    list.push(join(app.getPath('userData'), ENDPOINT_FILE_NAME))
  } catch {
    /* app 尚未就绪时忽略 */
  }
  try {
    list.push(join(dirname(app.getPath('exe')), ENDPOINT_FILE_NAME))
  } catch {
    /* ignore */
  }
  return list
}

function readEndpointFile(): { url: string | null; file: string | null } {
  for (const file of endpointFileCandidates()) {
    try {
      if (!existsSync(file)) continue
      const json = JSON.parse(readFileSync(file, 'utf-8'))
      const raw = json?.backendUrl ?? json?.server ?? json?.apiUrl
      if (typeof raw === 'string' && raw.trim()) return { url: normalizeBackendBaseUrl(raw), file }
    } catch (e) {
      console.warn('[Endpoint] 读取 server.json 失败:', file, e)
    }
  }
  return { url: null, file: null }
}

function readEndpointFromArgv(): string | null {
  const prefixed = process.argv.find((a) => a.startsWith('--backend='))
  if (prefixed) return prefixed.slice('--backend='.length)
  const idx = process.argv.indexOf('--backend')
  if (idx >= 0 && process.argv[idx + 1]) return process.argv[idx + 1]
  return null
}

/**
 * 当前生效的后端根地址（无尾部斜杠）。
 * 结果会被缓存；设置页保存时通过 setBackendBaseUrl 刷新。
 */
export function backendBaseUrl(): string {
  if (cachedBaseUrl) return cachedBaseUrl

  const fromArgv = readEndpointFromArgv()
  const fromEnv = process.env.MINDPET_SERVER_URL || process.env.XIAOQING_API_URL || null
  const fromFile = readEndpointFile().url

  const resolved = normalizeBackendBaseUrl(fromArgv || fromEnv || fromFile || DEFAULT_BACKEND_BASE_URL)
  cachedBaseUrl = resolved
  console.log(
    '[Endpoint] 后端地址 =',
    resolved,
    '| 来源:',
    fromArgv ? '命令行' : fromEnv ? '环境变量' : fromFile ? 'server.json' : '内置默认'
  )
  return resolved
}

/** 拼接后端 API 路径：backendUrl('/api/desktop/health') */
export function backendUrl(path: string): string {
  const base = backendBaseUrl().replace(/\/+$/, '')
  if (!path) return base
  return base + (path.startsWith('/') ? path : '/' + path)
}

/** server.json 的写入路径（首次写入时创建目录） */
export function endpointFilePath(): string {
  for (const file of endpointFileCandidates()) {
    if (existsSync(file)) return file
  }
  try {
    const primary = join(app.getPath('userData'), ENDPOINT_FILE_NAME)
    mkdirSync(dirname(primary), { recursive: true })
    return primary
  } catch {
    return join(dirname(app.getPath('exe')), ENDPOINT_FILE_NAME)
  }
}

/**
 * 保存后端地址并立即生效。
 * 传空字符串 = 恢复默认（写回内置默认值，便于用户一键回退到本机部署）。
 */
export function setBackendBaseUrl(raw: string): { url: string; file: string } {
  const url = raw && raw.trim() ? normalizeBackendBaseUrl(raw) : DEFAULT_BACKEND_BASE_URL
  const file = endpointFilePath()
  try {
    let merged: Record<string, unknown> = {}
    if (existsSync(file)) {
      try {
        merged = JSON.parse(readFileSync(file, 'utf-8')) || {}
      } catch {
        merged = {}
      }
    }
    merged.backendUrl = url
    mkdirSync(dirname(file), { recursive: true })
    writeFileSync(file, JSON.stringify(merged, null, 2), 'utf-8')
  } catch (e) {
    console.error('[Endpoint] 写入 server.json 失败:', e)
    // 写盘失败也要让本次运行生效，避免用户以为保存成功了却还在打旧地址
  }
  cachedBaseUrl = url
  console.log('[Endpoint] 后端地址已更新 =', url)
  return { url, file }
}

/** 仅清除缓存（测试用） */
export function resetBackendBaseUrlCache(): void {
  cachedBaseUrl = null
}

export interface BackendProbeResult {
  ok: boolean
  /** 实际探测的地址（可能因自动降级而与入参不同） */
  url: string
  httpStatus?: number
  elapsedMs: number
  service?: string
  version?: string
  /** 后端可达但业务链路有问题时的说明 */
  detail?: string
  /** 连不上时的错误 */
  error?: string
}

/**
 * 探测某个地址是否为可用的 MindPet 后端。
 * 用 /api/desktop/health，它同时返回服务标识，能区分「后端在跑」和「端口被别的程序占了」。
 */
export async function probeBackend(rawUrl: string, timeoutMs = 5000): Promise<BackendProbeResult> {
  const startedAt = Date.now()
  const url = normalizeBackendBaseUrl(rawUrl)
  try {
    const res = await fetch(`${url}/api/desktop/health`, {
      method: 'GET',
      signal: AbortSignal.timeout(timeoutMs)
    })
    const elapsedMs = Date.now() - startedAt
    const text = await res.text().catch(() => '')
    let payload: any = null
    try {
      payload = JSON.parse(text)
    } catch {
      /* 可能返回纯文本 */
    }
    const service = typeof payload?.service === 'string' ? payload.service : undefined
    const looksLikeBackend = /mindpet-desktop-api/i.test(text)

    if (!res.ok) {
      return { ok: false, url, httpStatus: res.status, elapsedMs, service, error: `HTTP ${res.status}` }
    }
    if (!looksLikeBackend) {
      return {
        ok: false,
        url,
        httpStatus: res.status,
        elapsedMs,
        service,
        detail: '该地址可访问，但不像 MindPet 后端（health 响应缺少服务标识）',
        error: '服务标识不匹配'
      }
    }
    return {
      ok: true,
      url,
      httpStatus: res.status,
      elapsedMs,
      service,
      version: typeof payload?.version === 'string' ? payload.version : undefined
    }
  } catch (e: any) {
    return {
      ok: false,
      url,
      elapsedMs: Date.now() - startedAt,
      error: e?.name === 'TimeoutError' || /abort/i.test(String(e?.message))
        ? `连接超时（${timeoutMs}ms）`
        : e?.message || String(e)
    }
  }
}
