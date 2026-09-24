import { existsSync, readFileSync } from 'fs'
import { SecretVault, writeTextAtomically } from './secret-vault-core'

/**
 * 豆包 Embedding API Key 的加密存储。
 *
 * 复用与系统 LLM Key 完全相同的机制：明文只进 OS 加密的 secrets.v1.json，
 * 配置文件里仅保留 apiKeyRef 引用。
 */
export const EMBEDDING_API_KEY_SECRET_ID = 'system-embedding-api-key'
export const EMBEDDING_API_KEY_REF = `secret://${EMBEDDING_API_KEY_SECRET_ID}`

/** AUTO: 优先 Ollama 自动降级；OLLAMA / DOUBAO: 强制指定 */
export type EmbeddingMode = 'AUTO' | 'OLLAMA' | 'DOUBAO'

export interface RuntimeEmbeddingConfig {
  mode: EmbeddingMode
  /** 仅在主进程内部流转，绝不发给渲染进程 */
  apiKey: string
  endpoint: string
  model: string
  hasApiKey: boolean
  secretMigrationPending?: boolean
  [key: string]: unknown
}

export const DEFAULT_EMBEDDING_CONFIG: RuntimeEmbeddingConfig = {
  mode: 'AUTO',
  apiKey: '',
  endpoint: 'https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal',
  model: 'doubao-embedding-vision-251215',
  hasApiKey: false
}

type JsonObject = Record<string, unknown>

function isJsonObject(value: unknown): value is JsonObject {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function withoutRuntimeSecrets(config: JsonObject): JsonObject {
  const sanitized = { ...config }
  delete sanitized.apiKey
  delete sanitized.hasApiKey
  delete sanitized.secretMigrationPending
  delete sanitized.clearApiKey
  return sanitized
}

function normalizeMode(value: unknown): EmbeddingMode {
  const m = typeof value === 'string' ? value.trim().toUpperCase() : ''
  return m === 'OLLAMA' || m === 'DOUBAO' ? m : 'AUTO'
}

export class EmbeddingConfigStore {
  constructor(
    private readonly configPath: string,
    private readonly vault: SecretVault
  ) {}

  load(): RuntimeEmbeddingConfig {
    const stored = this.readStoredConfig()
    const merged = { ...DEFAULT_EMBEDDING_CONFIG, ...stored } as JsonObject
    const legacyApiKey = typeof stored.apiKey === 'string' ? stored.apiKey : ''
    let apiKey = ''
    let migrationPending = false

    if (legacyApiKey) {
      try {
        this.vault.setSecret(EMBEDDING_API_KEY_SECRET_ID, legacyApiKey, 'Embedding API key')
        this.writeStoredConfig({
          ...withoutRuntimeSecrets(merged),
          apiKeyRef: EMBEDDING_API_KEY_REF
        })
        apiKey = legacyApiKey
      } catch {
        // 保留旧文件并让 Key 在内存中可用，下次启动重试迁移
        apiKey = legacyApiKey
        migrationPending = true
      }
    } else if (stored.apiKeyRef === EMBEDDING_API_KEY_REF) {
      try {
        apiKey = this.vault.getSecret(EMBEDDING_API_KEY_SECRET_ID) ?? ''
      } catch {
        migrationPending = true
      }
    }

    return {
      ...merged,
      mode: normalizeMode(merged.mode),
      apiKey,
      hasApiKey: apiKey.length > 0,
      ...(migrationPending ? { secretMigrationPending: true } : {})
    } as RuntimeEmbeddingConfig
  }

  save(input: JsonObject): RuntimeEmbeddingConfig {
    if (!isJsonObject(input)) throw new TypeError('Embedding configuration must be an object')

    const stored = this.readStoredConfig()
    const currentWithoutSecrets = withoutRuntimeSecrets(stored)
    const incomingWithoutSecrets = withoutRuntimeSecrets(input)
    const shouldClear = input.clearApiKey === true
    const incomingApiKey =
      typeof input.apiKey === 'string' && input.apiKey.length > 0 ? input.apiKey : ''
    const legacyApiKey =
      typeof stored.apiKey === 'string' && stored.apiKey.length > 0 ? stored.apiKey : ''
    let apiKeyRef =
      stored.apiKeyRef === EMBEDDING_API_KEY_REF ? EMBEDDING_API_KEY_REF : undefined

    if (shouldClear) {
      this.vault.deleteSecret(EMBEDDING_API_KEY_SECRET_ID)
      apiKeyRef = undefined
    } else {
      const keyToPersist = incomingApiKey || legacyApiKey
      if (keyToPersist) {
        this.vault.setSecret(EMBEDDING_API_KEY_SECRET_ID, keyToPersist, 'Embedding API key')
        apiKeyRef = EMBEDDING_API_KEY_REF
      }
    }

    const nextStored: JsonObject = {
      ...withoutRuntimeSecrets(DEFAULT_EMBEDDING_CONFIG),
      ...currentWithoutSecrets,
      ...incomingWithoutSecrets
    }
    nextStored.mode = normalizeMode(nextStored.mode)
    delete nextStored.apiKeyRef
    if (apiKeyRef) nextStored.apiKeyRef = apiKeyRef
    this.writeStoredConfig(nextStored)
    return this.load()
  }

  /** 交给渲染进程的安全副本：不含 Key，只含 hasApiKey */
  toRenderer(config: RuntimeEmbeddingConfig): RuntimeEmbeddingConfig {
    const sanitized = withoutRuntimeSecrets(config)
    delete sanitized.apiKeyRef
    return {
      ...sanitized,
      mode: normalizeMode(sanitized.mode),
      apiKey: '',
      hasApiKey: config.apiKey.length > 0
    } as RuntimeEmbeddingConfig
  }

  private readStoredConfig(): JsonObject {
    if (!existsSync(this.configPath)) return {}
    let parsed: unknown
    try {
      parsed = JSON.parse(readFileSync(this.configPath, 'utf8'))
    } catch {
      throw new Error('Embedding configuration is not valid JSON')
    }
    if (!isJsonObject(parsed)) throw new Error('Embedding configuration must be a JSON object')
    return parsed
  }

  private writeStoredConfig(config: JsonObject): void {
    writeTextAtomically(this.configPath, `${JSON.stringify(config, null, 2)}\n`)
  }
}
