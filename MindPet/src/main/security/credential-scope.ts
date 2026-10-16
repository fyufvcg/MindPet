/** A saved credential belongs to one provider and one service address. */
export function serviceAddress(value: unknown): string {
  const text = typeof value === 'string' ? value.trim() : ''
  try {
    const url = new URL(text)
    url.hash = ''
    return url.toString().replace(/\/+$/, '')
  } catch { return text.replace(/\/+$/, '') }
}

export function sameLlmCredentialScope(left: Record<string, unknown>, right: Record<string, unknown>): boolean {
  return left.provider === right.provider && serviceAddress(left.baseUrl) === serviceAddress(right.baseUrl)
}

export function validateServiceAddress(value: unknown): void {
  if (typeof value !== 'string' || !value.trim()) return
  let url: URL
  try { url = new URL(value.trim()) } catch { throw new Error('请输入完整的 HTTP 或 HTTPS 服务地址') }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
    throw new Error('服务地址仅支持 HTTP/HTTPS，请将凭证填写在 API Key 输入框中')
  }
}
