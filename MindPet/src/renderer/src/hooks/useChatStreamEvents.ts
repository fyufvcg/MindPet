/* eslint-disable @typescript-eslint/no-explicit-any, @typescript-eslint/explicit-function-return-type */
import { useEffect } from 'react'
import type { MutableRefObject } from 'react'

interface StreamUpdate {
  sessionId: string
  messageId: number
  content: string
}

interface ReasoningStatusUpdate {
  sessionId: string
  messageId: number
  status: string
  message?: string
}

interface UseChatStreamEventsOptions {
  updateSessionMessages: (sessionId: string, updater: (messages: any[]) => any[]) => void
  abortedReplyIdsRef: MutableRefObject<Set<number>>
}

/** Batches high-frequency LLM IPC events to one React update per frame. */
export function useChatStreamEvents({ updateSessionMessages, abortedReplyIdsRef }: UseChatStreamEventsOptions): void {
  useEffect(() => {
    if (!window.api.onLlmTextDelta) return undefined

    const pendingByMessage = new Map<string, StreamUpdate>()
    const pendingReasoningByMessage = new Map<string, StreamUpdate>()
    const pendingStatusByMessage = new Map<string, ReasoningStatusUpdate>()
    let frameId: number | null = null

    const flush = () => {
      frameId = null
      if (pendingByMessage.size === 0 && pendingReasoningByMessage.size === 0 && pendingStatusByMessage.size === 0) return
      const updatesBySession = new Map<string, Map<number, { text?: string; reasoningText?: string; status?: ReasoningStatusUpdate }>>()
      for (const update of pendingByMessage.values()) {
        const updates = updatesBySession.get(update.sessionId) || new Map<number, { text?: string; reasoningText?: string; status?: ReasoningStatusUpdate }>()
        const existing = updates.get(update.messageId) || {}
        existing.text = (existing.text || '') + update.content
        updates.set(update.messageId, existing)
        updatesBySession.set(update.sessionId, updates)
      }
      for (const update of pendingReasoningByMessage.values()) {
        const updates = updatesBySession.get(update.sessionId) || new Map<number, { text?: string; reasoningText?: string; status?: ReasoningStatusUpdate }>()
        const existing = updates.get(update.messageId) || {}
        existing.reasoningText = (existing.reasoningText || '') + update.content
        updates.set(update.messageId, existing)
        updatesBySession.set(update.sessionId, updates)
      }
      for (const update of pendingStatusByMessage.values()) {
        const updates = updatesBySession.get(update.sessionId) || new Map<number, { text?: string; reasoningText?: string; status?: ReasoningStatusUpdate }>()
        const existing = updates.get(update.messageId) || {}
        existing.status = update
        updates.set(update.messageId, existing)
        updatesBySession.set(update.sessionId, updates)
      }
      pendingByMessage.clear()
      pendingReasoningByMessage.clear()
      pendingStatusByMessage.clear()

      for (const [sessionId, updates] of updatesBySession) {
        updateSessionMessages(sessionId, previous => {
          let messages: any[] | null = null
          for (let index = 0; index < previous.length; index++) {
            const message = previous[index]
            const update = updates.get(message.id)
            if (!update || !message.isThinking || abortedReplyIdsRef.current.has(message.id)) continue
            if (!messages) messages = [...previous]
            messages[index] = {
              ...message,
              ...(update.text ? { text: (message.text || '') + update.text } : {}),
              ...(update.reasoningText ? { reasoningText: (message.reasoningText || '') + update.reasoningText } : {}),
              ...(update.status ? { reasoningStatus: update.status.status, reasoningNotice: update.status.message } : {})
            }
          }
          return messages || previous
        })
      }
    }

    const unsubscribe = window.api.onLlmTextDelta(({ content, sessionId, messageId }) => {
      if (!content || !sessionId || !messageId) return
      const key = `${sessionId}:${messageId}`
      const pending = pendingByMessage.get(key)
      if (pending) pending.content += content
      else pendingByMessage.set(key, { sessionId, messageId, content })
      if (frameId === null) frameId = requestAnimationFrame(flush)
    })

    const unsubscribeReasoning = window.api.onLlmReasoningDelta?.(({ content, sessionId, messageId }) => {
      if (!content || !sessionId || !messageId) return
      const key = `${sessionId}:${messageId}`
      const pending = pendingReasoningByMessage.get(key)
      if (pending) pending.content += content
      else pendingReasoningByMessage.set(key, { sessionId, messageId, content })
      if (frameId === null) frameId = requestAnimationFrame(flush)
    })

    const unsubscribeStatus = window.api.onLlmReasoningStatus?.(({ status, message, sessionId, messageId }) => {
      if (!status || !sessionId || !messageId) return
      const key = `${sessionId}:${messageId}`
      pendingStatusByMessage.set(key, { sessionId, messageId, status, message })
      if (status === 'complete' || status === 'unavailable' || status === 'unsupported') {
        flush()
        return
      }
      if (frameId === null) frameId = requestAnimationFrame(flush)
    })

    return () => {
      unsubscribe()
      unsubscribeReasoning?.()
      unsubscribeStatus?.()
      if (frameId !== null) cancelAnimationFrame(frameId)
      flush()
    }
  }, [updateSessionMessages, abortedReplyIdsRef])
}
