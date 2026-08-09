export type RemoteSessionChannel = 'wechat' | 'qq'

export function getRemoteSessionChannel(sessionId: string): RemoteSessionChannel | null {
  if (sessionId.startsWith('wechat:')) return 'wechat'
  if (sessionId.startsWith('qq:')) return 'qq'
  return null
}

export function isRemoteSessionId(sessionId: string): boolean {
  return getRemoteSessionChannel(sessionId) !== null
}
