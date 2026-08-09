/* eslint-disable @typescript-eslint/no-explicit-any */

const MESSAGE_OVERHEAD_TOKENS = 4
const DEFAULT_PROMPT_ENVELOPE_TOKENS = 1500

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

function getMessageBody(message: any): string {
  const fileContent = message.fileInfo?.content || message.fileInfos?.map((file: any) => file.content || '').join('\n') || ''
  return `${message.text || ''}\n${fileContent}`
}

function getActiveToolTrace(message: any): any[] {
  if (!message?.isThinking || !Array.isArray(message.toolSteps)) return []
  return message.toolSteps.filter((step: any) => step?.type === 'call' || step?.type === 'result' || step?.type === 'compaction')
}

export function getContextMessageSignature(message: any): string {
  return [message.sender || '', getMessageBody(message), JSON.stringify(getActiveToolTrace(message))].join('\u0000')
}

export function estimateContextMessageTokens(message: any): number {
  if (message.sender !== 'user' && message.sender !== 'agent') return 0
  const toolTraceTokens = getActiveToolTrace(message).reduce((total: number, step: any) => {
    if ((step?.type === 'result' || step?.type === 'compaction') && Number.isFinite(Number(step.contextTokens))) {
      return total + Math.max(0, Number(step.contextTokens))
    }
    return total + estimateTextTokens(step)
  }, 0)
  return Math.max(
    1,
    estimateTextTokens(getMessageBody(message)) + toolTraceTokens + MESSAGE_OVERHEAD_TOKENS
  )
}

export function getPromptEnvelopeSignature(session: any): string {
  return JSON.stringify({
    contextSummary: session?.contextSummary || ''
  })
}

export function estimatePromptEnvelopeTokens(session: any): number {
  return DEFAULT_PROMPT_ENVELOPE_TOKENS + estimateTextTokens(session?.contextSummary || '')
}

export function selectContextMessages(session: any, contextRounds: number): any[] {
  const limit = Math.max(1, Number(contextRounds) || 10) * 2
  return (session?.messages || [])
    .filter((message: any) => {
      if (message.sender !== 'user' && message.sender !== 'agent') return false
      if (!message.isThinking) return true
      return Boolean(message.text) || getActiveToolTrace(message).length > 0
    })
    .slice(-limit)
}

export function estimateDraftTokens(inputValue: string, attachedFiles: any[]): number {
  const attachmentPayload = (attachedFiles || []).map(file => ({
    name: file?.name || '',
    path: file?.path || '',
    content: file?.content || ''
  }))
  return estimateTextTokens(inputValue) + estimateTextTokens(attachmentPayload)
}
