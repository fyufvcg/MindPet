/* eslint-disable @typescript-eslint/no-explicit-any */
import { useEffect, useRef } from 'react'

const MAX_TOKEN_LOGS = 1000
const EVENT_BATCH_DELAY_MS = 200

export interface RuntimeTokenLog {
  id: string
  model: string
  provider: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  estimated?: boolean
  timestamp: number
  sessionId?: string
  messageId?: number
  source?: 'desktop' | 'wechat' | 'qq'
}

interface TokenUsageRuntimeOptions {
  setTokenLogs: (updater: (logs: RuntimeTokenLog[]) => RuntimeTokenLog[]) => void
}

/** Batches high-frequency token events and persists a bounded log at most once per second. */
export function useTokenUsageRuntime({ setTokenLogs }: TokenUsageRuntimeOptions): void {
  const pendingRef = useRef<RuntimeTokenLog[]>([])
  const flushTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    if (!window.api.onTokenUsage) return undefined

    const flushPending = () => {
      flushTimerRef.current = null
      if (pendingRef.current.length === 0) return
      const batch = pendingRef.current
      pendingRef.current = []
      setTokenLogs(previous => {
        const clearedAt = Number(localStorage.getItem('mindpet_token_logs_cleared_at')) || 0
        return [...previous, ...batch.filter(item => item.timestamp > clearedAt)].slice(-MAX_TOKEN_LOGS)
      })
    }

    const unsubscribe = window.api.onTokenUsage((data: any) => {
      pendingRef.current.push({
        id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
        model: data.model || 'unknown',
        estimated: Boolean(data.estimated),
        provider: data.provider || 'unknown',
        promptTokens: data.promptTokens || 0,
        completionTokens: data.completionTokens || 0,
        totalTokens: (data.promptTokens || 0) + (data.completionTokens || 0),
        timestamp: data.timestamp || Date.now(),
        sessionId: data.sessionId,
        messageId: data.messageId,
        source: data.source || 'desktop'
      })
      if (!flushTimerRef.current) {
        flushTimerRef.current = setTimeout(flushPending, EVENT_BATCH_DELAY_MS)
      }
    })

    return () => {
      unsubscribe()
      if (flushTimerRef.current) clearTimeout(flushTimerRef.current)
      flushPending()
    }
  }, [setTokenLogs])
}
