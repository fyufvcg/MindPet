import type { RuntimeLlmConfig } from './security/llm-config-store'
import type { RuntimeEmbeddingConfig } from './security/embedding-config-store'

type Kind = 'llm' | 'embedding'
export interface BackendConfigSyncState {
  state: 'pending' | 'applied'
  message: string
}
interface Dependencies {
  url: (path: string) => string
  llm: () => RuntimeLlmConfig
  embedding: () => RuntimeEmbeddingConfig
  systemPrompt: (config: RuntimeLlmConfig) => string
  fetch?: typeof fetch
}

/** Owns ordering, deadlines and retry semantics for both model configurations.
 * Saves persist in Electron first; this channel only applies the latest snapshot.
 */
export class ModelConfigSync {
  private tails: Record<Kind, Promise<unknown>> = { llm: Promise.resolve(), embedding: Promise.resolve() }
  private states: Record<Kind, BackendConfigSyncState> = {
    llm: { state: 'pending', message: '等待本地服务同步' },
    embedding: { state: 'pending', message: '等待本地服务同步' }
  }
  private revisions: Record<Kind, number> = { llm: 0, embedding: 0 }
  constructor(private readonly deps: Dependencies) {}

  state(kind: Kind): BackendConfigSyncState { return { ...this.states[kind] } }

  apply(kind: Kind, verify = false): Promise<Record<string, any>> {
    const revision = ++this.revisions[kind]
    this.states[kind] = { state: 'pending', message: '已保存在本机，正在同步至本地服务' }
    const operation = this.tails[kind].then(async () => {
      try {
        const config = kind === 'llm' ? this.deps.llm() : this.deps.embedding()
        const body = kind === 'llm'
          ? { apiKey: config.apiKey || (config.provider === 'ollama' ? 'ollama' : ''), baseUrl: config.baseUrl, model: config.model,
              systemPrompt: this.deps.systemPrompt(config as RuntimeLlmConfig) }
          : { mode: config.mode, apiKey: config.apiKey, endpoint: config.endpoint,
              model: config.model, verify }
        const response = await (this.deps.fetch ?? fetch)(this.deps.url(`/api/desktop/${kind}-config`), {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body), signal: AbortSignal.timeout(35_000)
        })
        if (!response.ok) throw new Error(`本地服务返回 HTTP ${response.status}`)
        const data = await response.json()
        if (data?.status !== 'ok') throw new Error('本地服务未接受配置')
        if (revision === this.revisions[kind]) this.states[kind] = { state: 'applied', message: '已保存并同步至本地服务' }
        return data
      } catch {
        // Do not forward upstream response bodies or credentials to renderer/logs.
        const message = '已保存在本机，等待本地服务同步；请检查后端连接后重试'
        if (revision === this.revisions[kind]) this.states[kind] = { state: 'pending', message }
        return { status: 'error', unreachable: true, message }
      }
    })
    this.tails[kind] = operation.catch(() => {})
    return operation
  }

  async retryPending(): Promise<void> {
    await Promise.all((['llm', 'embedding'] as const)
      .filter(kind => this.states[kind].state === 'pending').map(kind => this.apply(kind)))
  }
}
