import { existsSync, readFileSync, unlinkSync } from 'fs'
import { SecretVault, writeTextAtomically } from './secret-vault-core'

export const QQ_APP_SECRET_ID = 'qq-bot-app-secret'
const QQ_APP_SECRET_REF = `secret://${QQ_APP_SECRET_ID}`

interface QQCredentialMetadata {
  version: 1
  appId: string
  appSecretRef: string
  userOpenid?: string
  enabled: boolean
}

export interface RuntimeQQCredentials {
  appId: string
  appSecret: string
  userOpenid?: string
  enabled: boolean
}

export interface PublicQQCredentialState {
  appId: string
  hasCredentials: boolean
  enabled: boolean
}

export class QQSecureStore {
  constructor(
    private readonly metadataPath: string,
    private readonly vault: SecretVault
  ) {}

  load(): RuntimeQQCredentials | null {
    if (!existsSync(this.metadataPath)) return null

    let metadata: QQCredentialMetadata
    try {
      metadata = JSON.parse(readFileSync(this.metadataPath, 'utf8')) as QQCredentialMetadata
    } catch {
      throw new Error('QQ Bot credential metadata is not valid JSON')
    }

    if (!metadata.appId || metadata.appSecretRef !== QQ_APP_SECRET_REF) return null
    const appSecret = this.vault.getSecret(QQ_APP_SECRET_ID) ?? ''
    if (!appSecret) return null

    return {
      appId: metadata.appId,
      appSecret,
      userOpenid: metadata.userOpenid,
      enabled: metadata.enabled !== false
    }
  }

  getPublicState(): PublicQQCredentialState {
    try {
      const credentials = this.load()
      return {
        appId: credentials?.appId ?? '',
        hasCredentials: Boolean(credentials),
        enabled: credentials?.enabled ?? false
      }
    } catch {
      return { appId: '', hasCredentials: false, enabled: false }
    }
  }

  save(input: { appId: string; appSecret: string; userOpenid?: string; enabled?: boolean }): void {
    const appId = input.appId.trim()
    const appSecret = input.appSecret.trim()
    if (!appId || !appSecret) throw new TypeError('QQ Bot AppID and AppSecret are required')

    this.vault.setSecret(QQ_APP_SECRET_ID, appSecret, 'QQ Bot AppSecret')
    this.writeMetadata({
      version: 1,
      appId,
      appSecretRef: QQ_APP_SECRET_REF,
      userOpenid: input.userOpenid,
      enabled: input.enabled !== false
    })
  }

  setEnabled(enabled: boolean): void {
    const credentials = this.load()
    if (!credentials) return
    this.writeMetadata({
      version: 1,
      appId: credentials.appId,
      appSecretRef: QQ_APP_SECRET_REF,
      userOpenid: credentials.userOpenid,
      enabled
    })
  }

  clear(): void {
    this.vault.deleteSecret(QQ_APP_SECRET_ID)
    if (existsSync(this.metadataPath)) unlinkSync(this.metadataPath)
  }

  private writeMetadata(metadata: QQCredentialMetadata): void {
    writeTextAtomically(this.metadataPath, `${JSON.stringify(metadata, null, 2)}\n`)
  }
}
