import { useEffect, useState, useCallback, useRef } from 'react'
import type { CronTask } from './useAppStore'
import type { RuntimeTokenLog } from './useTokenUsageRuntime'

/** Observe persisted scheduling state; execution and notifications belong to the backend. */
export function useCronScheduler({ setCronTasks, setTokenLogs }: {
  setCronTasks: (updater: (tasks: CronTask[]) => CronTask[]) => void
  setTokenLogs: (updater: (logs: RuntimeTokenLog[]) => RuntimeTokenLog[]) => void
}): { error: string; refresh: () => Promise<void> } {
  const [error, setError] = useState('')
  const alive = useRef(false)
  const pending = useRef<Promise<void> | null>(null)
  const migrated = useRef(false)
  const refresh = useCallback((): Promise<void> => {
    if (pending.current) return pending.current
    pending.current = (async () => {
      try {
        if (!migrated.current) {
          const saved = localStorage.getItem('mindpet_cron_tasks') || localStorage.getItem('agentself_cron_tasks')
          if (saved) await window.api.migrateCronTasks(JSON.parse(saved))
          migrated.current = true
        }
        const tasks = await window.api.getCronTasks()
        if (alive.current && tasks) {
          setError('')
          setCronTasks(() => tasks)
          const clearedAt = Number(localStorage.getItem('mindpet_token_logs_cleared_at')) || 0
          const usage: RuntimeTokenLog[] = tasks.flatMap(task => (task.logs || []).flatMap(log => {
            const input = Number(log.usage?.promptTokens) || 0, output = Number(log.usage?.completionTokens) || 0
            const timestamp = Date.parse(log.time)
            if (input + output <= 0 || !Number.isFinite(timestamp) || timestamp <= clearedAt) return []
            return [{ id: `schedule:${task.id}:${log.id}`, model: log.usage.model || 'scheduled-task', provider: 'backend',
              promptTokens: input, completionTokens: output, totalTokens: input + output,
              estimated: Boolean(log.usage.estimated), timestamp, sessionId: log.sessionId, source: 'desktop' as const }]
          }))
          if (usage.length) setTokenLogs(previous => {
            const ids = new Set(previous.map(item => item.id))
            const oldest = previous.length >= 1000 ? Math.min(...previous.map(item => item.timestamp)) : 0
            const additions = usage.filter(item => !ids.has(item.id) && item.timestamp >= oldest)
            if (!additions.length) return previous
            const next = [...previous, ...additions].sort((a,b) => a.timestamp-b.timestamp).slice(-1000)
            return next
          })
        }
      } catch (e) { if (alive.current) setError(e instanceof Error ? e.message : String(e)) }
      finally { pending.current = null }
    })()
    return pending.current
  }, [setCronTasks, setTokenLogs])
  useEffect(() => {
    alive.current = true
    let timer: ReturnType<typeof setTimeout>
    const poll = async (): Promise<void> => {
      await refresh()
      if (alive.current) timer = setTimeout(() => { void poll() }, 5000)
    }
    const unsubscribe = window.api.onCronUpdated(() => { void refresh() })
    void poll()
    return () => { alive.current = false; clearTimeout(timer); unsubscribe() }
  }, [refresh])
  return { error, refresh }
}
