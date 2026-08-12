import { join } from 'path'
import { McpConfigStore, type RuntimeMcpConfig } from './mcp-config-store'
import { getSecretVault } from './secret-vault'
import { getDefaultDataDir } from '../storage-path'

let store: McpConfigStore | null = null
let storeDataDir = ''

function getStore(): McpConfigStore {
  const dataDir = getDefaultDataDir()
  if (!store || storeDataDir !== dataDir) {
    store = new McpConfigStore(join(dataDir, 'system_mcp_config.json'), getSecretVault())
    storeDataDir = dataDir
  }
  return store
}

export function loadSecureSystemMcpConfig(): RuntimeMcpConfig {
  return getStore().load()
}

export function saveSecureSystemMcpConfig(config: Record<string, unknown>): RuntimeMcpConfig {
  return getStore().save(config)
}

export function sanitizeSystemMcpConfig(config: RuntimeMcpConfig): RuntimeMcpConfig {
  return getStore().toRenderer(config)
}
