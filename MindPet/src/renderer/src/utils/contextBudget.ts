/* eslint-disable @typescript-eslint/no-explicit-any */

/** Fast UTF-8 based estimate for mixed Chinese, English, JSON, and code. */
export function estimateTextTokens(value: unknown): number {
  if (value == null) return 0
  const text = typeof value === 'string' ? value : JSON.stringify(value)
  if (!text) return 0
  try {
    return Math.ceil(new TextEncoder().encode(text).length / 4)
  } catch {
    return Math.ceil(text.length / 3)
  }
}

export function estimateDraftTokens(inputValue: string, attachedFiles: any[]): number {
  const attachmentPayload = (attachedFiles || []).map(file => ({
    name: file?.name || '',
    path: file?.path || '',
    content: file?.content || ''
  }))
  return estimateTextTokens(inputValue) + estimateTextTokens(attachmentPayload)
}
