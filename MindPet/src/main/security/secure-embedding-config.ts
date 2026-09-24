import { app } from 'electron'
import { join } from 'path'
import { getSecretVault } from './secret-vault'
import { EmbeddingConfigStore, type RuntimeEmbeddingConfig } from './embedding-config-store'

let store: EmbeddingConfigStore | null = null
let storeUserDataPath = ''

function getStore(): EmbeddingConfigStore {
  const userDataPath = app.getPath('userData')
  if (!store || storeUserDataPath !== userDataPath) {
    store = new EmbeddingConfigStore(
      join(userDataPath, 'system_embedding_config.json'),
      getSecretVault()
    )
    storeUserDataPath = userDataPath
  }
  return store
}

export function loadSecureEmbeddingConfig(): RuntimeEmbeddingConfig {
  return getStore().load()
}

export function saveSecureEmbeddingConfig(config: Record<string, unknown>): RuntimeEmbeddingConfig {
  return getStore().save(config)
}

export function sanitizeEmbeddingConfig(config: RuntimeEmbeddingConfig): RuntimeEmbeddingConfig {
  return getStore().toRenderer(config)
}
