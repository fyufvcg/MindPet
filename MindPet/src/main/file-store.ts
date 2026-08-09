/**
 * 轻量 JSON 文件存储 — 替代 sqlite3。
 * 用于会话和消息的本地持久化，不依赖任何原生模块。
 */
import * as fs from 'fs'
import { join } from 'path'
import { app } from 'electron'

function getStoreDir(): string {
  const base = app.getPath('userData')
  const dir = join(base, 'file-store')
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })
  return dir
}

function readJson<T>(file: string, fallback: T): T {
  try {
    if (fs.existsSync(file)) return JSON.parse(fs.readFileSync(file, 'utf-8'))
  } catch { /* ignore */ }
  return fallback
}

function writeJson(file: string, data: any): void {
  fs.writeFileSync(file, JSON.stringify(data, null, 2), 'utf-8')
}

// ==================== Sessions ====================
interface Session {
  id: string
  name: string
  created_at: string
  updated_at: string
  messages?: Message[]
  contextSummary?: string
  pinned?: number
}

interface Message {
  id: number | string
  sessionId: string
  role: string
  text: string
  time: string
  isThinking?: boolean
  isSuperseded?: boolean
  toolSteps?: any[]
  fileInfos?: string
  promptInfo?: string
}

export class FileStore {
  private sessionsFile: string
  private sessions: Session[] = []

  constructor() {
    const dir = getStoreDir()
    this.sessionsFile = join(dir, 'sessions.json')
    this.sessions = readJson<Session[]>(this.sessionsFile, [])
  }

  private save() {
    writeJson(this.sessionsFile, this.sessions)
  }

  // --- Sessions ---
  getSessions(options?: { todayOnly?: boolean }): Session[] {
    let list = [...this.sessions]
    if (options?.todayOnly) {
      const today = new Date().toISOString().slice(0, 10)
      list = list.filter(s => s.created_at?.startsWith(today))
    }
    return list.sort((a, b) => (b.updated_at || '').localeCompare(a.updated_at || ''))
  }

  getSession(id: string): Session | undefined {
    return this.sessions.find(s => s.id === id)
  }

  createSession(session: Session): boolean {
    if (this.sessions.find(s => s.id === session.id)) return false
    this.sessions.push(session)
    this.save()
    return true
  }

  updateSession(id: string, updates: Partial<Session>): boolean {
    const idx = this.sessions.findIndex(s => s.id === id)
    if (idx < 0) return false
    this.sessions[idx] = { ...this.sessions[idx], ...updates, updated_at: new Date().toISOString() }
    this.save()
    return true
  }

  deleteSession(id: string): boolean {
    const len = this.sessions.length
    this.sessions = this.sessions.filter(s => s.id !== id)
    if (this.sessions.length === len) return false
    this.save()
    return true
  }

  // --- Messages ---
  getMessages(sessionId: string): Message[] {
    const session = this.getSession(sessionId)
    return session?.messages || []
  }

  saveMessage(message: Message): boolean {
    const session = this.getSession(message.sessionId)
    if (!session) return false
    if (!session.messages) session.messages = []
    const idx = session.messages.findIndex(m => m.id === message.id)
    if (idx >= 0) {
      session.messages[idx] = { ...message }
    } else {
      session.messages.push(message)
    }
    session.updated_at = new Date().toISOString()
    this.save()
    return true
  }

  deleteMessage(messageId: string): boolean {
    for (const session of this.sessions) {
      if (!session.messages) continue
      const idx = session.messages.findIndex(m => String(m.id) === messageId)
      if (idx >= 0) {
        session.messages.splice(idx, 1)
        session.updated_at = new Date().toISOString()
        this.save()
        return true
      }
    }
    return false
  }

  saveMessages(messages: Message[]): boolean {
    for (const msg of messages) {
      this.saveMessage(msg)
    }
    return true
  }

  // --- WeChat sessions ---
  ensureWechatSession(sessionId: string, nickname: string): boolean {
    const existing = this.getSession(sessionId)
    if (existing) {
      this.updateSession(sessionId, { name: nickname })
      return true
    }
    return this.createSession({
      id: sessionId,
      name: nickname || sessionId,
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
      messages: []
    })
  }
}

// Singleton
let _store: FileStore | null = null
export function getStore(): FileStore {
  if (!_store) _store = new FileStore()
  return _store
}
